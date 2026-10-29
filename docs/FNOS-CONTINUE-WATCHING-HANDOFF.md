# 飞牛续播 / Apple TV 首页：对话与代码交接

> 日期：2026-10-25。本文供另一位开发者或 AI 接手。包含需求演变、当前代码、实际故障和未验证边界，不代表功能已验收。用户要求停止本轮实施，整理文档交接。
> **最新要求：不要再代替用户操作电视测试。修复后编译、安装，交给用户亲自测试。不要注入遥控器键、点屏幕、启动播放或改观看进度。**
> 本文不包含密码、会话 token、手机配置页的随机访问令牌。账号凭据由用户另行提供。

## 1. 用户最终想要什么

项目是 `happyhaha1/aerial-tv`，fork 自 `pengchujin/aerial-tv`。Android TV 桌面，Kotlin + Compose，包名 `app.orchard.tv`。

1. 同步上游更新，同时保留飞牛影视接入和内置播放器。
2. 首页续播参考 Apple TV Top Shelf，并参考 `SiaBuilt/lumen-tv`，但希望更像 Apple TV。
3. **不是额外加一排续播卡片。** 选中第一排飞牛图标时显示全屏静态影片封面、左侧标题 / 季集 / 进度 / 继续播放按钮、轻量页码；底部保留原版玻璃 Dock、磁贴与用户 App 排列。
4. 移到其他 App 恢复原壁纸。不要为了飞牛永久改用户时钟偏好。
5. 续播展示时，顶部时间、天气、Wi-Fi 和设置 / 控制中心入口必须保留，并且遥控器可达。
6. 点击继续播放，使用 **Aerial 内置播放器** 从服务端断点播放，退出保存进度，下一次继续。
7. Aerial 应成为用户实际使用的主桌面，**实体遥控器 Home 要返回 Aerial**。
8. 飞牛账号可以切换；用户报告另一个账号在配置页显示密码错误，需要修复。
9. UI 和动效要美观。用户希望用 `claude-sonnet-5-5` 设计；目录有该模型，但本机 Claude CLI 未安装，无法实际启动。本轮使用配置中的 `designer` 路由子智能体，不能声称已调用 Sonnet。

## 2. 用户最新反馈：以此为准

最新两条真实故障，尚未解决 / 验收：

- **焦点被困在 Wi-Fi（顶部状态入口）与继续播放之间，无法下移到应用栏。**
- **按实体 Home 仍进入极米原桌面，不是 Aerial。**

此前助手用 ADB 操作后曾称 Home 接管完成，这与用户实体遥控器反馈冲突；以用户反馈为准，不能继承“已完成”结论。

用户明确说：**“不要自己测试了，每次你改好后，我自己测试吧。”** 不要为了确认反馈再次复现上述故障。

## 3. 当前仓库与安装版本

- 本地分支 `main` 已 fast-forward 到上游 `f1c8ea9`：`1.49: frames for TCL x32a0, Xiaomi HDMI order, Skyworth 75A7D, VIDAA launcher`。
- 上游实际标签 / versionName 是 **v1.49 / 1.49**（用户口述为 1.4.9），`versionCode = 57`。
- 新同步的另外两个提交：`9230b90`、`d046f69`。
- 同步操作：`git stash push -u` 保存本地工作，`git merge --ff-only upstream/main`，再 `git stash apply`。`docs/HANDOFF.md` 的旧版本历史冲突保留上游部分；飞牛相关修改保留。
- **大量飞牛代码仍为未提交修改 / 未跟踪文件，有的文件暂存区与工作区不同。不要 reset、checkout 覆盖或 clean。**
- 保留了名为 `fnos-before-upstream-1.49` 的 stash 作为恢复来源，尚未删除。按名称确认，不依赖固定 stash 序号。
- 最近成功编译并安装到投影仪的是 1.49 standard release，包含顶部栏恢复与登录修复。
- **最新焦点修正是在最后一次安装后改的，尚未编译、尚未安装。** 所以用户正在用的包仍有焦点循环。
- Standard、Guard 两个 release 在最后安装之前均编译成功。Guard 只是构建产物，不是这台投影仪安装的 flavor。
- 没有在本轮创建 PR，也没有推送远端。

