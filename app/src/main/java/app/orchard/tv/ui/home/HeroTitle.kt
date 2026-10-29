package app.orchard.tv.ui.home

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.fnos.FnosArt
import app.orchard.tv.fnos.FnosArtwork
import app.orchard.tv.fnos.FnosPlayItem
import app.orchard.tv.fnos.rememberCover
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.ensureActive

/**
 * Home's Top Shelf, as tvOS shows a featured title: the artwork fills the screen, and on it sit
 * the title, the episode, how far in, one Resume button in the left column the artwork's shading
 * leaves dark, and a small row of page dots centred over the dock. Shown by HomeScreen for the
 * moment the FN app itself carries focus, or the Resume button does.
 *
 * [chrome] is Home's header row — the time, the weather and the status pill — over the top of the
 * artwork, short of the title, which starts a row below [titleTop]. The focus search has nothing
 * above the Resume button to reach, so the vertical steps there are Home's own, through [onPill] and
 * [onDock]: its header stays on screen with the pill one press up, the dock's tile one press down,
 * and Back the same way as Down. Left and right step to another title.
 *
 * The picture is still: it fades in when the server's artwork arrives and changes only when another
 * title is stepped to, so an idle Home draws one image a frame and runs no animation.
 */
@Composable
fun HeroTitle(
    item: FnosPlayItem?,
    count: Int,
    index: Int,
    /** The show's wide backdrop and its transparent logo, from the same resolved TV or movie. */
    art: HeroArt,
    /** The shelf has nothing to resume: this stands where the title and its progress do. */
    notice: String?,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    onStep: (Int) -> Unit,
    onPlay: () -> Unit,
    margin: Dp,
    titleTop: Dp,
    dotsY: Dp,
    /** Up reaches the status header; Down or Back return to the app dock, down from here. */
    onPill: () -> Unit,
    onDock: () -> Unit,
    /** Draws the header's row over the artwork ([chrome]): live TV, from the FN app's title up. */
    withChrome: Boolean = false,
    /** Home's header row, the time and weather and the pill, laid out with the margin already in [margin]. */
    chrome: (@Composable () -> Unit)? = null,
) {
    val reduced = LocalReducedMotion.current
    // The header's shading and its row stay out of the artwork, except for the pill: it is the one
    // control here, and the only one Home's Up leads to.
    val framed = withChrome && chrome != null
    val hasTitle = notice == null
    // The artwork comes in over the wallpaper rather than popping onto it, and the next title's
    // is crossfaded in behind the text rather than cut.
    Crossfade(targetState = art.backdrop, animationSpec = tween(420, easing = Motion.EaseOut), label = "hero-cover") { image ->
        if (image != null) {
            Image(
                image,
                contentDescription = null,
                modifier = if (framed) Modifier.drawWithContent { drawContent(); drawShade() } else Modifier,
                contentScale = ContentScale.Crop,
            )
        }
    }
    // The pill's shading lies under the pill, the rest of it over the artwork: the pill does not
    // move the picture, and shading over it would darken its own glass. The picture caches them
    // for itself as it is drawn; with no header there is none to cache.
    if (framed) Canvas(Modifier.fillMaxSize()) { drawChromeShade() }
    HeroShade()

    Column(Modifier.padding(start = margin).padding(top = titleTop).width(HeroColumn)) {
        // Titles start at the same line whatever their length: two lines of room, filled from the top.
        // A logo sits in that room, fitted and never stretched; without one the text title stands.
        Box(Modifier.height(HeroTitleBox), contentAlignment = Alignment.TopStart) {
            val logo = art.logo
            if (hasTitle && logo != null) {
                Image(
                    logo,
                    contentDescription = art.title.ifBlank { headline(item) },
                    modifier = Modifier.fillMaxHeight().width(HeroLogoWidth),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                )
            } else {
                BasicText(
                    text = if (hasTitle) headline(item) else notice.orEmpty(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = if (hasTitle) {
                        Type.LargeTitle.copy(
                            fontSize = 28.sp,
                            lineHeight = 35.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            shadow = HeroShadow,
                        )
                    } else {
                        // Everything watched, or the NAS is away: said quietly, not as a headline.
                        Type.Body.copy(fontSize = 15.sp, color = Color.White.copy(alpha = 0.78f), shadow = HeroShadow)
                    },
                )
            }
        }
        // The episode on its own line: on a show the title above is the series, and this is the
        // part the shelf's cards used to say under it.
        if (item != null && item.season > 0 && item.episode > 0) {
            BasicText(
                stringResource(R.string.fnos_episode, item.season, item.episode),
                maxLines = 1,
                style = Type.Headline.copy(fontSize = 15.sp, color = Color.White.copy(alpha = 0.78f), shadow = HeroShadow),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(Modifier.padding(top = 12.dp).height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            // How far in, when the server knows how long the title runs; the row keeps its height
            // either way, so the button never moves.
            if (item != null && item.durationSec > 0) {
                val progress = (item.tsSec.toFloat() / item.durationSec).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .width(HeroBar)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.28f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.coerceAtLeast(0.02f))
                            .clip(RoundedCornerShape(50))
                            .background(Color.White),
                    )
                }
                // "12 min left", "45 sec left" under a minute: "0 min left" reads as a bug.
                val leftSec = (item.durationSec - item.tsSec).coerceAtLeast(0)
                val left = if (leftSec < 60) {
                    stringResource(R.string.fnos_seconds_left, leftSec)
                } else {
                    stringResource(R.string.fnos_minutes_left, leftSec / 60)
                }
                BasicText(
                    left,
                    style = Type.Body.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f), shadow = HeroShadow),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Pressable(
            onClick = onPlay,
            enabled = enabled,
            focusRequester = focusRequester,
            focusedScale = 1.04f,
            onKey = { e ->
                if (e.type != KeyEventType.KeyDown) return@Pressable false
                when (e.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> { onStep(-1); true }
                    NativeKeyEvent.KEYCODE_DPAD_DOWN,
                    NativeKeyEvent.KEYCODE_BACK -> { onDock(); true }
                    // Up goes to the header's pill, the one control on the picture and the way to
                    // Control Center. Left to the focus search, that went to the first title of the
                    // row, behind the artwork.
                    NativeKeyEvent.KEYCODE_DPAD_UP -> { onPill(); true }
                    else -> false
                }
            },
        ) { state ->
            LaunchedEffect(state.focused) { onFocused(state.focused) }
            val bg by animateColorAsState(
                if (state.focused) Palette.Focus else Color.White.copy(alpha = 0.2f),
                tween(if (reduced) 0 else 160, easing = Motion.EaseOut),
                label = "hero-btn",
            )
            val fg = if (state.focused) Palette.OnFocus else Color.White
            Row(
                Modifier
                    .clip(RoundedCornerShape(HeroButtonCorner))
                    .background(bg)
                    .height(HeroButtonHeight)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Canvas(Modifier.size(11.dp)) {
                    val p = Path().apply {
                        moveTo(size.width * 0.12f, 0f)
                        lineTo(size.width, size.height / 2f)
                        lineTo(size.width * 0.12f, size.height)
                        close()
                    }
                    drawPath(p, fg)
                }
                BasicText(
                    stringResource(R.string.fnos_continue_play),
                    style = Type.Headline.copy(color = fg, fontSize = 16.sp, letterSpacing = 0.06.sp),
                )
            }
        }
    }
    // The header over the picture's top row, where Home keeps it: only while this is live TV. Home
    // lays the row's right end out itself, level with the tiles' edge.
    if (framed) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = margin),
            contentAlignment = Alignment.TopEnd,
        ) {
            chrome()
        }
    }

    if (count > 1) {
        HeroPager(
            count = count,
            index = index,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = dotsY),
        )
    }
}

