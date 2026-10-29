package app.orchard.tv.player

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.orchard.tv.R
import kotlin.math.roundToInt

/**
 * The bar's buttons and the panels behind them, left to right; each bar button opens one panel.
 * A [onBar] false kind is a panel of the danmaku settings, reached from a row of that panel and
 * never drawn as a button: the bar's own list is [playerBar].
 */
enum class MenuKind(@StringRes val label: Int, val onBar: Boolean = true) {
    Audio(R.string.player_audio),
    Subtitles(R.string.player_subtitles),
    Episodes(R.string.player_episodes),
    Speed(R.string.player_speed),
    Quality(R.string.player_quality),
    Aspect(R.string.player_aspect),
    Danmaku(R.string.player_danmaku),
    DanmakuArea(R.string.danmu_area, onBar = false),
    DanmakuSize(R.string.danmu_size, onBar = false),
    DanmakuOpacity(R.string.danmu_opacity, onBar = false),
    DanmakuSpeed(R.string.danmu_speed, onBar = false),
}

/**
 * The rows of the danmaku panel's first page, in order: the switch, the four toggles of what the
 * layer draws, then one row per value page.
 */
enum class DanmakuRow(@StringRes val label: Int) {
    On(R.string.player_on),
    Off(R.string.player_off),
    Scroll(R.string.danmu_scroll),
    Top(R.string.danmu_top),
    Bottom(R.string.danmu_bottom),
    Reverse(R.string.danmu_reverse),
    Area(R.string.danmu_area),
    Size(R.string.danmu_size),
    Opacity(R.string.danmu_opacity),
    Speed(R.string.danmu_speed),
    ;

    /** The value page this row opens, or null for the rows that change a setting in place. */
    val page: MenuKind?
        get() = when (this) {
            Area -> MenuKind.DanmakuArea
            Size -> MenuKind.DanmakuSize
            Opacity -> MenuKind.DanmakuOpacity
            Speed -> MenuKind.DanmakuSpeed
            else -> null
        }
}

/** The row of the danmaku panel that opens [kind], or null when [kind] is not one of its pages. */
fun danmakuRowOf(kind: MenuKind): DanmakuRow? = DanmakuRow.entries.firstOrNull { it.page == kind }

/** One row of an open panel. [current] marks what is playing now, [enabled] what can be chosen. */
@Immutable
data class MenuEntry(val label: String, val current: Boolean = false, val enabled: Boolean = true)

/** An open panel: the rows, and [note] — the server's own words when a choice is unavailable. */
@Immutable
data class PanelMenu(@StringRes val title: Int, val entries: List<MenuEntry>, val note: String? = null)

/** The playback speeds the menu offers. */
val SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** The danmaku panel's area choices: percent of the canvas height the comments may use. */
val DANMAKU_AREAS: List<Int> = listOf(25, 50, 75, 100)

/** The danmaku panel's text sizes, in canvas dp. */
val DANMAKU_SIZES: List<Int> = listOf(18, 22, 26, 30)

/** The danmaku panel's opacity choices, percent. */
val DANMAKU_OPACITIES: List<Int> = listOf(25, 50, 75, 100)

/** The fittings the aspect menu offers, in the order the menu lists them. */
val ASPECTS: List<AspectMode> = listOf(AspectMode.Fit, AspectMode.Fill, AspectMode.Wide16x9)

/** How many rows of an open panel show at once; a longer list scrolls under the focus. */
const val PANEL_ROWS = 7

/** The rows of a long panel that show around [focused], so the focus always stays in view. */
fun panelWindow(size: Int, focused: Int): IntRange {
    if (size <= PANEL_ROWS) return 0 until size
    val start = (focused.coerceIn(0, size - 1) - PANEL_ROWS / 2).coerceIn(0, size - PANEL_ROWS)
    return start until (start + PANEL_ROWS)
}

/**
 * The control bar's buttons for one film: the episode menu exists only for a show whose episode
 * list the NAS gave, so a film's bar has one button fewer; the danmaku settings pages are never
 * buttons, so they are left out here and reached from the danmaku panel instead.
 */
fun playerBar(hasEpisodes: Boolean): List<MenuKind> =
    MenuKind.entries.filter { it.onBar && (it != MenuKind.Episodes || hasEpisodes) }

/** The label of a speed row: "0.5", "0.75", "1", "1.25", "1.5", "2". */
fun speedLabel(speed: Float): String {
    val hundredths = (speed * 100).roundToInt()
    val whole = hundredths / 100
    val rest = hundredths % 100
    return when {
        rest == 0 -> "$whole"
        rest % 10 == 0 -> "$whole.${rest / 10}"
        rest < 10 -> "$whole.0$rest"
        else -> "$whole.$rest"
    }
}

/** The label of an aspect row. */
@StringRes
fun aspectLabel(mode: AspectMode): Int = when (mode) {
    AspectMode.Fit -> R.string.player_aspect_fit
    AspectMode.Fill -> R.string.player_aspect_fill
    AspectMode.Wide16x9 -> R.string.player_aspect_169
}