## 4. 设备与服务

- 投影仪的当前 ADB 地址保存在本机 `local/`，安装时显式指定 `TV`，不要沿用历史地址。
- 极米：`XGIMI TV` / `ironman` / `m7221`，Android 8.0、SDK 26，1920×1080，240 dpi。
- 此设备不是 `docs/devices/xgimi-z7x.md` 里已验证的 Android 9 Z7X；那个文档只能作参考。
- 飞牛 TV：`com.trim.tv`，分析过版本 1.4.2 / versionCode 14209。
- NAS 影视入口：`http://<nas>:5666/v`，影视 0.9.8、媒体服务 0.8.43（此前实测）。
- 影视账号和密码仅保存在本机配置，不记入版本库或转发给其他代理。

## 5. 焦点故障：已定位代码，最新修正未构建

相关：

- `app/src/main/java/app/orchard/tv/ui/home/HomeScreen.kt`
- `app/src/main/java/app/orchard/tv/ui/home/HeroTitle.kt`
- `app/src/main/java/app/orchard/tv/ui/home/HomeChrome.kt`

设计子智能体此前把 Hero 的 Up 和 Down 都接到 `onPill`，顶部又通过焦点搜索返回 Hero，造成用户报告的循环。不能依赖全屏 Hero 上的几何自动找焦点。

用户最后反馈后，主线程做了以下**未构建修改**：

- `HeroTitle` 新增必填 `onDock: () -> Unit`，Up 仍走 `onPill`，Down 改走 `onDock`。
- `HomeScreen` 的 `onDock` 清掉 `heroFocused` / `pillFocused`，显式请求飞牛磁贴的 FocusRequester。
- 顶层旧的“pill + Up 回 Hero”拦截替换为“pill + Down 显式请求 Hero”。

目标路径应是：

```text
顶部状态入口
    ↑ / ↓
继续播放（←/→ 换影片）
    ↑ / ↓
飞牛磁贴 / Dock（←/→ 移动 App）
```

待接手者检查整个按下 / 抬起与 `onFocused`、`LaunchedEffect` 的状态转换，避免抬起事件再次触发另一层导航。用户应验证 Down 最终能到 Dock，Back 同样不被困住。不要继续只用代码推断宣称已修复。

顶部栏当前已安装包实际截图显示：左侧时间和天气，右侧 Wi-Fi、时间与控制中心。**时间有重复显示**，可以后续统一，但不得再把天气 / 设置藏掉。大时钟设置下 Hero 临时使用紧凑时钟，离开恢复用户偏好。

集成中曾出现 `Padding must be non-negative`：Hero 给 header 传零 margin，header 再减焦点留白。现已用非负 padding，并把 Hero 外层水平 safe margin 对齐；修复版已成功启动。不要回退到那个崩溃版本。

## 6. Home 故障：真实默认桌面尚未成立

已有事实：

- `cmd package set-home-activity --user 0 app.orchard.tv/.MainActivity` 返回 `Success`。
- 随后的 `resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME` 仍输出 `android/com.android.internal.app.ResolverActivity`。
- 用户实体 Home 仍返回 `com.xgimi.home/com.xgimi.module.cellview.home.ui.HomeActivity`。
- 不能把命令返回 Success 当成完成。

本轮配置过：

- 授予 `android.permission.WRITE_SECURE_SETTINGS`。
- `appops set app.orchard.tv GET_USAGE_STATS allow`。
- 开启 `app.orchard.tv/app.orchard.tv.guard.HomeGuardService`，**保留原有** `com.xgimi.duertts/com.xgimi.duertts.MonitorService`。
- 部分 ADB 操作后看到 Aerial 前台与守护绑定；但这不能证明实体键正确，也未验证重启 / 待机行为。

相关实现：

