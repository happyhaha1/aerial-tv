package app.orchard.tv.danmaku

import kotlin.math.ceil
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lane model of `docs/DANMAKU.md` §3 without a screen: the canvas is plain numbers, and the
 * texts are measured at 10px a character so every width in a test is known.
 */
class DanmuEngineTest {
    private fun scroll(text: String, at: Float = 0f) = Danmaku(text, at, 0xFFFFFFFF.toInt(), Danmaku.SCROLL)

    private fun fixed(text: String, mode: Int, at: Float = 0f) = Danmaku(text, at, 0xFFFFFFFF.toInt(), mode)

    /**
     * A grid of exactly [rows] lanes. A 10px font makes an 18px lane; the band starts at the
     * default inset and is rounded up so the row count lands on [rows].
     */
    private fun grid(
        rows: Int = 4,
        width: Float = 600f,
        height: Float = 400f,
        speedMul: Float = 1f,
    ): DanmuGeometry {
        val area = ceil(rows * 18f * 100f / height).toInt()
        return DanmuGeometry(
            widthPx = width,
            heightPx = height,
            fontSizePx = 10f,
            areaPercent = area,
            bottomSkipPx = 0f,
            speedMul = speedMul,
            dpScale = 1f,
        )
    }

    private fun engine(rows: Int = 4, width: Float = 600f, height: Float = 400f): DanmuEngine<String> =
        engineOn(grid(rows, width, height))

    private fun engineOn(geometry: DanmuGeometry): DanmuEngine<String> =
        DanmuEngine<String>({ Measured(it.length * 10f, it) }, Random(7)).apply { configure(geometry) }

    @Test
    fun theLanesStartJustBelowTheTitleAndEndAboveTheControlBar() {
        val grid = DanmuGeometry(widthPx = 960f, heightPx = 540f, fontSizePx = 22f, bottomSkipPx = 96f)
        assertEquals(39.6f, grid.rowHeightPx, 0.01f)
        assertEquals(12f, grid.laneTopPx, 0.01f) // one inset row, not six lane rows
        assertEquals(201f, grid.laneBottomPx, 0.01f) // 12px inset + 35% of 540
        assertTrue(grid.laneBottomPx <= grid.contentBottomPx)
        assertEquals(4, grid.rowCount)
        assertEquals(12f, grid.rowTop(0), 0.01f)
    }

    @Test
    fun theDefaultCanvasPutsTheFirstCommentNearTheTop() {
        val engine = engineOn(DanmuGeometry(widthPx = 960f, heightPx = 540f, fontSizePx = 22f, bottomSkipPx = 96f))
        val first = engine.spawn(scroll("first"))!!
        val second = engine.spawn(scroll("second"))!!
        assertEquals(12f, first.topPx, 0.01f)
        assertEquals(51.6f, second.topPx, 0.01f)
    }

    @Test
    fun topCommentsStartAtTheInsetAndBottomCommentsAtTheContentBottom() {
        val grid = DanmuGeometry(widthPx = 960f, heightPx = 540f, fontSizePx = 22f, bottomSkipPx = 96f)
        val engine = engineOn(grid)
        val top = engine.spawn(fixed("top", Danmaku.TOP))!!
        val bottom = engine.spawn(fixed("bottom", Danmaku.BOTTOM))!!
        assertEquals(12f, top.topPx, 0.01f)
        // The bottom comment is anchored to the real content bottom, not to the scrolling band.
        assertEquals(540f - 96f - 39.6f, bottom.topPx, 0.01f)
        assertTrue(bottom.topPx > grid.laneBottomPx)
        assertTrue(bottom.topPx + grid.rowHeightPx <= grid.contentBottomPx + 0.01f)
    }

    @Test
    fun theInsetScalesWithTheCanvasDensity() {
        val grid = DanmuGeometry(widthPx = 600f, heightPx = 400f, fontSizePx = 10f, dpScale = 2f)
        assertEquals(24f, grid.laneTopPx, 0.01f)
        assertEquals(24f, grid.rowTop(0), 0.01f)
    }

