# 飞牛影视接入：内置播放器方案

> 目标：首页「继续观看」点卡片直接从断点播放，播放器自己实现（可弹幕、可控解码）。本文只写实现方案与已核实的事实；来源见每节末尾。
>
> 依据：
> - NAS 实测（影视 0.9.8 / 媒体 0.8.43，`local/probe-note.md`）
> - `rgcaafe/fnos_tv_danmu`（GPLv3，Java，ExoPlayer 2.18.7）对飞牛 API 的完整实现
> - `skymei/VisInkAndroidTVRelease` v1.0.6 APK 逆向（`local/visink-player-study.md`）
>
> 许可证注意：`fnos_tv_danmu` 是 GPLv3。**只借鉴接口契约与算法思路，不复制其代码**，避免 Aerial 被 GPL 传染。协议（API 路径、签名算法、弹幕格式）本身不受版权保护。

## 1. 结论先行

| 能力 | 结论 | 依据 |
|---|---|---|
| 取流（直连） | **已实测** | `GET /v/api/v1/media/range/{media_guid}` 带 `Range` 返回 206，4.7 GB 的 MKV 返回 `Content-Range: bytes 0-4095/4712357051` |
| 断点续播 | **可行**，`play/info` 返回秒级 `ts` | 实测 `play/info` 返回 `ts`，客户端 `STATE_READY` 后 seek |
| 进度回写 | **已实测** | `play/record` 写秒级 `ts` 返回 `code:0`，再读 `play/list` 值已更新 |
| 硬解 | **可行**，Media3 + 自定义 RenderersFactory | Aerial 已有 media3 1.8.1 |
| 软解兜底 | **可行**，FFmpeg 扩展或 Google 软解码器 | 参考实现用 jellyfin 的 ffmpeg 扩展 |
| 服务端转码 | **未打通**：`play/play` 在本机恒定返回 `code:1001 System busy, please retry later.` | 无必填字段时返回 1001 而非参数错误，推测缺 `video_guid`/`audio_guid` 等轨道信息；阶段 2 必须补测 |
| 弹幕 | **可行**，DanDanPlay 协议 + 自绘 Canvas | 与飞牛解耦，独立弹幕服务 |

## 2. 接口契约（Kotlin 侧要实现的全部请求）

基础地址：手机页填 `http://<nas>:5666` → 规范化成 `{host}/v/`（去掉末尾 `/`，截断到 `/v` 之前再拼 `/v/`）。

### 2.1 鉴权头（每个请求都要）

```kotlin
// 常量来自公开实现，会随影视版本变化；放进 AerialSettings，允许诊断页覆盖
const val API_KEY = "…"
const val API_SECRET = "…"

fun authx(path: String, bodyOrQuery: String?): String {
    val nonce = (100000..999999).random().toString()
    val ts = System.currentTimeMillis().toString()          // 13 位毫秒
    val dataMd5 = md5Hex(bodyOrQuery ?: "")                  // 有 body 用 body 原文；只有 query 用 query 原文
    val sign = md5Hex("${API_KEY}_${path}_${nonce}_${ts}_${dataMd5}_${API_SECRET}")
    return "nonce=$nonce&timestamp=$ts&sign=$sign"
}
```

- `path` = **仅 encodedPath**（如 `/v/api/v1/play/info`），不含 host 与 query。
- 头：`Authx`、`Cookie: mode=relay`、`x-trim-client: web`、`x-trim-client-version: 608`、`Content-Type: application/json`、`Authorization: <token>`（**不加 Bearer**）。
- `play/record` 额外需要 `Referer: {base}/v/video/{itemGuid}?media_guid={mediaGuid}`。
- 实测：我们的环境用 `x-trim-client-version: 631` 也能通过，说明服务端不严格校验版本号。
- 来源：`FnAuthUtils.java:11-46`、`AuthInterceptor.java:33-61`。

### 2.2 登录

`POST {base}/v/api/v1/login`

```json
{ "app_name": "trimemedia-web", "username": "…", "password": "明文", "nonce": "六位数字" }
```