/** The series' name, as the shelf's cards said it. */
@Composable
private fun headline(item: FnosPlayItem?): String {
    if (item == null) return ""
    return item.tvTitle.ifBlank { item.title }.ifBlank { stringResource(R.string.fnos_unknown_title) }
}

/**
 * The darkening over the full-screen artwork, as tvOS lays it under its titles: the left third
 * behind the text, the bottom behind the dock. Two gradient passes, once per frame; the artwork
 * itself is never tinted or blurred, so it stays as the studio drew it.
 */
@Composable
fun HeroShade() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = 0.78f),
                0.30f to Color.Black.copy(alpha = 0.42f),
                0.58f to Color.Transparent,
            ),
        )
        drawRect(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.34f),
                0.16f to Color.Transparent,
                0.52f to Color.Transparent,
                0.74f to Color.Black.copy(alpha = 0.42f),
                1f to Color.Black.copy(alpha = 0.72f),
            ),
        )
    }
}

/**
 * The darkening the header needs, in two parts: [under] over the picture under the time and
 * weather, which lie on the artwork itself, and [over] beside the pill, never over it.
 */
private class HeroChromeShade(val under: Brush, val over: Brush)

/** The header is read from the very top of the screen: shading under it begins at its own top. */
private fun DrawScope.chromeShades(size: Size): HeroChromeShade {
    val topBand = Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.34f),
        (ChromeShadeHeight.toPx() / size.height).coerceAtMost(1f) to Color.Transparent,
    )
    return HeroChromeShade(
        under = topBand,
        over = Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.2f),
            (ChromeShadeRightEnd.toPx() / size.width).coerceAtMost(1f) to Color.Transparent,
        ),
    )
}

