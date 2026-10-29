package app.orchard.tv.fnos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FnosArtworkTest {
    private fun detail(
        guid: String,
        type: String,
        parent: String = "",
        backdrop: String = "",
        logo: String = "",
        title: String = guid,
    ) = FnosDetail(guid, type, title, parent, backdrop, logo)

    private fun episode(guid: String, number: Int, parent: String = "season") = FnosPlayItem(
        guid = guid,
        tvTitle = "Show",
        title = "Episode $number",
        season = 1,
        episode = number,
        poster = "/episode-$number.jpg",
        tsSec = 60,
        durationSec = 3000,
        mediaGuid = "",
        parentGuid = parent,
    )

    @Test
    fun showBackdropAndLogoWinOverSeasonAndEpisode() {
        val chain = listOf(
            detail("episode", TYPE_EPISODE, "season", backdrop = "/episode-still.jpg", logo = "/episode-logo.png"),
            detail("season", "Season", "show", backdrop = "/season.jpg", logo = "/season-logo.png"),
            detail("show", "TV", backdrop = "/show.jpg", logo = "/show-logo.png", title = "Show"),
        )
        val art = chooseArtwork(chain)
        assertEquals("/show.jpg", art?.backdrop)
        assertEquals("/show-logo.png", art?.logo)
        assertEquals("Show", art?.title)
    }

    @Test
    fun episodeStillIsNeverTheHeroBackdrop() {
        val onlyEpisode = listOf(detail("episode", TYPE_EPISODE, "season", backdrop = "/episode-still.jpg"))
        assertNull(chooseArtwork(onlyEpisode))

        val seasonWithoutArt = listOf(
            detail("episode", TYPE_EPISODE, "season", backdrop = "/episode-still.jpg"),
            detail("season", "Season", "show"),
        )
        assertNull(chooseArtwork(seasonWithoutArt))
    }

    @Test
    fun seasonBackdropStandsWhenTheShowHasNone() {
        val chain = listOf(
            detail("season", "Season", "show", backdrop = "/season.jpg", logo = "/season-logo.png"),
            detail("show", "TV", title = "Show"),
        )
        val art = chooseArtwork(chain)
        assertEquals("/season.jpg", art?.backdrop)
        assertEquals("/season-logo.png", art?.logo)
    }

    @Test
    fun movieKeepsItsBackdropAndHasNoLogo() {
        val chain = listOf(detail("movie", "Movie", backdrop = "/movie.jpg", title = "Movie"))
        val art = chooseArtwork(chain)
        assertEquals("/movie.jpg", art?.backdrop)
        assertEquals("", art?.logo)
    }

    @Test
    fun noArtAtAllLeavesTheWallpaperAndTextTitle() {
        assertNull(chooseArtwork(emptyList()))
        assertNull(chooseArtwork(listOf(detail("show", "TV"))))
    }

    @Test
    fun backdropAndLogoAlwaysComeFromOneRow() {
        // The season has the only backdrop; the show has a logo but no picture to put it on.
        val chain = listOf(
            detail("season", "Season", "show", backdrop = "/season.jpg", logo = "/season-logo.png"),
            detail("show", "TV", logo = "/show-logo.png", title = "Show"),
        )
        val art = chooseArtwork(chain)
        assertEquals("/season.jpg", art?.backdrop)
        assertEquals("/season-logo.png", art?.logo)
    }

    @Test
    fun episodesOfOneSeasonResolveOneShowLookup() {
        val first = episode("episode-1", 1)
        val second = episode("episode-2", 2)
        assertEquals("season", artStart(first))
        assertEquals(artStart(first), artStart(second))
    }

    @Test
    fun movieResolvesItsOwnRow() {
        val movie = FnosPlayItem("movie", "", "Movie", 0, 0, "/poster.jpg", 0, 0, "", "library")
        assertEquals("movie", artStart(movie))
    }

    @Test
    fun artIsKeyedByServerAndAccount() {
        val index = FnosArtIndex()
        index.putShow("nas", "alice", "show", FnosArtwork("/show.jpg", "/logo.png", "Show"))
        index.putDetail("nas", "alice", "show", detail("show", "TV", backdrop = "/show.jpg"))

        assertEquals("/show.jpg", index.show("nas", "alice", "show")?.backdrop)
        assertEquals("/show.jpg", index.detail("nas", "alice", "show")?.backdrops)
        assertFalse(index.remembersShow("nas", "bob", "show"))
        assertNull(index.show("nas", "bob", "show"))
        assertNull(index.detail("nas", "bob", "show"))
        assertNotEquals(artKey("nas", "alice", "show"), artKey("nas", "bob", "show"))
        assertNotEquals(artKey("nas", "alice", "show"), artKey("other-nas", "alice", "show"))
    }

    @Test
    fun showWithoutArtIsRememberedSoItsEpisodesDoNotAskAgain() {
        val index = FnosArtIndex()
        assertFalse(index.remembersShow("nas", "alice", "season"))
        index.putShow("nas", "alice", "season", null)
        assertTrue(index.remembersShow("nas", "alice", "season"))
        assertNull(index.show("nas", "alice", "season"))
    }
}