返回 `data.token`，只保存在内存 + 加密偏好里，冷启动自动重登。参考实现**不加密密码**、无 token 刷新逻辑；Aerial 侧要补上 401/`code!=0` 检测并提示重新登录。

### 2.3 取流三连

| 步骤 | 请求 | 关键字段 |
|---|---|---|
| 1 | `POST /v/api/v1/play/info` `{item_guid}` | `media_guid`、`video_guid`、`audio_guid`、`subtitle_guid`、`ts`（**秒**）、`item.media_stream.resolutions[0]` |
| 2 | `POST /v/api/v1/stream` `{header:{User-Agent:[…]}, level, media_guid, ip: md5(用户名), nonce}` | `direct_link_qualities[]`、`qualities[]`、`video_stream`、`audio_streams[]`、`subtitle_streams[]`、`file_stream` |
| 3 | 直接播 `GET /v/api/v1/media/range/{media_guid}` | 支持 `Range`，返回 200/206 |

- `?direct_link_quality_index=N` 选云盘直链档位；只有 `.strm` 文件才用 `direct_link_qualities[].url` 外链（`\u0026` 要还原成 `&`）。
- URL 含 `.m3u8` → HLS 源，否则渐进式源。
- `stream` 失败**不阻断**，直接走 `media/range` 代理地址。
- `play/play`（换档位）body：`{media_guid, video_guid, video_encoder, resolution, bitrate, startTimestamp(秒), audio_encoder, audio_guid, channels, subtitle_guid}`，返回 `play_link`（**相对路径**）、`hls_time`、`subtitle_link`。换档位后要保留当前位置：先记 `currentPosition`，`READY` 后回 seek。
- 来源：`PlayerActivity.java:676-746`、`CloudStreamManager.java:118-304`、`QualitySelectHelper.java:79-226`。

**本机实测（2026-10-25，影视 0.9.8）**：

```
play/list[0]      《此处通往繁星》S01E01，guid=0196b183…，ts=1，duration=2471
play/info         media_guid=6cf24aa8…，ts=1
stream            code=0，qualities 21 档（4k 13777691 / 1080 5000000 …），
                  file_stream.file_name="此处通往繁星 S01E01 2160p.ADWeb.mkv"，size=4 712 357 051
media/range 206   Content-Range: bytes 0-4095/4712357051，application/octet-stream，前 12 字节 1a45dfa3（MKV）
play/record       写入 ts → code=0；重读 play/list 值已变
play/play         恒定 code=1001 "System busy, please retry later."（不含 video_guid/audio_guid）
```

- `qualities` 是服务端给出的档位表（`resolution` 为 `4k`/`1080`，`bitrate` 为 bps），可作为画质菜单数据源；`level` 参数传 0 或 1 结果一致。
- 直连播放按 `media/range` 即可，**不需要** `direct_link_qualities`（本机为空，那是云盘/STRM 专用）。

### 2.4 进度回写

`POST /v/api/v1/play/record`

```json
{ "item_guid": "…", "media_guid": "…", "video_guid": "…", "audio_guid": "…",
  "subtitle_guid": "_no_display_", "resolution": "1920x1080", "bitrate": 8000000,
  "ts": 1234, "duration": 2700 }
```

- 单位统一**秒**。`subtitle_guid` 用 `_no_display_` 表示关闭字幕。
- 触发点：首帧一次 → 之后每 15 秒 → `onPause`/`onStop` 补写。
- 这是「继续观看」能否对齐的关键：Aerial 首页读的 `play/list` 与这里写的是同一份记录。

### 2.5 剧集链

`GET /v/api/v1/episode/list/{parent_guid}` → `PlayListItem[]`（含 `guid`、`title`、`episode_number`、`season_number`、`ts`、`duration`）。用 `guid` 或 `episode_number` 定位当前集，`STATE_ENDED` 后取下一集，重跑 `play/info` 全流程。

## 3. 播放器架构（Compose 下）

### 3.1 依赖