/** The header's shading, made as the picture is first drawn and read on every redraw. */
private object HeroScene {
    var shades: HeroChromeShade? = null
}

/** The header's shading under the picture's own: the top band the time and weather stand on. */
private fun DrawScope.drawChromeShade() {
    val shades = HeroScene.shades ?: chromeShades(size).also { HeroScene.shades = it }
    drawRect(shades.under)
}

/** The side shading alone, over the pill's part of the picture, as the picture is drawn. */
private fun DrawScope.drawShade() {
    val shades = HeroScene.shades ?: chromeShades(size).also { HeroScene.shades = it }
    drawRect(shades.over)
}

/** How far down the picture the header's shading reaches: level with the time and weather. */
private val ChromeShadeHeight = 96.dp

/** How far in from the right the pill's side shading fades: the pill's left edge, by its side. */
private val ChromeShadeRightEnd = 130.dp

/** Which title is up: small dots centred over the dock, as tvOS places its page dots. */
@Composable
fun HeroPager(count: Int, index: Int, modifier: Modifier = Modifier) {
    // Eight at most: more read as a strip of noise at TV distance.
    val shown = count.coerceAtMost(8)
    val at = index.coerceIn(0, (shown - 1).coerceAtLeast(0))
    // Apple TV's dots are small and close-set, the current one a short white pill.
    Row(modifier, horizontalArrangement = Arrangement.Center) {
        repeat(shown) { i ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = if (i == at) 0.95f else 0.35f))
                    .size(if (i == at) 14.dp else 5.dp, 5.dp),
            )
        }
    }
}

/** The hero's show pictures: the wide backdrop, and the transparent title logo when there is one. */
data class HeroArt(val backdrop: ImageBitmap?, val logo: ImageBitmap?, val title: String = "")

/**
 * The show art for [item], at the size the screen needs: the series' or movie's backdrop and its
 * logo, never an episode's poster. The metadata lookup is async and keyed by server, account and
 * item, so a step to another title cannot publish the last one's pictures; the pictures use the
 * shelf's own disk cache.
 */
@Composable
fun heroArt(item: FnosPlayItem?, host: String, token: String?, user: String, width: Int): HeroArt {
    val guid = item?.guid.orEmpty()
    var show by remember(host, user, guid) { mutableStateOf<FnosArtwork?>(null) }
    LaunchedEffect(host, user, guid, item?.season, item?.episode, item?.parentGuid, token) {
        show = null
        if (item == null || host.isBlank() || guid.isBlank()) return@LaunchedEffect
        val resolved = FnosArt.showArt(host, token, user, item)
        // The title may have changed while the NAS answered: only the live title publishes.
        ensureActive()
        show = resolved
    }
    val art = show
    val backdrop = rememberCover(host, art?.backdrop.orEmpty(), width, token)
    val logo = rememberCover(host, art?.logo.orEmpty(), LogoWidth, token)
    return HeroArt(backdrop, logo, art?.title.orEmpty())
}

private val HeroShadow = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 18f)

/** Room for two lines of title, so the button below does not move between titles. */
private val HeroTitleBox = 78.dp

/** The logo's widest drawn size: a wordmark, not a banner across the title column. */
private val HeroLogoWidth = 220.dp

/** The progress row under the title, kept even when the server does not know the length. */
private val HeroProgressRow = 22.dp

/** Apple TV keeps its featured title in the left column that the artwork's shading leaves dark. */
private val HeroColumn = 400.dp
private val HeroBar = 150.dp

/** The Resume button: Apple TV's 42-44 dp pill, with its 16 sp label and 8 dp corners. */
private val HeroButtonHeight = 42.dp
private val HeroButtonCorner = 8.dp

/** The header's own place, level with the small clock on Home, as its row holds it. */
private val HeroPillTop = 28.dp

/** The width the hero artwork is asked of the server: 1080p, once per title. */
const val HeroWidth = 1920

/** The logo is asked at the server's own 800 px width: it fits the title box without upscaling. */
private const val LogoWidth = 800
