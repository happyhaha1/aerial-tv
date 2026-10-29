# 弹幕引擎（DanDanPlay 协议 + 自绘 Canvas）

> 弹幕与飞牛解耦：数据来自独立的弹幕服务（DanDanPlay 兼容），播放器只负责提供时间与暂停/拖动状态。本文覆盖数据格式、匹配策略、渲染模型三部分，算法来自 `rgcaafe/fnos_tv_danmu`（GPLv3，只借鉴思路，不复制代码）。

## 1. 数据来源

服务地址是一个**完整基址**，token 作为路径段放在最前面（实测，2026-10-27）：

```
http://<host>:9321/<token>
```

`Authorization: Bearer`、`X-API-Key`、`apikey` 三种请求头全部返回 **401 Unauthorized**；只有路径形式
可用（依据是该服务页面脚本的 `buildApiUrl()`：`(currentToken ? '/' + currentToken : "") + path`）。
地址在手机页「弹幕服务地址」里填，存进 `DanmuPrefs.server`；留空沿用同 NAS 的 9321 端口，需要完全关闭时在播放器弹幕菜单选择「关」。

| 用途 | 请求 | 实测 |
|---|---|---|
| 按文件名匹配 | `POST {base}/api/v2/match`，body `{"fileName":"剧名 S01E03"}` | 200，`matches[0].episodeId` |
| 番剧集列表 | `GET {base}/api/v2/bangumi/{animeId}` | 200，`bangumi.episodes[]`，`episodeNumber` 是**字符串** |
| 取弹幕 | `GET {base}/api/v2/comment/{epId}?format=json` | 200，`comments[]`，18222 条样本 |

实测 2026-10-27（`嫁金钗 S01E01`）：18222 条原始弹幕；`DanmuClient` 按总量上限抽稀后进入引擎 5000 条，
首条解析为 `mode=1（滚动）, atSec=0.0, colorArgb=-1（白）`。同一轮用真实 token 基址跑通了
match → bangumi → comment 整条链，因此鉴权与解析在真机上只剩渲染部分待确认。

条目格式（DanDanPlay 标准）：

```json
{ "m": "弹幕文本", "p": "12.34,1,16777215,abcd1234" }
```

- `p` 按逗号切分：`[0]` 时间（秒，float）、`[1]` 模式、`[2]` 颜色（RGB 十进制）、`[3]` 用户 ID。
- 模式：`1–3` 普通滚动（统一为 `1`）、`4` 底部固定、`5` 顶部固定、`6` 从左向右的逆向滚动。高级／代码／BAS 等其他模式跳过，不显示为普通文字。
- 颜色：`0xFF000000 or rgb`。无逗号时 `p` 直接当时间、颜色取 `c` 字段。
- 无字号字段；无弹幕 ID/点赞数。

## 2. 匹配策略

入参拼成 `"{tv_title} S{ss}E{ee}"`（`tv_title` 来自 `play/info` 的 `item.tv_title`）。

1. `/match` 取第一条，校验 `episodeTitle` 里的集号与目标集一致；不一致就丢弃。
2. 失败：用返回的 `animeId` 拉集列表，按 `episodeNumber` 找；没有 `animeId` 就 `/search/anime` 取第一个集数够的番剧。
3. 再失败：查本地匹配缓存（`剧名 → animeId|番剧名`）。手动选中过的结果必须写回缓存。
4. 全失败：提示「弹幕匹配失败」，提供手动搜索入口。
5. 加载后两个修正：**时间缩放**（弹幕最大时间按视频时长等比缩放，偏差超过 ±5% 时提示用户）与**整秒偏移**（用户可调）。

## 3. 渲染模型

单 `Canvas` 绘制，不建 View 池，不做 Bitmap 缓存。

```
滚动区域从画面顶部 12dp 安全边距开始，底边不超过画面高 × 区域比例
行高 = 字号 × 1.8
底部固定弹幕独立锚定画面底部，按字幕与控制栏可见状态向上避让
```

| 类型 | 放置规则 | 寿命 |
|---|---|---|
| 滚动 | 从顶部向下分配轨道，右侧进入、左侧离开；检查同轨追尾与反向碰撞 | 默认 7 秒完整穿越 |
| 顶部固定 | 从画面顶部安全边距向下排，水平居中 | 5 秒 |
| 底部固定 | 从实际画面底边的避让区上沿向上排，不锚定滚动区域底部 | 5 秒 |
| 逆向滚动 | 左侧进入、右侧离开；与普通滚动共用物理碰撞检查 | 默认 7 秒完整穿越 |

- 速度：基础穿越速度乘以用户速度倍率及播放倍速；大于 1 时更快。长短文字速度不同，需要预测追尾，不能只检查进入边缘的间隔。
- 无空行直接丢弃该条（不排队），避免弹幕堆积。
- 绘制：先 0.8dp 黑色描边再填充；暂停中的条目透明度 ×0.6。
- 绘制坐标使用文字布局盒左上角，测量与轨道统一使用画布像素。

## 4. 时钟

两个时钟必须分开：

- **触发时钟**：播放器位置（100ms 粒度即可），决定「什么时候发射」。
- **动画时钟**：`frameTimeNanos` 差值，决定「弹幕走到哪」。

规则：