- `guard/HomeGuardService.kt`：`refreshStockHomes`、`onAccessibilityEvent`、`foregroundCheck`、`updateKeyFilter`、`homeKey`。
- `guard/GuardSetup.kt`：`ensureEnabled`、守护重绑 / 复活设置。
- `tools/install.sh`：默认桌面设置、解析检查、无障碍追加、权限授予。
- `docs/devices/xgimi-z7x.md`、`docs/devices/tcl-m7221-cn.md`：相似固件参考，不能照搬 TCL 系统属性到极米。

最后子智能体 `FixPhysicalHome` **只调查，没有修改任何文件，已停止**。其推测尚无真机证据：

1. 极米原桌面可能不向第三方无障碍发窗口事件；SDK26 当前没有默认启动 Android6/VIDAA 那条 usage polling。
2. HOME 注册 Activity 与实际显示 Activity 可能不同。
3. HOME query 的低 priority 过滤可能遗漏原桌面。

以上是待查假设，不是根因定论。不要盲目对所有系统扩展轮询，也不要盲目放开所有窗口 / 包识别，避免看视频时拉回 Aerial。

**禁止停用 / 卸载系统桌面、root、猜系统属性或覆盖别人的无障碍服务。** 需要固件级设置时先找实际证据。

## 7. 账号登录问题：已改代码，未完成用户配置页验收

主线程使用用户提供的凭据，直接对 NAS 发签名 v1 登录请求，返回 `code:0`、存在 token。只输出成功标志，没有打印密码 / token。因此**NAS 认可这组密码**，不能继续认定密码错。

子智能体修复文件：

- `fnos/FnosClient.kt`
- `fnos/FnosStore.kt`
- `fnos/FnosShelf.kt`
- `wallpaper/PhoneServer.kt`
- `assets/web/index.html`

发现并修复：

1. `FnosClient.call` 原用 `url.removePrefix(baseUrl)` 取签名路径。Test 传裸 `<nas>:5666` 而 login 规范化为带协议 URL，会把完整 URL 签进去。现取 `url.toHttpUrl().encodedPath`。
2. Save/Test 的空密码原可跨账号复用旧密码。现仅在规范化服务器与用户名一致时沿用已存密码；换账号必须输入密码。
3. 登录和货架请求引入 revision / generation 校验，旧账号请求完成不能覆盖新账号。
4. Save/Test 统一 trim host / user；**不 trim 密码**。保存失败保留输入；Test 不写入持久配置或 token。
5. `FnosStore.client()` 带用户名。

表单使用 URLSearchParams → NanoHTTPD parseBody，未发现多余解码，也未发现会裁掉密码首尾字符的处理。用户当时那一次报错的确切触发条件尚未复现，不能把所有缺陷都说成已证实根因。

这些登录修改已随最近 1.49 安装。但**没有完成配置页 Test/Save 的真实闭环**。用户已要求自己测试，不再代测。

建议交给用户的步骤：带协议 / 裸 IP 均可 Test；同账号空密码可沿用；换账号空密码必须要求重新填写；切账号后首页内容只来自新账号。不要在错误提示或日志暴露密码 / token。

当前 `FnosStore` 仍将密码保存为私有 SharedPreferences 明文、token 只在内存。此前对话曾承诺加密，但代码没有做到，不能对外声称已加密。若做凭据加密，需要兼容 Android 5 的项目约束。

## 8. 内置播放器当前状态与黑屏历史

相关：`PlayerActivity.kt`、`player/FnosDataSource.kt`、`MainActivity.playFilm`。

当前方向：

- 全屏默认层级 SurfaceView + 同 Activity 透明 Compose 控制层。
- 启动前 `LiveFilm.onHome?.letDecoderGo()` 释放壁纸解码器。
- 停止释放播放器；顺序提交进度，避免旧心跳覆盖退出位置。
- 直连 `media/range`，按 `play/info.ts` 断点 seek；15 秒进度回写。

已做过的观察：AVC 3840×2160 由 `OMX.MS.AVC.Decoder` 输出过正常画面；曾从 30:13 续播、30:26 暂停、快进至30:36，退出重开显示从30:36继续。这只是那些路径的旧实测，不代表所有影片 / 控制层 / 实体遥控器均通过。

失败尝试：

