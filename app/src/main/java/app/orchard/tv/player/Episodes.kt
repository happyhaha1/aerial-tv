package app.orchard.tv.player

import app.orchard.tv.fnos.FnosPlayItem

/**
 * Which episode follows the one playing, for autoplay at the end of an episode.
 *
 * The list is the show's own episode list, in season/episode order as the NAS returns it, so the
 * next entry after the one playing is the next episode — also across a season boundary when the
 * list spans seasons. A film, or a show the NAS listed nothing for, has no next episode and the
 * player keeps its old behaviour of finishing.
 */
object Episodes {
    /**
     * The episode after [current] in [list], or null when [current] is the last one, when the list
     * is empty, or when [current] is not an episode of it.
     *
     * The current episode is found by guid first, since two episodes can share a number across
     * seasons; a list whose guids do not match (a re-imported library, a stale shelf card) is
     * matched on season and episode instead.
     */
    fun next(list: List<FnosPlayItem>, current: FnosPlayItem): FnosPlayItem? {
        if (list.isEmpty()) return null
        // A film has no episode number and nothing to advance to.
        if (current.episode <= 0 && current.guid.isEmpty()) return null
        val at = list.indexOfFirst { current.guid.isNotEmpty() && it.guid == current.guid }
            .takeIf { it >= 0 }
            ?: list.indexOfFirst { it.season == current.season && it.episode == current.episode }
        if (at < 0) return null
        return list.getOrNull(at + 1)
    }
}