Aerial 现有 `media3-exoplayer 1.8.1`（Android 5.0 最后一个版本）已够用，补三项：

```kotlin
implementation("androidx.media3:media3-exoplayer-hls:1.8.1")   // HLS 转码流
implementation("androidx.media3:media3-ui:1.8.1")              // 可选，若不自绘
implementation("androidx.media3:media3-datasource-okhttp:1.8.1") // 或者自写 DataSource
```

软解顺序：先用 `MediaCodecSelector.DEFAULT`（硬解优先），失败再降级，**不默认上 FFmpeg**。FFmpeg 扩展能解 DTS/TrueHD 这类电视普遍不支持的音轨，但会增加约 3–4 MB 且只支持 arm；Android 5.0 上可用的是 `org.jellyfin.exoplayer:exoplayer-ffmpeg-extension:2.18.7+1`（ExoPlayer 2.x，Media3 API 不兼容），Media3 版需要自己编译 `media3-decoder-ffmpeg`。**第一阶段不上 FFmpeg**，先跑通硬解，遇到不支持的音轨再补。

### 3.2 组件划分

```
fnos/
├── FnosClient.kt         Retrofit/OkHttp + AuthInterceptor（签名）
├── FnosModels.kt         play/info、stream、record、episode/list 的 @Serializable 模型
├── FnosAuth.kt           登录、token 内存持有、401 处理
├── FnosRepository.kt     resolve(itemGuid) → PlaybackRequest 的组装与缓存
└── PlaybackRequest.kt    纯数据：url、isHls、itemGuid/mediaGuid/轨道 guid、startTs

player/
├── PlayerScreen.kt       全屏播放器 UI（Compose，960×540 画布）
├── PlayerViewModel.kt    状态机：Preparing/Playing/Paused/Buffering/Error
├── PlayerController.kt   ExoPlayer 封装、切档位、软解降级、进度上报
├── SubtitleOverlay.kt    Cue 自绘（文本 + PGS）
└── danmaku/              见 docs/DANMAKU.md
```

关键点：

- **DataSource 要自己写**：`media/range` 需要带签名头与 `Range`，且读超时设为 0（大文件会断）。参考 `OkHttpExoDataSource`：`BaseDataSource` + 手动发 `Range: bytes=pos-`，非 200/206 抛 `IOException`。
- **播放器实例不放进 Composable**：放 `ViewModel`，`AndroidView`/`SurfaceView` 只挂 surface。切集、切档位不重建 Activity。
- **状态机隔离**：`PlayerController` 对外只暴露 `StateFlow<PlayerState>`，进度回写、弹幕时钟、UI 都订阅它。
- **焦点**：控制栏 5 秒自动隐藏；隐藏时 `←/→` 是 ±10 秒（1 秒防抖后再真 seek，期间显示目标时间），`OK/↑` 呼出控制栏，`BACK` 两秒内连按两次才退出。控制栏显示时方向键在按钮间移动。参考实现这套逻辑可用，但 `hasFocus()` 续期要改成 Compose 的焦点区域判断。

### 3.3 解码降级链

```
DEFAULT 硬解
  ↓ MediaCodec 初始化/运行时失败
Google 软解码器（omx.google.* / c2.android.*）
  ↓ 仍失败
请求服务端转码（play/play 指定位率档位 → HLS）
  ↓ 仍失败
终止并提示（不再无限重试）
```

- 错误分类用 Media3 `PlaybackException.errorCode`：`ERROR_CODE_DECODING_*`/`ERROR_CODE_DECODER_INIT_FAILED` 才降级；`ERROR_CODE_IO_*` 先重试（指数退避，最多 5 次），不降级。
- 参考实现的问题：软解切换后把偏好持久化成永久软解、且没重挂播放器引用。Aerial 侧只对**当前条目**生效，并在设置里给一个显式的「解码方式」三选一（自动/硬解/软解）。
- 切换解码器或档位后，先存 `currentPosition`，`STATE_READY` 后回 seek 一次。

## 4. 首页衔接（继续观看）

