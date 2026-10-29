package app.orchard.tv.player

/**
 * The seek ladder of the player's arrow keys: a single press moves ten seconds, and presses that
 * follow one another inside [BURST_MS] climb to thirty, sixty and two minutes, so crossing a whole
 * film never needs forty presses. A pause ends the burst and the next press is ten seconds again.
 *
 * The fnOS TV player (com.trim.tv 1.4.2, `TrimPlayerView.x(int, boolean)`) keys its own ladder on
 * the gap *since the previous press* instead: gaps of 3-6 s gave 40/60/80 s and anything longer
 * 120 s, which a normal single press hits. That is the opposite of what a viewer expects, so this
 * class keeps the intent (bigger steps while seeking) and not the exact keying; see docs/FNOS-PLAYER.md.
 *
 * Pure arithmetic: the Activity asks for a step and nothing here touches a player or a clock.
 */
class SeekPace(private val now: () -> Long = System::currentTimeMillis) {

    /** When the last press happened; null before the first one, which is not the same as time 0. */
    private var lastAt: Long? = null
    private var run = 0
    private var previousDirection = 0

    /** The step for a press towards [direction] (+1 forward, -1 back), in milliseconds. */
    fun step(direction: Int): Long {
        val at = now()
        val previous = lastAt
        val gap = if (previous == null) Long.MAX_VALUE else at - previous
        run = if (gap in 0..BURST_MS && direction == previousDirection) {
            (run + 1).coerceAtMost(STEPS.lastIndex)
        } else 0
        lastAt = at
        previousDirection = direction
        return direction * STEPS[run]
    }

    /** Ends the burst, so the next press starts at ten seconds again. */
    fun reset() {
        lastAt = null
        run = 0
        previousDirection = 0
    }

    companion object {
        /** Presses closer together than this belong to one burst. */
        const val BURST_MS = 3_000L

        private val STEPS = longArrayOf(10_000L, 30_000L, 60_000L, 120_000L)
    }
}