- 缩小视频到76%、系统悬浮窗、抓帧底图、独立 Dialog：均已撤除，不要恢复这些试验。
- TextureView：截图出现彩色噪点，已撤回；是否实际投影也花屏没有用户现场确认。
- 播放 SurfaceView 时 `screencap` 连系统音量 OSD 都没有捕获。**截图只见视频，不足以证明投影上的控制栏不可见。** UI hierarchy 读到标题 / 集数 / 时间，但也不等于现场视觉验收。
- 之前曾把测试脚本重复按键误判成播放器秒退；不能沿用那个错误根因。

用户曾明确报告黑屏有声音。现有画面观察不足以宣称所有黑屏都根治；由用户在新版本反馈影片 / 情境，再按真实故障排查。

`cmd package compile -m speed -f app.orchard.tv` 在该 SDK26 固件抛出 `Can't create handler inside thread that has not called Looper.prepare()`。之前已执行但失败，不要声称 speed 编译成功。

## 9. 飞牛 API 与研究结论

详细协议：`docs/FNOS-PLAYER.md`。弹幕方案：`docs/DANMAKU.md`。早期文档有过期内容，需与当前代码核对。

主链路：

```text
POST /v/api/v1/login → session token
GET  /v/api/v1/play/list → 续播列表
POST /v/api/v1/play/info {item_guid} → media_guid / ts
POST /v/api/v1/stream → 轨道与画质元数据
GET  /v/api/v1/media/range/{media_guid} → Range 206 媒体
POST /v/api/v1/play/record → 秒级进度
```

- 请求签名 `Authx`：path 只取 encodedPath；body 按实际字节签；无 body 有 query 时按协议签 query。
- `Authorization` 是 token 原值，不加 Bearer。签名常量见本机代码，不复制到交接文档。
- 列表 data 为裸数组；duration=0 不做除法。
- `play/info` envelope duration 可能0，当前代码回退 item.duration / runtime分钟转秒。
- 原片取流 Range206、封面、列表、回写已做过 NAS 实测；`play/play` 服务端转码请求此前始终 code1001，**未打通**。

未来阶段：硬解能力 / 软解 / 转码降级、音轨字幕、连播、片头片尾、弹幕。不要把它们算进已经完成的阶段1。

弹幕参考是 DanDanPlay 独立服务协议，并非飞牛账号自带接口。默认NAS:9321只是参考项目约定，不应默认用户必然部署。

## 10. 为什么放弃外部飞牛 TV 跳转作为主路线

早期方案2是构造 `GUID` + `media` 给飞牛 `PlayerActivity`。最终静态分析：

- `com.trim.tv.bean.MediaEntity` 是 Serializable，不是 Parcelable。
- Bundle.get 后外部 check-cast；不存在“任意对象都能读”和“需要共用 classloader”的保证，早期说法不正确。
- 与目标类 serialVersionUID / 字段结构耦合；无完整 Intent 真机成功证据。
- 起点来自服务端观看记录，没有 Intent ts 参数。但这**不等于外部续播绝对不可行**，仅说明不能注入任意 offset。
- 用户已经明确选择内置播放器，不应再把“只打开飞牛首页”当完成交付。

## 11. 参考项目与代码许可

- Apple Top Shelf：https://developer.apple.com/design/human-interface-guidelines/top-shelf
- Lumen：https://github.com/SiaBuilt/lumen-tv
- VisInk：https://github.com/skymei/VisInkAndroidTVRelease （发布仓库，无完整应用源码；有本地APK研究记录）
- 飞牛取流 / 弹幕：https://github.com/rgcaafe/fnos_tv_danmu （研究版本 fc60135，GPLv3）

借鉴协议与设计思路，不直接复制 GPL 代码；分发依赖前核对许可证。此前把弹幕渲染库、序列化格式和软件解码能力讲得过于确定，接手应以源码 / APK 实际证据为准。

## 12. 关键文件索引

