package app.orchard.tv.danmaku

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The gap the lanes leave under the player's title at the top of the canvas. */
private const val TOP_INSET_DP = 12

/** The outline the doc draws behind each comment. */
private const val STROKE_DP = 0.8f

/** The gap a comment leaves behind the one before it in a lane, random between these. */
private const val GAP_MIN_DP = 20
private const val GAP_MAX_DP = 60

/** A paused comment is dimmed rather than frozen out of sight (`docs/DANMAKU.md` §3). */
private const val PAUSED_ALPHA = 0.6f

private const val NOTICE_HOLD_MS = 2500L
private const val NOTICE_FADE_MS = 700

/** What the layer knows about this episode's comments. */
private enum class DanmuStatus { Loading, Ready, None, Unreachable }

/**
 * The danmaku layer: a transparent Canvas over the video, fed by the player's position. It fetches
 * and renders on its own — the player only says where it is, whether it is playing and how fast —
 * and it does nothing at all while [DanmuPrefs.enabled] is off.
 *
 * Nothing here blocks the caller: the fetch runs on IO, and a frame only draws what the engine
 * already placed. The layer is the player's only source of frames of its own, and it stops them
 * whenever the video does.
 */
@Composable
fun DanmakuOverlay(
    title: String,
    episode: Int,
    positionMs: Long,
    playing: Boolean,
    playbackSpeed: Float,
    /** What the player keeps free at the bottom: its control bar, the subtitles, or neither. */
    bottomInsetDp: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // The player can be reached without the settings screen having run (a deep link): init here
    // too, since it is idempotent, so the saved switch still reaches this screen.
    LaunchedEffect(Unit) { DanmuPrefs.init(context) }
    if (!DanmuPrefs.enabled) return

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val sizeDp = DanmuPrefs.size
    val area = DanmuPrefs.area
    val speedMul = DanmuPrefs.speedMul
    val alpha = DanmuPrefs.alpha
    val scroll = DanmuPrefs.scrollEnabled
    val top = DanmuPrefs.topEnabled
    val bottom = DanmuPrefs.bottomEnabled
    val reverse = DanmuPrefs.reverseEnabled
    val painter = remember(measurer, sizeDp, density) { DanmuPainter(measurer, sizeDp, density) }
    // The engine outlives a change of font size: its painter is swapped under it, not rebuilt.
    val currentPainter = rememberUpdatedState(painter)
    val engine = remember {
        DanmuEngine<TextLayoutResult>({ text -> currentPainter.value.measure(text) })
    }
    var drewComments by remember(title, episode) { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val geometry = remember(
        canvasSize, sizeDp, area, speedMul, density, bottomInsetDp, scroll, top, bottom, reverse,
    ) {
        with(density) {
            DanmuGeometry(
                widthPx = canvasSize.width.toFloat(),
                heightPx = canvasSize.height.toFloat(),
                fontSizePx = sizeDp.dp.toPx(),
                areaPercent = area,
                bottomSkipPx = bottomInsetDp.toPx(),
                speedMul = speedMul,
                gapMinPx = GAP_MIN_DP.dp.toPx(),
                gapMaxPx = GAP_MAX_DP.dp.toPx(),
                dpScale = density.density,
                topInsetPx = TOP_INSET_DP.dp.toPx(),
                allowScroll = scroll,
                allowTop = top,
                allowBottom = bottom,
                allowReverse = reverse,
            )
        }
    }
    LaunchedEffect(geometry) {
        engine.configure(geometry)
        // A new grid empties the lanes, so the proof of active lanes is earned again.
        drewComments = false
    }

    val server = DanmuPrefs.serverUrl()
    var status by remember(title, episode) { mutableStateOf(DanmuStatus.Loading) }
    LaunchedEffect(title, episode, server) {
        if (server.isEmpty()) {
            status = DanmuStatus.Unreachable
            return@LaunchedEffect
        }
        status = DanmuStatus.Loading
        val fetched = withContext(Dispatchers.IO) { DanmuClient.fetch(server, title, episode, context.cacheDir) }
        when (fetched) {
            is DanmuFetch.Ok -> {
                app.orchard.tv.system.DiagLog.i("danmaku loaded ${fetched.comments.size} comments")
                engine.load(fetched.comments)
                status = if (fetched.comments.isEmpty()) DanmuStatus.None else DanmuStatus.Ready
            }
            DanmuFetch.NoMatch -> status = DanmuStatus.None
            DanmuFetch.Unreachable -> status = DanmuStatus.Unreachable
        }
    }

    // The trigger clock: the player's samples, of which only a real jump moves the cursor.
    SideEffect { engine.sync(positionMs / 1000f) }

    val tick = remember { mutableIntStateOf(0) }
    // A layer that draws nothing — paused, no comments, or every mode switched off — keeps no
    // frame loop: the canvas is static until one of those changes.
    LaunchedEffect(engine, playing, playbackSpeed, geometry, status, scroll, top, bottom, reverse) {
        if (!playing || status == DanmuStatus.None || status == DanmuStatus.Unreachable ||
            (!scroll && !top && !bottom && !reverse) ||
            geometry.widthPx <= 0f || geometry.heightPx <= 0f
        ) {
            return@LaunchedEffect
        }
        var lastNanos = System.nanoTime()
        while (true) {
            withFrameNanos { frameNanos ->
                val dt = (frameNanos - lastNanos) / 1_000_000_000f
                lastNanos = frameNanos
                engine.advance(dt, playing = true, playbackSpeed = playbackSpeed)
                if (!drewComments && engine.items.isNotEmpty()) {
                    drewComments = true
                    app.orchard.tv.system.DiagLog.i("danmaku active lanes=${engine.items.size}")
                }
                tick.intValue++
            }
        }
    }

    Box(modifier = modifier) {
        Canvas(Modifier.fillMaxSize().clipToBounds().onSizeChanged { canvasSize = it }) {
            // Read so that every frame the engine moved redraws this canvas.
            tick.intValue
            val items = engine.items
            if (items.isEmpty()) return@Canvas
            val dim = if (playing) 1f else PAUSED_ALPHA
            val lit = alpha * dim
            val stroke = Stroke(width = STROKE_DP.dp.toPx())
            for (item in items) {
                val at = Offset(item.xPx, item.topPx)
                drawText(
                    textLayoutResult = item.paint, color = Color.Black.copy(alpha = lit),
                    topLeft = at, drawStyle = stroke,
                )
                drawText(
                    textLayoutResult = item.paint, color = Color(item.colorArgb).copy(alpha = lit),
                    topLeft = at,
                )
            }
        }
        val notice = when (status) {
            DanmuStatus.Unreachable -> R.string.danmu_offline
            DanmuStatus.None -> R.string.danmu_none
            else -> 0
        }
        val noticeAlpha = remember { Animatable(0f) }
        LaunchedEffect(notice) {
            if (notice == 0) {
                noticeAlpha.snapTo(0f)
                return@LaunchedEffect
            }
            noticeAlpha.snapTo(1f)
            delay(NOTICE_HOLD_MS)
            noticeAlpha.animateTo(0f, tween(NOTICE_FADE_MS))
        }
        if (notice != 0) {
            BasicText(
                text = stringResource(notice),
                style = Type.Headline.copy(color = Color.White.copy(alpha = 0.85f)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 34.dp, end = 48.dp)
                    .graphicsLayer { this.alpha = noticeAlpha.value }
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Measures comment text, once per place it enters a lane. Sizes come from the canvas density as
 * dp, not sp: the TV's font scale must not change the lanes, and the size the painter measures
 * with is exactly the size the engine lays out with.
 */
private class DanmuPainter(private val measurer: TextMeasurer, sizeDp: Int, density: Density) {
    private val style = TextStyle(fontSize = with(density) { sizeDp.dp.toSp() }, color = Color.White)

    /** One comment's layout, and the width the lanes need for it. */
    fun measure(text: String): Measured<TextLayoutResult> {
        val layout = this.measurer.measure(text = text, style = style, softWrap = false, maxLines = 1)
        return Measured(layout.size.width.toFloat(), layout)
    }
}