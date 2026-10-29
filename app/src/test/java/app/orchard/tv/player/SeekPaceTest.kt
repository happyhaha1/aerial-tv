package app.orchard.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** The arrow keys' burst ladder: ten seconds alone, then longer steps while the burst runs. */
class SeekPaceTest {

    private var clock = 0L
    private val pace = SeekPace { clock }

    @Test
    fun `a single press moves ten seconds either way`() {
        assertEquals(10_000L, pace.step(1))
        pace.reset()
        clock += 60_000L
        assertEquals(-10_000L, pace.step(-1))
    }

    @Test
    fun `presses inside the burst climb the ladder and stop at two minutes`() {
        assertEquals(10_000L, pace.step(1))
        clock += 200L
        assertEquals(30_000L, pace.step(1))
        clock += 200L
        assertEquals(60_000L, pace.step(1))
        clock += 200L
        assertEquals(120_000L, pace.step(1))
        clock += 200L
        assertEquals(120_000L, pace.step(1))
    }

    @Test
    fun `a pause ends the burst`() {
        pace.step(1)
        clock += 200L
        assertEquals(30_000L, pace.step(1))
        clock += SeekPace.BURST_MS + 1
        assertEquals(10_000L, pace.step(1))
    }

    @Test
    fun `backwards presses climb the same ladder`() {
        assertEquals(-10_000L, pace.step(-1))
        clock += 100L
        assertEquals(-30_000L, pace.step(-1))
    }

    @Test
    fun `reversing after an overshoot restarts with a small step`() {
        pace.step(1)
        clock += 100L
        pace.step(1)
        clock += 100L
        pace.step(1)
        clock += 100L
        assertEquals(-10_000L, pace.step(-1))
        clock += 100L
        assertEquals(-30_000L, pace.step(-1))
    }
}