| 文件 | 职责 |
|---|---|
| `ui/home/HomeScreen.kt` | Hero显示条件、顶部栏、Dock、焦点和浮层 |
| `ui/home/HeroTitle.kt` | 全屏封面、标题、进度、续播动作、页码、上下键 |
| `ui/home/HomeChrome.kt` | 时钟天气、状态胶囊、玻璃底板 |
| `fnos/FnosClient.kt` | 签名、登录、影视 API |
| `fnos/FnosStore.kt` | 账号配置、会话revision、密码沿用规则 |
| `fnos/FnosShelf.kt` | 续播列表、Hero索引、旧请求失效 |
| `fnos/FnosArt.kt` | 封面缓存；目前按server隔离，未完整按账号隔离 |
| `PlayerActivity.kt` | 全屏播放器、控制层、生命周期、回写 |
| `player/FnosDataSource.kt` | OkHttp / Range / 签名媒体数据源 |
| `MainActivity.kt` | 初始化、刷新、播放入口、壁纸解码器释放 |
| `wallpaper/PhoneServer.kt` | 手机页 API、Test/Save |
| `assets/web/index.html` | 五语手机页飞牛配置 |
| `guard/HomeGuardService.kt` | 原桌面识别、Home处理、无障碍 / usage观察 |
| `guard/GuardSetup.kt` | 配置授权后的守护重建 |
| `docs/FNOS-PLAYER.md` | 主方案与旧实测；部分字段描述仍应复核 |
| `docs/HANDOFF.md` / `docs/ARCHITECTURE.md` | 必须遵守的项目规范与上游架构 |

旧 `TopShelf.kt` / `ShelfCard.kt` / `ShelfGlass.kt` 卡片行已删除；不要按旧架构文档重新接一排卡片。

本地调研记录可能位于 `local/probe-note.md`、`local/fnos-handoff.md`、`local/visink-player-study.md`；只在本机查看，不提交 local/。临时反编译文件随清理可能已不存在，不能假定仍在。

## 13. 接手工作顺序与验收

1. 先看现有未提交修改，保留用户工作，确认最新焦点补丁尚未构建。不要重新设计整个首页。
2. 修完整 Header / Resume / Dock 焦点状态机，让 Down 有明确目的地，消除抬起二次导航；检查恢复焦点与返回路径。
3. 修实体 Home 的守护 / 默认桌面路径，先查固件实际HOME注册、事件可见性与过滤条件；上面三条仅是假设。不要再只靠ADB注入宣称完成。
4. 保留并检查账号登录修复，修正错误分类，密码不泄露。没有用户允许不要继续登录 / 切账号 / 播放测试。
5. 编译两个release，覆盖安装到用户指定设备，不清除数据。由用户亲自验收，不代按键。

用户验收清单：

- [ ] 飞牛封面显示时，时间天气与Wi-Fi/设置均可见。
- [ ] 顶部按下到继续播放，再按下到Dock；可左右选择其他App；按上可反向返回。
- [ ] 实体Home从其他App回Aerial，待机/重启行为另行明确验证，不冒充通过。
- [ ] 账号 Test 与 Save 成功，切账号不沿用另一个账号的密码 / 列表。
- [ ] 用户选择影片后画面和声音正常、暂停控制栏可见、退出位置能续播。
- [ ] 玻璃Dock、原壁纸、App排序、户主照片与系统App均保留。

## 14. 构建与操作约束

本机最近成功命令：

```sh
env JAVA_HOME="$HOME/.local/share/mise/installs/java/temurin-17.0.21+9" \
  ./gradlew --offline assembleStandardRelease assembleGuardRelease
adb -s "$TV:5555" install -r \
  app/build/outputs/apk/standard/release/app-standard-release.apk
```

- 项目规范列出的另一 Java 路径是 `/opt/homebrew/opt/openjdk@17`，使用实际存在的17版本。
- 之前 `local.properties` 的SDK配置曾使用 `/private/tmp/android-sdk`，路径不稳定，换环境先检查；不要复制这个假定。
- 真机使用release，禁止debug性能结论。
- 保持minSdk21、960×540dp逻辑画布、五语资源、现有Motion与Pressable约定。
- 不要提交local/、用户密码、token、NAS私人观看记录或未脱敏QR。
- 本轮停止前无后台构建 / 安装任务；最后的Home调查子智能体已停止，确认无文件修改。后续不要再唤醒旧任务。