- 卡片点击 → `FnosRepository.resolve(guid)` → 启动 `PlayerActivity`（传 `guid` + `ts` 兜底）。
- 播放返回后刷新 `play/list`，保证进度条与刚看的进度一致。
- 进度回写与首页读取共用 `item_guid`；跨账号时缓存按 `host + username` 分目录。

## 5. 网络与安全

- `network_security_config.xml` 目前只对几个固定域名放行明文。NAS 是局域网任意 IP，**不能加域名白名单**：播放器与 API 走自定义 OkHttp，不走系统 HTTP 栈时不受该策略限制；若走系统栈，需要在配置里加 `<base-config cleartextTrafficPermitted="true"/>` 或用 `localhost` 代理。实现前先验证一次明文请求能否成功。
- 参考实现 `trust-all`（任意证书 + 任意 hostname）是为了兼容自签 NAS。Aerial 只在**用户勾选「忽略证书错误」**时启用，默认校验证书，并把证书指纹记到设置里。

## 6. 分阶段落地

| 阶段 | 交付 | 验收 |
|---|---|---|
| **1. 打通** | 登录 + `play/info` + `media/range` 直连播放 + `seek(ts)` + `play/record` 心跳 | 电视上从头文件实际播放，退出后首页进度前进 |
| **2. 可控** | 画质档位（`play/play`）、音轨/字幕轨切换、软解降级、错误分类与重试 | 切档位不掉进度；不支持的音轨能降级 |
| **3. 完整** | 剧集连播、片头片尾、SRT/ASS 字幕、快捷键与锁定 | 连续看 3 集不出错 |
| **4. 弹幕** | DanDanPlay 匹配 + 自绘引擎 | 见 `docs/DANMAKU.md` |

## 7. 阶段 1 已实现（2026-10-25）

| 文件 | 内容 |
|---|---|
| `fnos/FnosClient.kt` | OkHttp 同步客户端 + `suspend` 包装、`Authx` 签名、play/list、play/info、stream、record、item/list、封面 |
| `fnos/FnosModels.kt` | `FnosPlayItem`、`FnosPlayInfo`、`FnosQuality`、`FnosStream`、`FnosException` |
| `fnos/FnosStore.kt` | SharedPreferences `fnos`（地址/账号/密码）、内存 token、`configured` |
| `fnos/FnosShelf.kt` | 首页货架状态：列表、加载中、四种问题状态（未设置/登录失效/连不上/为空） |
| `fnos/FnosArt.kt` | 封面磁盘缓存（按服务器分目录），先读盘再联网 |
| `player/FnosDataSource.kt` | Media3 `BaseDataSource`，`Range` + 签名 + token，连接 10 秒、读超时 0 |
| `PlayerActivity.kt` | 内置播放器：`play/info` 续播、15 秒回写、自绘控制栏、遥控器映射 |
| `ui/home/HeroTitle.kt`、`HomeScreen.kt`、`HomeChrome.kt` | 全屏 Top Shelf：静态影片背景、续播信息和动作、页码；保留上游玻璃 Dock |
| `MainActivity.kt` | 启动初始化、每次回到首页刷新货架、`playFilm` |
| `wallpaper/PhoneServer.kt` + `assets/web/index.html` | 手机页「飞牛影视」组：地址/账号/密码、测试连接、退出登录 |

交互：第一排飞牛影视磁贴被选中时显示全屏影片背景和续播信息；按 ↑ 进入续播动作，←/→ 切换影片，↓ 或返回回到飞牛磁贴，确认进入 Aerial 内置播放器。磁贴本身的确认仍打开飞牛 TV；移到其他 App 时恢复用户壁纸。没有独立的横向续播卡片行。

焦点修复：续播动作的 ↓ 和返回显式请求原飞牛磁贴，↑ 进入顶部状态入口；顶部入口 ↓ 或返回回到续播动作。切换时先同步 `heroFocused` / `pillFocused`，避免延迟焦点请求再次抢回。极米 release 上已验证续播区 ↓ 回飞牛，再 → 移到芒果 TV。

