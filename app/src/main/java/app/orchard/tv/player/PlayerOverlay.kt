package app.orchard.tv.player

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.text.Cue
import app.orchard.tv.R
import app.orchard.tv.formatClock
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type

/** What the picture is doing, as the overlay draws it. */
enum class PlayerPhase { Loading, Playing, Buffering, Failed }

/** The video's rectangle on the canvas: the surface's own size, where the picture lives. */
@Immutable
data class VideoFrame(val width: Dp, val height: Dp)

/**
 * Everything the overlay draws, as values: the Activity owns the state, this file draws it. Marked
 * immutable so the bar and the panel are skipped when only the position they do not read changed.
 */
@Immutable
data class PlayerOverlayState(
    val phase: PlayerPhase,
    val title: String,
    val subtitleLine: String?,
    val positionMs: Long,
    val durationMs: Long,
    val notice: String?,
    val upNext: String?,
    val failure: String?,
    val canRetry: Boolean,
    /** A short line under the spinner: "Switching…", "Trying the software decoder". */
    val status: String?,
    val bar: List<MenuKind>,
    val barFocus: MenuKind,
    val panel: PanelMenu?,
    val menuIndex: Int,
    /** The plate the picture shows while the arrow keys are tapped: "▶ 30 s". Null when not seeking. */
    val seekHint: String? = null,
)

/** How many lines of subtitles show at once, as the picture's own captions do. */
private const val SUBTITLE_LINES = 3

/**
 * The film's subtitles, drawn by the player itself from the cues ExoPlayer hands over: text near the
 * bottom of the picture, white on a black shadow, and any bitmap cues (PGS and the like) above the
 * text. Both stay inside [frame] — the rectangle the picture is fitted to — so they sit under the
 * film and not under the letterbox.
 *
 * Nothing is composed and nothing is drawn while there are no cues.
 */
@Composable
fun SubtitleLayer(cues: List<Cue>, frame: VideoFrame, modifier: Modifier = Modifier) {
    val lines = remember(cues) { cueLines(cues) }
    val bitmaps = remember(cues) { cues.mapNotNull { it.bitmap } }
    if (lines.isEmpty() && bitmaps.isEmpty()) return
    Column(
        modifier = modifier
            .size(frame.width, frame.height)
            .padding(bottom = frame.height * 0.05f),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        bitmaps.forEach { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.heightIn(max = frame.height * 0.25f).widthIn(max = frame.width * 0.9f),
            )
        }
        lines.forEach { line ->
            BasicText(
                line,
                style = SubtitleStyle,
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            )
        }
    }
}

/**
 * The text of [cues] as lines: the last [SUBTITLE_LINES] of them, so a cue carrying a paragraph
 * shows its end rather than running off the picture.
 */
internal fun cueLines(cues: List<Cue>): List<String> = cues
    .asSequence()
    .mapNotNull { it.text?.toString() }
    .flatMap { it.split('\n').asSequence() }
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .toList()
    .takeLast(SUBTITLE_LINES)

