package app.orchard.tv.fnos

/**
 * One entry of the NAS film library's continue-watching list (`play/list`), or one episode of a
 * show (`item/list`).
 *
 * [guid] identifies the item; [mediaGuid] identifies the file behind it, which is what the stream
 * URL and the play record are keyed by. Entries from the episode list carry no [mediaGuid] (0) until
 * [FnosClient.playInfo] resolves it, and [tsSec] is the resume position in whole seconds.
 */
data class FnosPlayItem(
    val guid: String,
    val tvTitle: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val poster: String,
    val tsSec: Long,
    val durationSec: Long,
    val mediaGuid: String,
    val parentGuid: String,
)

/**
 * One item's own row from `GET /item/{guid}`: what it is ([type]: "TV", "Season", "Episode" or
 * "Movie"), the item above it, and the artwork the show carries.
 *
 * [backdrops] and [logos] are `sys/img` paths as the server stores them, empty when the item has
 * none. A show's logo is a transparent picture; a movie usually has none, so the hero keeps its
 * text title.
 */
data class FnosDetail(
    val guid: String,
    val type: String,
    val title: String,
    val parentGuid: String,
    val backdrops: String,
    val logos: String,
)

/** The show-level art the hero uses, taken from one item: its wide backdrop and transparent logo. */
data class FnosArtwork(val backdrop: String, val logo: String, val title: String)

/** What `play/info` says about an item before playback: the file, the resume point, the tracks. */
data class FnosPlayInfo(
    val itemGuid: String,
    val mediaGuid: String,
    val tsSec: Long,
    val durationSec: Long,
    val title: String,
    val tvTitle: String,
    val season: Int,
    val episode: Int,
    /** Resolution names the file offers, best first ("4k", "1080", ...). */
    val resolutions: List<String>,
    /** Audio track guids (`stream`'s `audio_streams`), best first. */
    val audioGuids: List<String>,
    /** Chosen subtitle track guid, or null for none; `_no_display_` as the API uses for none. */
    val subtitleGuid: String?,
)

/** One transcoding choice of a film: the player picks by [resolution]. */
data class FnosQuality(val resolution: String, val bitrate: Long)

/**
 * The file behind a media guid, from `stream`: direct-play metadata, the tracks `play/play` needs by
 * guid, and the quality menu.
 */
data class FnosStream(
    val qualities: List<FnosQuality>,
    val videoCodec: String,
    val width: Int,
    val height: Int,
    val fileSize: Long,
    /** `video_stream.guid`, which a transcoding request sends back as `video_guid`. */
    val videoGuid: String = "",
    /** `audio_streams[].guid`, in the server's order. */
    val audioGuids: List<String> = emptyList(),
    /** `audio_streams[].codec_name`: the `audio_encoder` a transcoding request names. */
    val audioCodecs: List<String> = emptyList(),
    /** Channels of the first audio stream, for the request's `channels`. */
    val audioChannels: Int = 0,
)

/** The NAS answered with a non-2xx status or its own non-zero `code` envelope. */
class FnosException(val code: Int, message: String, val detail: String? = null) :
    Exception("fnos $code: $message")

/** Item kinds the detail row reports; an episode's still is never its show's art. */
internal const val TYPE_EPISODE = "Episode"
internal const val TYPE_TV = "TV"
internal const val TYPE_MOVIE = "Movie"

/**
 * Which item's art stands for [item]: a movie's own row, or the season an episode belongs to.
 * Episodes of one season share this guid, so the shelf resolves their show once.
 */
internal fun artStart(item: FnosPlayItem): String = when {
    item.guid.isBlank() -> ""
    item.parentGuid.isNotBlank() && (item.season > 0 || item.episode > 0) -> item.parentGuid
    else -> item.guid
}

/**
 * The show's art from a chain walked from an item upwards (deepest first): the highest row with a
 * backdrop, or, when no row has one, the highest with a logo. Both pictures always come from that
 * one row, so a season's backdrop never sits under the TV's logo. Episode rows are skipped: an
 * episode's still is never the show's backdrop.
 */
internal fun chooseArtwork(chain: List<FnosDetail>): FnosArtwork? {
    val shows = chain.filterNot { it.type.equals(TYPE_EPISODE, ignoreCase = true) }
    val show = shows.lastOrNull { it.backdrops.isNotBlank() }
        ?: shows.lastOrNull { it.logos.isNotBlank() }
        ?: return null
    return FnosArtwork(show.backdrops, show.logos, show.title)
}

/** A server, account and item as one key: art from another account is never read as this one's. */
internal fun artKey(host: String, user: String, guid: String): String = "$host|$user|$guid"

/**
 * Run-scoped memo of detail rows and resolved show art, per server and account. A resolved show
 * with no art is remembered too, so a shelf of its episodes does not ask again; a failed lookup is
 * not stored and is retried on the next title.
 */
internal class FnosArtIndex {
    private val details = HashMap<String, FnosDetail>()
    private val shows = HashMap<String, FnosArtwork?>()

    @Synchronized
    fun detail(host: String, user: String, guid: String): FnosDetail? = details[artKey(host, user, guid)]

    @Synchronized
    fun putDetail(host: String, user: String, guid: String, detail: FnosDetail) {
        details[artKey(host, user, guid)] = detail
    }

    /** Whether this account's lookup for [guid] has finished, even when it found no art. */
    @Synchronized
    fun remembersShow(host: String, user: String, guid: String): Boolean =
        shows.containsKey(artKey(host, user, guid))

    @Synchronized
    fun show(host: String, user: String, guid: String): FnosArtwork? = shows[artKey(host, user, guid)]

    @Synchronized
    fun putShow(host: String, user: String, guid: String, art: FnosArtwork?) {
        shows[artKey(host, user, guid)] = art
    }
}