- 位置跳变超过 1 秒或处于拖动中 → 清空同屏弹幕，二分重定位游标，**不补发**跳变前的弹幕。
- 迟到超过 1.5 秒的弹幕直接丢弃，避免拖动后一次性刷屏。
- 发射抖动：随机 0–300ms 错开，防止同一秒的弹幕完全同步。
- 倍速：动画移动随播放倍速变化，0.5x 与 2x 均保持媒体时间同步。
- `dt` 截断 0.1 秒，防止后台返回后大跳。

## 5. 性能

| 项 | 参考值 | 说明 |
|---|---|---|
| 同屏上限 | 40 条 | 超出不再发射 |
| 总量上限 | 50000 条 | 超出按比例随机抽样 |
| 密度 | 百分比抽稀 | 用户可调 |
| 帧循环 | `Choreographer`，低于 48Hz 降为定帧 | Aerial 用 `withFrameNanos` 即可 |
| 文本测量 | 每条只在出生时量一次，缓存宽度 | 不要每帧 `measureText` |

Aerial 的额外约束：

- 电视空闲不许有持续动画 → 播放器不在前台、或暂停且无活动弹幕时，停止帧循环。
- 每帧最多一次全屏绘制 → 弹幕层与字幕层合并在同一个 `Canvas` 里画，不要叠两层全屏 View。
- 960×540dp 画布：字号换算用 `LocalDensity` 转 px，不要用 sp（会跟随系统字体缩放）。

## 6. 数据结构（Kotlin）

```kotlin
data class Danmaku(val text: String, val atSec: Float, val colorArgb: Int, val type: Int)

data class Active(
    val text: String, val widthPx: Float, val rowY: Float,
    var x: Float, val speedPxPerSec: Float,
    val ttlSec: Float = 0f, var ttl: Float = ttlSec,
)

class LanePool(private val width: Float, private val height: Float, private val fontSizePx: Float) {
    private val rowHeight = fontSizePx * 1.8f
    private val maxRows = max(1, (height * 0.35f / rowHeight).toInt())
    val scroll = ArrayList<Active>()
    val fixed = ArrayList<Active>()   // 顶部与底部共用，靠 type 区分
}
```

- `scroll`/`fixed` 用普通 `ArrayList`，用一个 `Int` 状态驱动重绘，不要用 `mutableStateListOf`（每帧快照开销）。
- 回收：`scroll.removeAll { it.x + it.width < -100 }`、`fixed.removeAll { it.ttl <= 0 }`，每帧一次。

## 7. 设置项

| 项 | 默认 | 范围 |
|---|---|---|
| 开关 | 开 | — |
| 透明度 | 80% | 20–100% |
| 字号 | 22（画布单位） | 14–36 |
| 显示区域 | 35% | 20–60% |
| 同屏上限 | 40 | 10–100 |
| 速度 | 1.0 | 0.5–2.0 |
| 仅滚动 / 含顶底 | 含 | — |
| 时间偏移 | 0 秒 | −30…+30 |

## 8. 待验证

1. 弹幕服务是否需要在 NAS 上单独部署：参考实现只把主机名拼 `9321` 端口，**没有内置服务**；Aerial 要给出「未配置弹幕服务」的空状态，而不是报错。
2. `/match` 对 `tv_title` 的命中率：飞牛返回的是中文剧名，DanDanPlay 期望 `SxxExx` 后缀，需要在真机上跑几条热门剧确认。
3. 弹幕与字幕同一层绘制时的对齐：字幕贴底，弹幕底部区最多 3 行，需要预留高度避免重叠。

## 9. 哔哩哔哩模式对照与位置修正（2026-10-28）

- 一手依据：[bilibili/DanmakuFlameMaster 的模式常量](https://github.com/bilibili/DanmakuFlameMaster/blob/master/DanmakuFlameMaster/src/main/java/master/flame/danmaku/danmaku/model/BaseDanmaku.java)与[独立方向／上下固定轨道分配](https://github.com/bilibili/DanmakuFlameMaster/blob/master/DanmakuFlameMaster/src/main/java/master/flame/danmaku/danmaku/renderer/android/DanmakusRetainer.java)。参考公开语义，自行实现现有 Canvas 引擎，不引入库。
- 旧代码固定空出顶部 6 行，在 22dp 字号／1.8 倍行高下偏移约 238dp，导致所有弹幕落在中下部；移除整块偏移，滚动与顶部固定从顶部开始，底部固定单独向上排。
- 播放器弹幕菜单新增普通滚动、顶部固定、底部固定、逆向滚动的独立开关，以及显示区域（25%／50%／75%／100%）、字号、不透明度、速度。选择即时生效并保存；子设置返回弹幕菜单，不影响隐藏控制栏时的方向键快进。
- 验证：146 项单元测试通过，Standard／Guard release 构建通过并覆盖安装到投影仪；实际进入弹幕根菜单与显示区域子菜单，确认四类开关及25／50／75／100选项可达，退出回 Aerial 首页。极米播放截图仍遗漏叠加层，真实投影上的位置、字距和可读性待现场确认；测试短时播放推进了真实观看进度。
- 控制栏或字幕导致底部避让变化时，保留仍合法的顶部／滚动轨道，只重排底部固定弹幕或移除越界条目，避免打开菜单整屏弹幕被清空。