**新增依赖**：`com.squareup.okhttp3:okhttp:4.12.0`。

播放器渲染改为同一个 Activity 中的默认层级 SurfaceView 与透明 Compose 控制层；不使用系统悬浮窗、抓帧铺底或缩小视频以容纳控制栏。启动前主动让壁纸播放器交出解码器，播放器停止时释放自身解码器。真机验证结果以本轮实际记录为准。

本轮极米 ironman / m7221（Android 8）release 验证：全屏续播背景、玻璃 Dock、移到其他 App 恢复壁纸、上键聚焦续播和左右换片已执行；AVC 3840×2160 由 `OMX.MS.AVC.Decoder` 解码，正常视频帧已捕获。最终版从服务端 30:13 续播，暂停时界面层级时间为 30:26，右键后为 30:36，随后退出回首页。

退出后再点同一续播动作，界面提示「已从 30:36 继续播放」，暂停位置 30:43；证明最终位置已写回服务端并在新播放器实例读取。测试真实推进了该条观看记录，未回退用户进度。

**捕获限制**：该设备播放 SurfaceView 视频时，`screencap` 未包含控制层，也未包含音量键触发的系统 OSD；不能据此断言投影画面上的控制层不可见。UI 层级确认标题、集数与时间存在，但实际投影上的控制栏可见性仍需现场观察。TextureView 路径出现彩色噪点，已撤回；最终版保留默认 SurfaceView 和透明 Compose，未保留 Dialog 或系统浮窗。`cmd package compile -m speed -f` 已执行，但设备固件抛出 `Can't create handler inside thread that has not called Looper.prepare()`，安装与应用启动成功。

## 8. 已知风险

1. **API 是逆向协议**，影视升级可能改签名密钥或字段；版本号写进设置，失败时诊断页给出原始响应。
2. **`ts` 精度**：秒级，首页进度条会有 1 秒内的误差，可接受。
3. **`play/play` 未打通**：本机 5 种字段组合都返回 `code:1001`。参考实现能跑通，差别在它先取到 `stream` 里的 `video_stream.guid`、`audio_streams[].guid` 再填进 body，Aerial 侧必须照此补齐后再测；打通前画质菜单只能展示、不能切换。
4. **Android 5.0**：Media3 1.8.1 是最后支持版本；HLS、HDR 相关 API 需要 `SDK_INT` 门控，lint NewApi 会报。
5. **两端账号一致性**：投影仪飞牛 TV 与网页端看的是不同记录，接入前必须先确认电视登录的服务器和账号。

## 9. 阶段 2 已实现（播放器控制，2026-10-27）

| 能力 | 实现 | 仍需真机确认 |
|---|---|---|
| 字幕 | `Player.Listener.onCues` 自绘：文本白字黑描边，最多 3 行，限制在画面矩形内；位图字幕用 `Cue.bitmap` 以 Image 绘制在文本上方；字幕轨选择与「关闭」，按剧名记忆 | 投影上的字号与位置、PGS 位图的缩放 |
| 音轨/字幕轨 | `player.currentTracks` 枚举，`setTrackTypeDisabled` + `TrackSelectionOverride`，不重建播放器 | 多音轨文件切换不卡顿 |
| 剧集 | `episode/list/{parent_guid}` 菜单（当前集标记），`STATE_ENDED` 自动进下一集，切换前先回写进度并显示「Up next」 | 一季内连播；跨季不在本轮范围 |
| 错误 | 网络/IO 退避重试 2 次（1.5s、3s）；解码类失败先换 `omx.google.*`/`c2.android.*` 软解一次，再失败终止；HTTP 401/403 与服务器 `code != 0` 不重试；失败页给「重试」 | 软解切换后位置恢复、失败页可读 |
| 画质 | `play/play` 用 `stream` 的 `video_stream.guid`、`audio_streams[].guid`、`codec_name`、`channels`，加 resolution/bitrate/startTimestamp 组装 | 服务器仍返回非 0 时菜单置灰并显示服务器原文；若返回 `.m3u8`，当前构建没有 `media3-exoplayer-hls`，会明确报错而不是假切换 |
| 倍速 | 0.5–2.0，`setPlaybackSpeed`，全局记忆 | — |
| 画面比例 | 自适应 / 拉伸 / 16:9，沿用 `fitVideo()` 几何，菜单里三选一 | 拉伸档在投影上的观感 |
| 音量 | VOLUME_UP/DOWN/MUTE 交给系统，不消费、不唤醒控制栏 | — |
| 菜单 | 底部按钮行（Audio/Subtitles/Episodes/Speed/Quality/Aspect/Danmaku）+ 玻璃面板；方向键移动、OK 选择、返回关闭；菜单打开时不自动隐藏 | 遥控器全流程可达 |
| 弹幕 | 按契约调用 `DanmakuOverlay`，菜单开关写 `DanmuPrefs.enabled` | 见 docs/DANMAKU.md |

