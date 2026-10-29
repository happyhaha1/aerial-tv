package app.orchard.tv.danmaku

import kotlin.math.abs
import kotlin.random.Random

/**
 * What the renderer measured for one text: the width its lane needs, and the object it will draw
 * the text with. Measured once, when the comment enters its lane — a frame never measures.
 */
class Measured<T>(val widthPx: Float, val paint: T)

/**
 * The lane grid of `docs/DANMAKU.md` §3: rows of 1.8 times the font size. The scrolling band
 * starts [topInsetPx] below the canvas top — the player's own title and notice live there — and
 * covers [areaPercent] of the full canvas height, clipped above the control bar and the subtitles
 * ([bottomSkipPx]). Fixed top comments start at [laneTopPx] as well; fixed bottom comments are
 * anchored to [contentBottomPx], the real bottom of the content, not to the scrolling band.
 *
 * Every length is in the player's canvas pixels; [dpScale] is the canvas density, which the inset
 * needs so a 960×540 canvas on a 4K panel keeps the same breathing room.
 */
class DanmuGeometry(
    val widthPx: Float,
    val heightPx: Float,
    val fontSizePx: Float,
    val areaPercent: Int = 35,
    val bottomSkipPx: Float = 0f,
    val speedMul: Float = 1f,
    val gapMinPx: Float = 20f,
    val gapMaxPx: Float = 60f,
    val dpScale: Float = 1f,
    val topInsetPx: Float = 12f * dpScale,
    val allowScroll: Boolean = true,
    val allowTop: Boolean = true,
    val allowBottom: Boolean = true,
    val allowReverse: Boolean = true,
) {
    val rowHeightPx = (fontSizePx * 1.8f).coerceAtLeast(1f)

    /** The bottom of what comments may use: the control bar and the subtitles own the rest. */
    val contentBottomPx = (heightPx - bottomSkipPx).coerceAtLeast(0f)

    /** The first row's top, just below the player's own overlay. */
    val laneTopPx = topInsetPx.coerceAtLeast(0f)

    /** Where the scrolling band ends: [areaPercent] of the full height, clipped at the bottom. */
    val laneBottomPx = (laneTopPx + heightPx * areaPercent.coerceIn(MIN_AREA_PERCENT, 100) / 100f)
        .coerceAtMost(contentBottomPx)

    val rowCount = ((laneBottomPx - laneTopPx) / rowHeightPx).toInt().coerceAtLeast(0)

    /** The top of scrolling lane [row], which is where its comment's text box starts. */
    fun rowTop(row: Int): Float = laneTopPx + row * rowHeightPx

    /**
     * Whether [other] is this grid with only the bottom inset moved: same canvas, font, area,
     * speed, gaps, top inset and enabled types. Such a change can leave the live comments in
     * place, unlike a resize or a font change, which invalidates every lane.
     */
    internal fun sameLanesAs(other: DanmuGeometry): Boolean =
        widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            fontSizePx == other.fontSizePx &&
            areaPercent == other.areaPercent &&
            speedMul == other.speedMul &&
            gapMinPx == other.gapMinPx &&
            gapMaxPx == other.gapMaxPx &&
            dpScale == other.dpScale &&
            topInsetPx == other.topInsetPx &&
            allowScroll == other.allowScroll &&
            allowTop == other.allowTop &&
            allowBottom == other.allowBottom &&
            allowReverse == other.allowReverse

    companion object {
        /** Below this the band would be a sliver no row fits in. */
        private const val MIN_AREA_PERCENT = 10
    }
}

/**
 * One comment in flight: its lane, its place in it, and how it leaves. Fixed comments
 * ([Danmaku.TOP], [Danmaku.BOTTOM]) hold their lane for [ttlSec] and move horizontally only to
 * centre themselves; scrolling comments — [Danmaku.SCROLL] right to left, [Danmaku.REVERSE] left
 * to right — are recycled once their tail has left the canvas.
 */