    @Test
    fun theDisplayAreaCoversTheRequestedShareAndStopsAtTheControlBar() {
        val expected = listOf(25 to 262f, 50 to 512f, 75 to 762f, 100 to 1000f)
        for ((area, bottom) in expected) {
            val grid = DanmuGeometry(widthPx = 1000f, heightPx = 1000f, fontSizePx = 10f, areaPercent = area)
            assertEquals(bottom, grid.laneBottomPx, 0.01f)
            assertTrue(grid.rowTop(grid.rowCount - 1) + grid.rowHeightPx <= grid.laneBottomPx + 0.01f)
            assertTrue(grid.laneBottomPx <= grid.contentBottomPx)
        }
        val clipped = DanmuGeometry(
            widthPx = 1000f,
            heightPx = 1000f,
            fontSizePx = 10f,
            areaPercent = 100,
            bottomSkipPx = 300f,
        )
        assertEquals(700f, clipped.laneBottomPx, 0.01f)
    }

    @Test
    fun twoCommentsFiredAtOnceTakeDifferentLanes() {
        val engine = engine()
        val first = engine.spawn(scroll("first"))!!
        val second = engine.spawn(scroll("second"))!!
        assertEquals(0, first.row)
        assertEquals(1, second.row)
    }

    @Test
    fun aLaneIsReusedOnlyOnceItsCommentHasClearedTheRightEdge() {
        val engine = engine()
        val first = engine.spawn(scroll("first"))!!
        // The lane's comment is still entering, so the next one takes the lane below instead.
        assertEquals(1, engine.spawn(scroll("second"))!!.row)
        // Ten seconds later both have left the canvas and the first lane takes a comment again.
        repeat(100) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) }
        assertTrue(engine.items.isEmpty())
        assertFalse(engine.items.contains(first))
        assertEquals(0, engine.spawn(scroll("third"))!!.row)
    }

    @Test
    fun everyLaneBusyDropsTheComment() {
        val engine = engine(rows = 3)
        repeat(3) { assertNotNull(engine.spawn(scroll("comment $it"))) }
        assertNull(engine.spawn(scroll("one too many")))
        assertEquals(3, engine.items.size)
    }

    @Test
    fun fixedCommentsFillFromTheBottomUpAndKeepToThreeLanes() {
        val engine = engine(rows = 4)
        val bottom = (0 until 3).map { engine.spawn(fixed("b$it", Danmaku.BOTTOM))!! }
        assertEquals(listOf(0, 1, 2), bottom.map { it.row })
        assertEquals(listOf(382f, 364f, 346f), bottom.map { it.topPx })
        assertNull(engine.spawn(fixed("fourth", Danmaku.BOTTOM)))
        val top = engine.spawn(fixed("top", Danmaku.TOP))!!
        assertEquals(0, top.row)
        assertEquals(12f, top.topPx, 0.01f)
    }

    @Test
    fun aFixedCommentFreesItsLaneAfterFiveSeconds() {
        val engine = engine(rows = 4)
        repeat(3) { engine.spawn(fixed("b$it", Danmaku.BOTTOM)) }
        assertNull(engine.spawn(fixed("blocked", Danmaku.BOTTOM)))
        repeat(60) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) }
        assertTrue(engine.items.isEmpty())
        assertNotNull(engine.spawn(fixed("again", Danmaku.BOTTOM)))
    }

    @Test
    fun aFixedCommentLivesInVideoSecondsWhenTheVideoIsSpedUp() {
        val engine = engine(rows = 4)
        engine.spawn(fixed("bottom", Danmaku.BOTTOM))
        // Three real seconds at 2x are six video seconds, so the five-second comment is gone.
        repeat(30) { engine.advance(0.1f, playing = true, playbackSpeed = 2f) }
        assertTrue(engine.items.isEmpty())
    }

    @Test
    fun theScreenCapDropsCommentsBeyondIt() {
        val engine = engine(rows = 41, width = 4000f, height = 1000f)
        repeat(DanmuEngine.MAX_LIVE) { assertNotNull(engine.spawn(scroll("c$it"))) }
        assertNull(engine.spawn(scroll("one too many")))
    }

    @Test
    fun aSeekClearsTheScreenAndDoesNotReplayIt() {
        val engine = engine()
        engine.load(listOf(scroll("early", 1f), scroll("later", 60f)))
        repeat(30) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) } // 3 s in
        assertEquals(listOf("early"), engine.items.map { it.text })
        engine.sync(120f)
        assertTrue(engine.items.isEmpty())
        repeat(5) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) }
        assertTrue(engine.items.isEmpty())
    }

    @Test
    fun commentsWhoseMomentHasPassedAreDroppedNotStacked() {
        val engine = engine()
        repeat(30) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) } // the clock is 3 s in
        engine.load(listOf(scroll("old", 0f), scroll("fresh", 2.9f)))
        repeat(5) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) }
        // "old" is more than 1.5 s late here; "fresh" is only just, and is drawn.
        assertEquals(listOf("fresh"), engine.items.map { it.text })
    }

    @Test
    fun theClockAndTheCommentsOnlyMoveWhileTheVideoPlays() {
        val engine = engine()
        val first = engine.spawn(scroll("first"))!!
        val start = first.xPx
        engine.advance(1f, playing = false, playbackSpeed = 1f)
        assertEquals(start, first.xPx, 0.001f)
        engine.advance(0.1f, playing = true, playbackSpeed = 1f)
        assertTrue(first.xPx < start)
        val perFrame = start - first.xPx
        // A frame longer than a tenth of a second is clamped: a return from the background cannot
        // sweep the lane.
        engine.advance(30f, playing = true, playbackSpeed = 1f)
        assertEquals(first.xPx, start - perFrame * 2f, 0.01f)
    }

    @Test
    fun aSmallDriftIsWalkedTowardsWithoutClearingTheScreen() {
        val engine = engine()
        engine.load(listOf(scroll("now", 0f)))
        repeat(4) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) } // clock 0.4 s
        assertEquals(1, engine.items.size)
        // The television dropped a frame: under a second behind the player is drift, not a seek.
        engine.sync(0.7f)
        assertEquals(1, engine.items.size)
        // A jump of more than a second is the user's seek, and it clears the screen.
        engine.sync(9f)
        assertTrue(engine.items.isEmpty())
    }

    @Test
    fun aCommentCrossesInSevenSecondsAtOneX() {
        val engine = engine()
        val item = engine.spawn(scroll("first"))!!
        val expected = (600f + 50f) / 7f
        assertEquals(expected, item.speedPxPerSec, 0.01f)
        repeat(10) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) }
        assertEquals(600f - expected, item.xPx, 0.01f)
        repeat(61) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) }
        assertTrue(engine.items.isEmpty())
    }

    @Test
    fun aSpeedMultiplierShortensTheCrossingTime() {
        val engine = engineOn(grid(speedMul = 2f))
        val item = engine.spawn(scroll("first"))!!
        val expected = (600f + 50f) / 7f
        assertEquals(expected * 2f, item.speedPxPerSec, 0.01f)
        repeat(36) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) }
        assertTrue(engine.items.isEmpty())
    }

    @Test
    fun theClockRunsAtThePlaybackRate() {
        val engine = engine()
        engine.load(listOf(scroll("at-one-and-a-half", 1.5f)))
        repeat(10) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) }
        assertTrue(engine.items.isEmpty()) // the clock is only 1 s in
        repeat(5) { engine.advance(0.1f, playing = true, playbackSpeed = 2f) }
        assertEquals(listOf("at-one-and-a-half"), engine.items.map { it.text }) // the clock is 2 s in
    }

    @Test
    fun aRateChangeAppliesToCommentsAlreadyInFlight() {
        val engine = engine()
        val item = engine.spawn(scroll("first"))!!
        val expected = (600f + 50f) / 7f
        repeat(10) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) }
        assertEquals(600f - expected, item.xPx, 0.01f)
        repeat(10) { engine.advance(0.1f, playing = true, playbackSpeed = 2f) }
        assertEquals(expected * 2f, item.speedPxPerSec, 0.01f)
        assertEquals(600f - expected * 3f, item.xPx, 0.01f)
    }

    @Test
    fun aTopCommentAndAScrollCommentNeverShareARow() {
        val engine = engine()
        val scrolling = engine.spawn(scroll("first"))!!
        val top = engine.spawn(fixed("top", Danmaku.TOP))!!
        assertEquals(0, scrolling.row)
        assertEquals(1, top.row)
        assertEquals(30f, top.topPx, 0.01f)
        assertNotNull(engine.spawn(fixed("top2", Danmaku.TOP)))
        assertNotNull(engine.spawn(fixed("top3", Danmaku.TOP)))
        assertNull(engine.spawn(fixed("top4", Danmaku.TOP)))
        // The first row is scrolling, the other three hold fixed comments.
        assertNull(engine.spawn(scroll("blocked")))
    }

    @Test
    fun aBottomCommentNeverOverlapsAScrollCommentAtTheBottom() {
        val grid = DanmuGeometry(
            widthPx = 600f,
            heightPx = 84f,
            fontSizePx = 10f,
            areaPercent = 100,
            bottomSkipPx = 0f,
        )
        val engine = engineOn(grid)
        repeat(4) { assertNotNull(engine.spawn(scroll("c$it"))) } // the four rows reach the bottom
        assertNull(engine.spawn(fixed("bottom", Danmaku.BOTTOM)))
        repeat(80) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) }
        assertTrue(engine.items.isEmpty())
        val bottom = engine.spawn(fixed("bottom", Danmaku.BOTTOM))!!
        assertEquals(66f, bottom.topPx, 0.01f)
    }

    @Test
    fun aWideFollowerDoesNotCatchANarrowPredecessor() {
        val engine = engine()
        val lead = engine.spawn(scroll("a"))!!
        assertEquals(0, lead.row)
        // One second later the lane is clear by more than the gap, but a 100-character comment
        // is much faster than the 1-character one and would catch it before it leaves.
        repeat(10) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) }
        val follower = engine.spawn(scroll("x".repeat(100)))!!
        assertEquals(1, follower.row)
    }

    @Test
    fun aFollowerOfTheSameWidthTakesTheLaneItsPredecessorCleared() {
        val engine = engine()
        engine.spawn(scroll("a"))
        repeat(10) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) }
        val follower = engine.spawn(scroll("b"))!!
        assertEquals(0, follower.row)
    }

    @Test
    fun reverseCommentsMoveRightAndExit() {
        val engine = engine()
        val item = engine.spawn(fixed("rev", Danmaku.REVERSE))!!
        assertEquals(-30f, item.xPx, 0.01f)
        val start = item.xPx
        repeat(10) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) }
        assertTrue(item.xPx > start)
        repeat(100) { engine.advance(0.1f, playing = true, playbackSpeed = 1f) }
        assertTrue(engine.items.isEmpty())
    }

    @Test
    fun oppositeDirectionsNeverShareARow() {
        val engine = engine()
        val forward = engine.spawn(scroll("first"))!!
        val reverse = engine.spawn(fixed("rev", Danmaku.REVERSE))!!
        assertEquals(0, forward.row)
        assertEquals(1, reverse.row)
        assertTrue(reverse.topPx > forward.topPx)
    }

    @Test
    fun typeFiltersAreAppliedAtSpawn() {
        val grid = DanmuGeometry(
            widthPx = 600f,
            heightPx = 400f,
            fontSizePx = 10f,
            areaPercent = 18,
            allowScroll = false,
            allowTop = false,
            allowBottom = false,
            allowReverse = true,
        )
        val engine = engineOn(grid)
        assertNull(engine.spawn(scroll("s")))
        assertNull(engine.spawn(fixed("t", Danmaku.TOP)))
        assertNull(engine.spawn(fixed("b", Danmaku.BOTTOM)))
        assertNotNull(engine.spawn(fixed("r", Danmaku.REVERSE)))
    }

    @Test
    fun modesOutsideTheCanonicalFourAreDropped() {
        val engine = engine()
        for (mode in listOf(0, 2, 3, 7, 8, 9)) {
            assertNull(engine.spawn(Danmaku("unsupported $mode", 0f, 0xFFFFFFFF.toInt(), mode)))
        }
        assertNotNull(engine.spawn(scroll("ok")))
    }

    @Test
    fun aBottomInsetChangeKeepsTopAndScrollCommentsAndMovesTheBottom() {
        val engine = engineOn(
            DanmuGeometry(widthPx = 960f, heightPx = 540f, fontSizePx = 22f, bottomSkipPx = 96f),
        )
        val scrolling = engine.spawn(scroll("first"))!!
        val top = engine.spawn(fixed("top", Danmaku.TOP))!!
        val bottom = engine.spawn(fixed("bottom", Danmaku.BOTTOM))!!
        assertEquals(404.4f, bottom.topPx, 0.01f)

        // The player's menu appears: the bottom inset grows, the scrolling band is untouched.
        engine.configure(
            DanmuGeometry(widthPx = 960f, heightPx = 540f, fontSizePx = 22f, bottomSkipPx = 180f),
        )
        assertTrue(engine.items.contains(scrolling))
        assertTrue(engine.items.contains(top))
        assertTrue(engine.items.contains(bottom))
        assertEquals(12f, scrolling.topPx, 0.01f)
        assertEquals(320.4f, bottom.topPx, 0.01f) // anchored above the new content bottom
        assertTrue(bottom.topPx + 39.6f <= 540f - 180f + 0.01f)

        // The menu hides: everything still fits, and the bottom returns to the content bottom.
        engine.configure(
            DanmuGeometry(widthPx = 960f, heightPx = 540f, fontSizePx = 22f, bottomSkipPx = 96f),
        )
        assertTrue(engine.items.contains(scrolling))
        assertTrue(engine.items.contains(top))
        assertTrue(engine.items.contains(bottom))
        assertEquals(404.4f, bottom.topPx, 0.01f)
    }

    @Test
    fun aBottomInsetThatShrinksTheBandDropsOnlyTheRowsOutsideIt() {
        val engine = engineOn(
            DanmuGeometry(widthPx = 960f, heightPx = 540f, fontSizePx = 22f, bottomSkipPx = 96f),
        )
        val comments = (0 until 4).map { engine.spawn(scroll("c$it"))!! }
        assertEquals(listOf(0, 1, 2, 3), comments.map { it.row })

        engine.configure(
            DanmuGeometry(widthPx = 960f, heightPx = 540f, fontSizePx = 22f, bottomSkipPx = 400f),
        )
        // The band now ends at 140px: the fourth row does not fit, the first three do.
        assertEquals(listOf("c0", "c1", "c2"), engine.items.map { it.text })
        assertEquals(listOf(0, 1, 2), engine.items.map { it.row })
    }

    @Test
    fun aBottomReanchorThatCannotFitIsDroppedOnItsOwn() {
        val engine = engineOn(
            DanmuGeometry(widthPx = 600f, heightPx = 200f, fontSizePx = 10f, areaPercent = 100),
        )
        val bottom = engine.spawn(fixed("bottom", Danmaku.BOTTOM))!!
        assertEquals(182f, bottom.topPx, 0.01f)
        val tops = listOf("t0", "t1", "t2").map { engine.spawn(fixed(it, Danmaku.TOP))!! }
        assertEquals(listOf(12f, 30f, 48f), tops.map { it.topPx })

        engine.configure(
            DanmuGeometry(
                widthPx = 600f,
                heightPx = 200f,
                fontSizePx = 10f,
                areaPercent = 100,
                bottomSkipPx = 120f,
            ),
        )
        // No bottom row is free any more, so only the bottom comment is dropped.
        assertEquals(listOf("t0", "t1", "t2"), engine.items.map { it.text })
        assertFalse(engine.items.contains(bottom))
    }

    @Test
    fun anUnrelatedGridChangeStillClearsTheLanes() {
        val engine = engineOn(
            DanmuGeometry(widthPx = 960f, heightPx = 540f, fontSizePx = 22f, bottomSkipPx = 96f),
        )
        engine.spawn(scroll("first"))
        engine.spawn(fixed("top", Danmaku.TOP))
        engine.configure(
            DanmuGeometry(widthPx = 960f, heightPx = 540f, fontSizePx = 22f, areaPercent = 50, bottomSkipPx = 96f),
        )
        assertTrue(engine.items.isEmpty())
    }
}