**片头片尾**：实测的 `play/info` 返回里没有 intro/outro 字段（全仓库也没有），因此没有实现跳过片头，也没有伪造字段。

**字幕/菜单文案**：`player_*` 与 `danmu_*` 共 29 个键已加入 en/zh/ru/uk/kk 五套 `strings.xml`，占位符一致。

## 10. 按键模型与弹幕接入修正（2026-10-27）

### 10.1 方向键不再抢走快进

上一版的 `dispatchKeyEvent` 是 `if (media || !controlsVisible) seekKey(...) else navigate(...)`，而
`seekTo()` 里调了 `wake()`：**第一次按右键快进成功并弹出控制栏，第二次按右键就变成在按钮间移动**。
现在按飞牛 TV 的做法分开两条路：

| 状态 | 键 | 行为 |
|---|---|---|
| 控制栏收起 | ←/→、快退/快进 | 快进快退，画面右上角显示「▶ 30 秒」，**不弹控制栏** |
| | ↑、MENU | 打开控制栏 |
| | OK | 播放/暂停 |
| | BACK | 第一次提示「再按一次返回键退出」，2 秒内再按退出 |
| 控制栏展开 | ←/→ | 在按钮间移动 |
| | ↑、OK | 打开该按钮的面板 |
| | ↓、BACK | 收起控制栏 |
| 面板展开 | ↑/↓ | 选择条目 |
| | OK | 生效 |
| | BACK | 关闭面板 |

**加速快进**：`player/SeekPace.kt`。间隔 ≤3 秒的连按属于同一段，步长 10 → 30 → 60 → 120 秒；
停下 1.5 秒（提示消失）后归零。飞牛原版把步长挂在「距上次按键的间隔」上（3–6 秒给 40/60/80 秒，
超过 6 秒给 120 秒），单按就会跳两分钟，与用户预期相反，因此这里保留意图、不照抄键位规则。

### 10.2 弹幕服务的 token 在路径里

实测弹幕服务（`<host>:9321`）的鉴权方式：`Authorization: Bearer`、`X-API-Key`、`apikey`
三种请求头全部 **401**，正确形式是把 token 作为**路径段**：

```
POST http://<host>:9321/<token>/api/v2/match   {"fileName":"嫁金钗 S01E01"}
GET  http://<host>:9321/<token>/api/v2/bangumi/7462531
GET  http://<host>:9321/<token>/api/v2/comment/10002?format=json
```

实测返回：match 命中 `episodeId=10002`；bangumi 20 集，`episodeNumber` 是**字符串** `"1"`；
comment 18222 条，条目形如 `{"p":"0.00,1,16777215,[youku]","m":"…"}`。

因此：

- `DanmuPrefs.server` 存**完整基址**（含 token 路径），手机页新增「弹幕服务地址」一栏；
- 缓存 key 加入服务器地址，换服务不会读到旧缓存；
- `DanmuClient` 兼容 `bangumi.episodes` 嵌套与字符串形式的 `episodeNumber`。