class Active<T>(
    val text: String,
    val mode: Int,
    val colorArgb: Int,
    val widthPx: Float,
    /** The lane it occupies; a fixed comment may be re-laned when the bottom inset moves. */
    var row: Int,
    var topPx: Float,
    /** Pixels per second of real time, playback rate already included. */
    var speedPxPerSec: Float,
    var xPx: Float,
    var ttlSec: Float,
    /** The renderer's own measurement object, drawn as it is every frame. */
    val paint: T,
)

/**
 * The danmaku timeline: which comment's moment has come, which lane it takes, and how far it has
 * travelled. Everything here is plain arithmetic on the canvas' own pixels, so the whole model
 * runs in a test without a screen (`docs/DANMAKU.md` §4–§6).
 *
 * Two clocks drive it, as the doc requires. The **trigger clock** is the player's position, which
 * decides what to fire: [sync] takes the player's samples and only treats a jump of more than
 * [SEEK_JUMP_SEC] as a seek, so a sample that is stale (the player's own position updates pause
 * while its controls are hidden) cannot stall the layer. The **animation clock** is [advance]'s
 * frame time, which only runs while the video plays.
 */
class DanmuEngine<T>(
    private val measure: (String) -> Measured<T>,
    private val random: Random = Random.Default,
) {
    /** One comment and the moment it is due, which its own second plus its jitter. */
    private class Scheduled(val comment: Danmaku, val dueSec: Float)

    var geometry: DanmuGeometry? = null
        private set

    /** The comments in flight, in draw order: the renderer walks this every frame. */
    private val live = ArrayList<Active<T>>()
    val items: List<Active<T>> get() = live

    private var schedule: List<Scheduled> = emptyList()
    private var cursor = 0
    private var positionSec = 0f
    private var rate = 1f

    /**
     * The grid changed. A real change — canvas size, font, display area, speed, gaps, top inset
     * or enabled types — invalidates every live comment's lane or speed, so the lanes start
     * empty. A move of the bottom inset alone (the player's menu or subtitles appearing) keeps
     * the lanes: only comments that no longer fit are removed, and bottom comments are
     * re-anchored to the new content bottom where they can be placed safely.
     */
    fun configure(geometry: DanmuGeometry) {
        val previous = this.geometry
        if (previous === geometry) return
        this.geometry = geometry
        if (previous == null || !previous.sameLanesAs(geometry)) {
            live.clear()
        } else {
            adaptToBottomInset()
        }
    }

    /**
     * Keeps what still fits when only the bottom inset moved. Scrolling and top comments are
     * valid while their box stays inside the scrolling band; a bottom comment is re-anchored to
     * the new content bottom, and one that cannot be placed without overlapping a comment of
     * another type (or the other two bottom rows) is dropped on its own.
     */
    private fun adaptToBottomInset() {
        val grid = geometry ?: return
        live.removeAll { item -> item.mode != Danmaku.BOTTOM && !fitsBand(grid, item.topPx) }
        val bottoms = ArrayList<Active<T>>()
        for (index in live.indices) {
            if (live[index].mode == Danmaku.BOTTOM) bottoms += live[index]
        }
        if (bottoms.isEmpty()) return
        bottoms.sortBy { it.row }
        live.removeAll { item -> item.mode == Danmaku.BOTTOM }
        for (item in bottoms) {
            val row = bottomRow(grid)
            if (row < 0) continue
            item.row = row
            item.topPx = grid.contentBottomPx - (row + 1) * grid.rowHeightPx
            live += item
        }
    }

    /** Whether a scrolling or top comment's box still lies inside the scrolling band. */
    private fun fitsBand(grid: DanmuGeometry, topPx: Float): Boolean =
        topPx >= grid.laneTopPx - EPSILON && topPx + grid.rowHeightPx <= grid.laneBottomPx + EPSILON

    /**
     * The comments of the episode just fetched. The clock is not moved and the cursor starts at
     * the beginning, so a fetch that lands mid-episode replays only the [LATE_SEC] that are still
     * worth showing; everything earlier is dropped by [advance] as it walks past.
     */
    fun load(comments: List<Danmaku>) {
        schedule = comments.map { Scheduled(it, it.atSec + random.nextFloat() * JITTER_SEC) }.sortedBy { it.dueSec }
        cursor = 0
        live.clear()
    }

    /**
     * A sample of the player's position. A jump of more than [SEEK_JUMP_SEC] is a seek — the screen
     * is cleared, the cursor re-found, and nothing before the new position is replayed. A smaller
     * difference is the engine's own clock drifting: the pair of frames the television dropped
     * while it kept playing, which a long film would otherwise turn into seconds of lag. That one
     * is walked towards, never snapped, so no comment jumps.
     */
    fun sync(positionSec: Float) {
        val drift = positionSec - this.positionSec
        if (abs(drift) > SEEK_JUMP_SEC) {
            seek(positionSec)
        } else if (abs(drift) > NUDGE_SEC) {
            this.positionSec += drift * NUDGE_SHARE
        }
    }

    /**
     * One frame while the video plays. [dtSec] is real time since the last frame, clamped so a
     * return from the background cannot sweep the screen. The media clock advances by the playback
     * rate, and so do the comments: their stored speed already carries the rate, which keeps a
     * comment's screen lifetime equal to its seven seconds of *video* time at every playback speed.
     */
    fun advance(dtSec: Float, playing: Boolean, playbackSpeed: Float) {
        val nextRate = playbackSpeed.coerceIn(MIN_RATE, MAX_RATE)
        if (nextRate != rate) {
            // A rate change applies to the comments already in flight, not only to the next one.
            val factor = nextRate / rate
            for (index in live.indices) {
                val item = live[index]
                if (isScrolling(item.mode)) item.speedPxPerSec *= factor
            }
            rate = nextRate
        }
        if (!playing) return
        val dt = dtSec.coerceIn(0f, MAX_FRAME_SEC)
        positionSec += dt * rate
        emit()
        move(dt)
    }

    /**
     * Places one comment in the first lane that can take it, or drops it. The lane grid, the
     * enabled comment types and [MAX_LIVE] are the screen's own limits (`docs/DANMAKU.md` §3, §5):
     * a row shared by a fixed comment and a scrolling one, or by two comments travelling in
     * opposite directions, is never taken, and a scrolling follower that would catch its
     * predecessor before that predecessor leaves is given the next row instead. Modes outside the
     * four the parser canonicalizes are dropped rather than guessed at.
     *
     * Returns the comment in flight, or null when it was dropped.
     */
    fun spawn(comment: Danmaku): Active<T>? {
        val grid = geometry ?: return null
        val mode = comment.mode
        if (!isSupported(mode) || !allowed(grid, mode)) return null
        if (live.size >= MAX_LIVE) return null
        val text = comment.text
        if (text.isEmpty()) return null
        val measured = measure(text)
        val width = measured.widthPx
        if (width <= 0f) return null
        val scrolling = isScrolling(mode)
        val pxPerSec = if (scrolling) speed(grid, width) else 0f

        val row: Int
        val topPx: Float
        if (scrolling) {
            row = scrollRow(grid, width, pxPerSec, mode)
            if (row < 0) return null
            topPx = grid.rowTop(row)
        } else if (mode == Danmaku.TOP) {
            row = topRow(grid)
            if (row < 0) return null
            topPx = grid.rowTop(row)
        } else {
            row = bottomRow(grid)
            if (row < 0) return null
            topPx = grid.contentBottomPx - (row + 1) * grid.rowHeightPx
        }

        val item = Active(
            text = text,
            mode = mode,
            colorArgb = comment.colorArgb,
            widthPx = width,
            row = row,
            topPx = topPx,
            speedPxPerSec = pxPerSec,
            xPx = when (mode) {
                Danmaku.SCROLL -> grid.widthPx
                Danmaku.REVERSE -> -width
                else -> (grid.widthPx - width) / 2f
            },
            ttlSec = if (scrolling) 0f else FIXED_TTL_SEC,
            paint = measured.paint,
        )
        live += item
        return item
    }

    private fun seek(positionSec: Float) {
        this.positionSec = positionSec
        live.clear()
        cursor = firstDueAfter(positionSec)
    }

    private fun emit() {
        while (cursor < schedule.size) {
            val next = schedule[cursor]
            if (next.dueSec > positionSec) return
            cursor++
            if (positionSec - next.comment.atSec > LATE_SEC) continue
            spawn(next.comment)
        }
    }

    private fun move(dtSec: Float) {
        for (index in live.indices) {
            val item = live[index]
            when (item.mode) {
                Danmaku.SCROLL -> item.xPx -= item.speedPxPerSec * dtSec
                Danmaku.REVERSE -> item.xPx += item.speedPxPerSec * dtSec
                // A fixed comment's five seconds are video seconds, like its moment.
                else -> item.ttlSec -= dtSec * rate
            }
        }
        val width = geometry?.widthPx ?: 0f
        live.removeAll { item ->
            when (item.mode) {
                Danmaku.SCROLL -> item.xPx + item.widthPx <= 0f
                Danmaku.REVERSE -> item.xPx >= width
                else -> item.ttlSec <= 0f
            }
        }
    }

    /**
     * A scrolling lane can take a comment once its predecessor's tail has cleared the entry edge
     * by a random gap *and* the new comment cannot catch that predecessor before it leaves. Rows
     * are checked by their physical vertical interval, so a top or bottom comment in the same
     * band makes the row unavailable, and a comment going the other way is never put on it.
     */
    private fun scrollRow(grid: DanmuGeometry, width: Float, speed: Float, mode: Int): Int {
        val gap = randomGap(grid)
        for (row in 0 until grid.rowCount) {
            if (canEnterRow(grid, grid.rowTop(row), speed, mode, gap)) return row
        }
        return -1
    }

    private fun canEnterRow(grid: DanmuGeometry, topPx: Float, speed: Float, mode: Int, gap: Float): Boolean {
        for (index in live.indices) {
            val item = live[index]
            if (!overlaps(topPx, item.topPx, grid.rowHeightPx)) continue
            // A fixed comment, or a scrolling one going the other way, owns the whole row.
            if (item.mode != mode) return false
            if (!canFollow(grid, item, speed, gap)) return false
        }
        return true
    }

    /**
     * Whether a comment entering at the right (or left, for [Danmaku.REVERSE]) stays behind [lead]
     * until [lead] has left the canvas. A wider text is faster — every comment crosses in
     * [CROSS_SEC] — so width alone must not decide the lane: the gap at entry and the two speeds
     * decide whether the follower catches the leader before it is gone.
     */
    private fun canFollow(grid: DanmuGeometry, lead: Active<T>, speed: Float, gap: Float): Boolean {
        if (lead.speedPxPerSec <= 0f) return false
        val free: Float
        val exitSec: Float
        if (lead.mode == Danmaku.REVERSE) {
            // The leader runs to the right; its trailing edge is its left edge.
            free = lead.xPx
            exitSec = (grid.widthPx - lead.xPx) / lead.speedPxPerSec
        } else {
            // The leader runs to the left; its trailing edge is its right edge.
            val tail = lead.xPx + lead.widthPx
            free = grid.widthPx - tail
            exitSec = tail.coerceAtLeast(0f) / lead.speedPxPerSec
        }
        if (free < gap) return false
        if (speed <= lead.speedPxPerSec) return true
        val catchSec = free / (speed - lead.speedPxPerSec)
        return catchSec >= exitSec
    }

    /** A top comment takes the first free row from the inset downwards. */
    private fun topRow(grid: DanmuGeometry): Int {
        for (row in 0 until grid.rowCount) {
            if (rowClear(grid, grid.rowTop(row))) return row
        }
        return -1
    }

    /**
     * A bottom comment takes the first free row from the real content bottom upwards, so it never
     * overlaps the control bar or the subtitles and never sits inside the scrolling band by
     * accident. [BOTTOM_ROWS] bounds how much of the picture it may cover.
     */
    private fun bottomRow(grid: DanmuGeometry): Int {
        for (row in 0 until BOTTOM_ROWS) {
            val topPx = grid.contentBottomPx - (row + 1) * grid.rowHeightPx
            if (topPx < grid.laneTopPx) break
            if (rowClear(grid, topPx)) return row
        }
        return -1
    }

    /** A fixed comment may not share its physical interval with any comment already in flight. */
    private fun rowClear(grid: DanmuGeometry, topPx: Float): Boolean {
        for (index in live.indices) {
            if (overlaps(topPx, live[index].topPx, grid.rowHeightPx)) return false
        }
        return true
    }

    /** Whether two text boxes of [heightPx] share any vertical pixels. */
    private fun overlaps(topPx: Float, otherTopPx: Float, heightPx: Float): Boolean =
        topPx < otherTopPx + heightPx - EPSILON && otherTopPx < topPx + heightPx - EPSILON

    private fun randomGap(grid: DanmuGeometry): Float {
        val min = grid.gapMinPx.coerceAtLeast(0f)
        val max = grid.gapMaxPx.coerceAtLeast(min)
        return min + random.nextFloat() * (max - min)
    }

    /** The first scheduled comment after [positionSec]; what a seek re-finds the cursor with. */
    private fun firstDueAfter(positionSec: Float): Int {
        var low = 0
        var high = schedule.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (schedule[mid].dueSec <= positionSec) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     * Every comment crosses the screen in [CROSS_SEC] of video time at 1x, so a long one is never
     * a blur. The user's speed multiplier and the playback rate both scale that real-time speed:
     * at 2x the comment covers the same seven video seconds in 3.5 real seconds.
     */
    private fun speed(grid: DanmuGeometry, widthPx: Float): Float =
        (grid.widthPx + widthPx) / CROSS_SEC * grid.speedMul.coerceAtLeast(MIN_SPEED_MUL) * rate

    private fun isScrolling(mode: Int): Boolean = mode == Danmaku.SCROLL || mode == Danmaku.REVERSE

    /** The modes the parser produces; anything else is not guessed at. */
    private fun isSupported(mode: Int): Boolean =
        mode == Danmaku.SCROLL || mode == Danmaku.TOP || mode == Danmaku.BOTTOM || mode == Danmaku.REVERSE

    private fun allowed(grid: DanmuGeometry, mode: Int): Boolean = when (mode) {
        Danmaku.SCROLL -> grid.allowScroll
        Danmaku.TOP -> grid.allowTop
        Danmaku.BOTTOM -> grid.allowBottom
        Danmaku.REVERSE -> grid.allowReverse
        else -> false
    }

    companion object {
        /** Comments on screen at once; past this a comment is dropped rather than delayed. */
        const val MAX_LIVE = 40

        /** Seconds of real time one frame may carry. */
        const val MAX_FRAME_SEC = 0.1f

        /** A position this far from the clock is a seek, not drift. */
        const val SEEK_JUMP_SEC = 1f

        /** A comment found this late is dropped: a fetch or a seek must not replay the screen. */
        const val LATE_SEC = 1.5f

        /** Comments are fired up to this much off their own second, so one second does not burst. */
        const val JITTER_SEC = 0.3f

        /** How long a fixed comment holds its lane, in video seconds. */
        const val FIXED_TTL_SEC = 5f

        /** The doc's three bottom lanes. */
        const val BOTTOM_ROWS = 3

        /** A drift smaller than this is a sample's own jitter, and is left alone. */
        private const val NUDGE_SEC = 0.15f

        /** The share of a drift each sample walks towards the player: no comment jumps. */
        private const val NUDGE_SHARE = 0.25f

        /** Seconds of video a comment takes to cross at 1x, its own length included. */
        private const val CROSS_SEC = 7f
        private const val MIN_RATE = 0.1f
        private const val MAX_RATE = 8f
        private const val MIN_SPEED_MUL = 0.05f

        /** Row boundaries may touch; they may not share pixels. */
        private const val EPSILON = 0.01f
    }
}
