package app.orchard.tv.player

import app.orchard.tv.fnos.FnosPlayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which episode autoplay follows: the next in the show's list, never past its end. */
class EpisodesTest {

    private fun episode(guid: String, season: Int, number: Int, title: String = "Episode $number") =
        FnosPlayItem(guid, "Show", title, season, number, "", 0, 2_400, "", "season")

    @Test
    fun theNextEpisodeFollowsTheGuidThatIsPlaying() {
        val list = listOf(episode("a", 1, 1), episode("b", 1, 2), episode("c", 1, 3))
        assertEquals("b", Episodes.next(list, episode("a", 1, 1))?.guid)
        assertEquals("c", Episodes.next(list, episode("b", 1, 2))?.guid)
        assertNull(Episodes.next(list, episode("c", 1, 3)))
    }

    @Test
    fun theNumberFindsTheEpisodeWhenTheGuidIsNotInTheList() {
        // A re-imported library hands the player a guid the new list no longer has; the number still
        // points at the episode, and the list's own order carries on across the season break.
        val list = listOf(episode("a", 1, 1), episode("b", 1, 2), episode("c", 2, 1))
        assertEquals("b", Episodes.next(list, episode("stale", 1, 1))?.guid)
        assertEquals("c", Episodes.next(list, episode("b", 1, 2))?.guid)
    }

    @Test
    fun aFilmOrAnEmptyListHasNoNextEpisode() {
        assertNull(Episodes.next(emptyList(), episode("a", 1, 1)))
        val film = FnosPlayItem("f", "Film", "Film", 0, 0, "", 0, 7_200, "", "")
        assertNull(Episodes.next(listOf(episode("a", 1, 1)), film))
        // An episode that is not in the list at all ends playback rather than guessing a neighbour.
        assertNull(Episodes.next(listOf(episode("a", 1, 1)), episode("x", 4, 9)))
    }
}