/** Spinner, failure and the "up next" plate: what the film is doing, in the middle of the picture. */
@Composable
fun StatusLayer(state: PlayerOverlayState) {
    Box(Modifier.fillMaxSize()) {
        when (state.phase) {
            PlayerPhase.Loading -> if (state.upNext == null) BasicText(
                stringResource(R.string.fnos_loading), style = Type.Body.copy(color = Color.White),
                modifier = Modifier.align(Alignment.Center),
            )
            PlayerPhase.Buffering -> ProgressDot(Modifier.align(Alignment.Center))
            PlayerPhase.Failed -> Failure(state, Modifier.align(Alignment.Center))
            PlayerPhase.Playing -> Unit
        }
        state.status?.let {
            BasicText(
                it, style = Type.Callout.copy(color = Color.White),
                modifier = Modifier.align(Alignment.Center).padding(top = 72.dp),
            )
        }
        state.upNext?.let {
            Column(
                Modifier.align(Alignment.Center)
                    .background(Palette.Glass, RoundedCornerShape(16.dp))
                    .padding(horizontal = 30.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProgressDot()
                Spacer(Modifier.height(16.dp))
                BasicText(stringResource(R.string.player_up_next, it), style = Type.Title.copy(color = Color.White))
            }
        }
        // The seek plate sits outside the bar: tapping right must move the picture and say how far
        // without raising the button row, or the next tap would navigate instead of seeking again.
        state.seekHint?.let {
            BasicText(
                it,
                style = Type.Headline.copy(color = Color.White),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 48.dp, top = 34.dp)
                    .background(Palette.Glass, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * The bar itself: the panel of the open menu above it, then the row of buttons, then the film's
 * name, its episode and the position. Drawn only while the controls are up, which the player keeps
 * to the few seconds after a key and to as long as a menu is open.
 */
@Composable
fun ControlBar(state: PlayerOverlayState, onSeek: (Float) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxWidth()
                .height(if (state.panel != null) 440.dp else 220.dp)
                .align(Alignment.BottomCenter),
        ) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.4f to Color.Black.copy(alpha = 0.42f),
                    1f to Color.Black.copy(alpha = 0.86f),
                ),
            )
        }
        Column(
            Modifier.fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 48.dp)
                .padding(bottom = 34.dp),
        ) {
            state.panel?.let { panel ->
                Panel(panel, state.menuIndex)
                Spacer(Modifier.height(18.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                state.bar.forEach { kind ->
                    Pill(stringResource(kind.label), focused = kind == state.barFocus)
                    Spacer(Modifier.width(10.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        state.title, style = Type.Title.copy(color = Color.White),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
                    )
                    state.subtitleLine?.let {
                        BasicText(it, style = Type.Caption.copy(color = Color.White.copy(alpha = 0.75f)))
                    }
                }
                BasicText(
                    "${formatClock(state.positionMs)} / ${formatClock(state.durationMs)}",
                    style = Type.Callout.copy(color = Color.White.copy(alpha = 0.9f)),
                )
            }
            Spacer(Modifier.height(8.dp))
            Canvas(
                Modifier.fillMaxWidth().height(24.dp).pointerInput(onSeek, state.durationMs) {
                    detectTapGestures { offset ->
                        if (size.width > 0) onSeek((offset.x / size.width).coerceIn(0f, 1f))
                    }
                },
            ) {
                val y = size.height / 2
                val progress =
                    if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
                drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
                drawLine(Color.White, Offset(0f, y), Offset(size.width * progress, y), 3.dp.toPx(), StrokeCap.Round)
            }
        }
        state.notice?.let {
            BasicText(
                stringResource(R.string.fnos_resumed, it), style = Type.Headline.copy(color = Color.White),
                modifier = Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 34.dp),
            )
        }
    }
}

/** One button of the bar: a white plate under the focus, glass otherwise, as tvOS shows a control. */
@Composable
private fun Pill(label: String, focused: Boolean) {
    Box(
        Modifier
            .background(if (focused) Palette.Focus else Palette.FillStrong, RoundedCornerShape(11.dp))
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        BasicText(label, style = Type.Headline.copy(color = if (focused) Palette.OnFocus else Color.White))
    }
}

/** An open menu: one row per choice, with the server's own words under the title when it refused. */
@Composable
private fun Panel(menu: PanelMenu, focused: Int) {
    Column(
        Modifier
            .background(Palette.Glass, RoundedCornerShape(14.dp))
            .width(420.dp)
            .padding(vertical = 10.dp),
    ) {
        BasicText(
            stringResource(menu.title), style = Type.Caption.copy(color = Color.White.copy(alpha = 0.6f)),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        menu.note?.let {
            BasicText(
                it, style = Type.Caption.copy(color = Palette.Orange),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        panelWindow(menu.entries.size, focused).forEach { index ->
            val entry = menu.entries[index]
            Row(
                Modifier.fillMaxWidth()
                    .background(if (index == focused) Palette.Focus else Color.Transparent)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    entry.label, style = Type.Body.copy(color = entryColor(entry, index == focused)),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (entry.current) BasicText(
                    stringResource(R.string.player_current),
                    style = Type.Caption.copy(color = if (index == focused) Palette.OnFocus else Color.White),
                )
            }
        }
    }
}

private fun entryColor(entry: MenuEntry, focused: Boolean): Color = when {
    focused -> Palette.OnFocus
    !entry.enabled -> Palette.TertiaryLabel
    entry.current -> Color.White
    else -> Palette.SecondaryLabel
}

@Composable
private fun Failure(state: PlayerOverlayState, modifier: Modifier) {
    Column(
        modifier
            .widthIn(max = 620.dp)
            .background(Palette.Glass, RoundedCornerShape(16.dp))
            .padding(horizontal = 40.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            state.failure ?: stringResource(R.string.fnos_offline),
            style = Type.Title.copy(color = Color.White, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.canRetry) {
            Spacer(Modifier.height(22.dp))
            BasicText(
                stringResource(R.string.player_retry), style = Type.Headline.copy(color = Palette.OnFocus),
                modifier = Modifier
                    .background(Palette.Focus, RoundedCornerShape(11.dp))
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ProgressDot(modifier: Modifier = Modifier) {
    val spin = rememberInfiniteTransition("spinner")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = Motion.EaseInOut)), "spinner angle")
    Canvas(modifier.size(26.dp)) {
        drawArc(
            Color.White.copy(alpha = 0.85f), spin.value, 260f, false,
            Offset.Zero, Size(size.width, size.height), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** The subtitles' look: white, centred, with a shadow that holds over any picture. */
private val SubtitleStyle: TextStyle = Type.Title.copy(
    color = Color.White,
    textAlign = TextAlign.Center,
    shadow = Shadow(Color.Black, Offset(1.5f, 1.5f), 6f),
)