**未在真机验证**：本轮按要求只改代码、编译通过，没有安装到投影仪，也没有实际播放验证字幕、弹幕和按键。

## 11. 上游 1.67 合并与投影仪实测（2026-10-28）

- 合并 upstream `a07a6ae`，版本 1.67 / 81；保留飞牛 Hero、播放器、弹幕和守护恢复修复。首页几何跟随上游每排列数。
- Standard/Guard release 构建通过，116 项单元测试无失败；手机页脚本语法与五语文案对齐通过。覆盖安装保留数据。
- 极米 ironman Android 8：`OMX.MS.HEVC.Decoder` 硬解 3840×2160，SurfaceView 1920×1080，续播正常；飞牛横移到芒果恢复壁纸。
- 真实按键日志确认：控制栏隐藏时多次右键持续 seek，`bar=false`；上键打开后左右只移动菜单焦点。返回重复事件不计作第二次按压，反向 seek 从 10 秒重新开始。
- 暂停时界面树确认 AAC 音轨、当前样本无字幕轨、剧集列表、倍速 1.25 当前标记和画质档位。未验证字幕绘制、自动连播或服务器画质切换成功。
- 保存用户授权的弹幕基址（含路径 token，仅设备存储）；投影仪加载 5000 条抽稀弹幕，日志确认活动轨道出现。视频截图会遗漏叠加层，弹幕视觉可读性尚未现场确认。
- 测试中曾收到非预期的 `ACTION_MAIN` 返回首页，来源未确定；未将静态代码猜测当作守护根因。保留低频诊断用于后续定位。
- 测试推进真实观看记录；结束后暂停并返回首页，补回悬浮显示授权。系统 speed 编译仍报固件 `Looper.prepare()` 异常。未执行重启、待机验收。

## 12. Top Shelf 使用整剧背景与透明 Logo（2026-10-28）

- 素材来源为 `GET /v/api/v1/item/{guid}` 的 `data.backdrops` 与 `data.logos`，两者都是图片路径字符串；通过已登录的 `/v/api/v1/sys/img{path}?w={width}` 下载，不使用单集 `poster`。
- 续播 Episode 的 `parent_guid` 是 Season；再读取 Season 的 `parent_guid` 得到 TV。整剧素材来自 TV，电影直接使用 Movie 本身。不能把 Season 或媒体库的 `ancestor_guid` 当成整剧。
- 《嫁金钗》实测：整剧背景 1920×1080 WebP，标题 Logo 800×306 WebP，含透明通道；与用户给出的飞牛详情截图一致。无鉴权返回 `Auth Failed`，图片加载继续携带当前会话。
- Logo 等比完整显示在标题区域，保留季集、进度与继续播放；电影或剧集未提供 Logo 时使用文字标题，未提供背景时保留桌面壁纸，不回退单集剧照或竖海报裁切。
- 验证：整剧素材解析与账户隔离 10 项回归用例通过，全量 126 项测试通过，Standard／Guard release 均构建成功。恢复本机 ADB 服务后已覆盖安装极米 ironman；截图确认整剧背景、透明金色 Logo、季集进度、时钟天气与玻璃 Dock 正常，焦点回 Dock 后移到芒果恢复普通壁纸。未启动影片或写观看记录。

## 13. 网络策略：Android 9 起必须放行明文 HTTP

飞牛影视和弹幕服务是局域网 HTTP 设备，地址由户主在手机页填，列不进 `network_security_config.xml` 的域名表。Android 9（SDK 28）起系统会拒绝这类请求，报 `CLEARTEXT communication to <NAS> not permitted by network security policy`，首页续播变成 0 条、`problem=Unreachable`；Android 8 及以下不校验，所以极米上同一份 APK 是好的，容易误判。

本 fork 因此加了 `base-config cleartextTrafficPermitted="true"`（2026-10-28，小米 MFTP0 上实测：修复前 0 条，修复后 101 条续播）。含义是允许 http:// 请求，HTTPS 仍按系统证书链校验；代价是 App 内任何地址都能走明文，只在本 fork 接受。
