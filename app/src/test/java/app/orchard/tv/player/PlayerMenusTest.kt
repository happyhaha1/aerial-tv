package app.orchard.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The control bar's shape: which buttons a film gets, how a long panel scrolls, how speeds read. */
class PlayerMenusTest {

    @Test
    fun onlyAShowWhoseEpisodesTheNasListedGetsTheEpisodeButton() {
        assertFalse(MenuKind.Episodes in playerBar(hasEpisodes = false))
        assertTrue(MenuKind.Episodes in playerBar(hasEpisodes = true))
        assertFalse(MenuKind.DanmakuArea in playerBar(hasEpisodes = true))
        assertFalse(MenuKind.DanmakuSpeed in playerBar(hasEpisodes = false))
    }

    @Test
    fun aLongPanelKeepsTheFocusedRowInView() {
        // A season longer than the panel: the window follows the focus and stops at both ends.
        assertEquals(0 until 7, panelWindow(40, 0))
        assertTrue(20 in panelWindow(40, 20))
        assertEquals(33 until 40, panelWindow(40, 39))
        // A short panel shows everything, and an empty one shows nothing.
        assertEquals(0 until 3, panelWindow(3, 2))
        assertTrue(panelWindow(0, 0).isEmpty())
    }

}