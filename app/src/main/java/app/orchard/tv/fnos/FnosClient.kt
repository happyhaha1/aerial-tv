package app.orchard.tv.fnos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Where `play/play` points: a relative play link, its HLS segment length, and the subtitle link. */
data class FnosPlayLink(val link: String, val hlsTimeSec: Int, val subtitleLink: String) {
    /** Whether the answer is an HLS manifest rather than a progressive file. */
    val isHls: Boolean get() = link.contains(".m3u8")
}

/** One transcoding request: the media, its tracks by the guid `stream` gives each, and where to start. */
data class FnosTranscode(
    val mediaGuid: String,
    val videoGuid: String,
    val videoEncoder: String,
    val resolution: String,
    val bitrate: Long,
    val startTimestampSec: Long,
    val audioGuid: String,
    val audioEncoder: String,
    val channels: Int,
    val subtitleGuid: String = FnosClient.NO_SUBTITLE,
)

/**
 * The NAS film library (飞牛影视) over its web API, which Aerial reads for the continue-watching row.
 *
 * Every call carries an `Authx` signature, and everything but login needs the session token from
 * [login]. The blocking methods are the API; the `suspend` ones are the same calls on IO, and the
 * player's data source calls the blocking ones directly.
 *
 * Two server behaviours shape the code: a query string is signed by its raw text, so GETs here send
 * none; and paged endpoints take their parameters in the JSON body, with only `page` and
 * `page_size` in the query.
 */
class FnosClient(val baseUrl: String, token: String?, private val user: String? = null) {
    private val token: String? = token.takeUnless { it.isNullOrEmpty() }
    private val http = httpClient()

    /** A signed-in session: the normalised [host] (`http://nas:5666`), its token and the account. */
    data class Session(val host: String, val token: String, val user: String)

    // --- Auth ------------------------------------------------------------------------------

    /**
     * Signs in and returns the session token. [host] may be a bare address (`nas`, `nas:5666`);
     * [pass] is sent unchanged, as required by the v1 endpoint.
     */
    fun login(host: String, user: String, pass: String): Result<Session> = capture {
        val base = normalize(host)
        // The v1 endpoint accepts the unchanged password and a 6-digit nonce.
        val body = JSONObject()
            .put("app_name", CLIENT_NAME)
            .put("username", user)
            .put("password", pass)
            .put("nonce", (100000..999999).random().toString())
        // Login is the one call without a token; its envelope nests the token under `data`.
        val data = call(base + LOGIN, body.toString(), null)
        val token = data.optString("token")
        if (token.isEmpty()) throw FnosException(-1, "login returned no token")
        Session(base, token, user)
    }

    suspend fun loginAsync(host: String, user: String, pass: String): Result<Session> = io { login(host, user, pass) }

    // --- Library ---------------------------------------------------------------------------

    /** The continue-watching list, most recent first. */
    fun playList(): List<FnosPlayItem> = listing("GET", "/play/list")

    /**
     * The cover picture under [poster] at [width] pixels wide, or null when the server has none.
     * The server requires the session's `Authorization` as well as the signature.
     */
    fun image(poster: String, width: Int): ByteArray? {
        if (poster.isEmpty()) return null
        // The picture endpoint is signed like the rest (its query is part of the signature); only
        // the stored path is signed, `w` is added after, as the web client does.
        val path = "/v/api/v1/sys/img" + (if (poster.startsWith("/")) poster else "/$poster")
        val url = baseUrl + path + "?w=$width"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("x-trim-client", "web")
            .header("x-trim-client-version", CLIENT_VERSION)
            .header("Cookie", "mode=relay")
            .header("Authx", authx(path, poster))
            .apply { token?.let { header("Authorization", it) } }
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
        } catch (e: IOException) {
            null
        }
    }

    suspend fun playListAsync(): List<FnosPlayItem> = io { playList() }

    /** Resume point, file and tracks of one item. */
    fun playInfo(itemGuid: String): FnosPlayInfo {
        val data = callOrThrow("/play/info", JSONObject().put("item_guid", itemGuid).toString(), null)
        val item = data.optJSONObject("item") ?: JSONObject()
        val media = item.optJSONObject("media_stream")
        val subtitle = data.optString("subtitle_guid").ifEmpty { item.optString("subtitle_guid") }
        return FnosPlayInfo(
            itemGuid = data.optString("guid").ifEmpty { itemGuid },
            mediaGuid = data.optString("media_guid"),
            tsSec = data.optLong("ts"),
            // The envelope's own duration is usually 0 (it fills in only sometimes); the item's
            // runtime is minutes, which is the fallback.
            durationSec = data.optLong("duration").takeIf { it > 0 }
                ?: (item.optLong("duration").takeIf { it > 0 } ?: item.optLong("runtime") * 60),
            title = item.optString("title"),
            tvTitle = item.optString("tv_title"),
            season = item.optInt("season_number", 0),
            episode = item.optInt("episode_number", 0),
            resolutions = strings(media?.optJSONArray("resolutions")),
            audioGuids = strings(media?.optJSONArray("audio_type")),
            subtitleGuid = subtitle.takeIf { it.isNotEmpty() && it != NO_SUBTITLE },
        )
    }

    suspend fun playInfoAsync(itemGuid: String): FnosPlayInfo = io { playInfo(itemGuid) }

    /** Runs a blocking call off the main thread. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /**
     * Episodes of one season of a show: `parentGuid` is the season's guid, which a continue-watching
     * item carries as [FnosPlayItem.parentGuid].
     *
     * The listing carries no file behind an episode, so [FnosPlayItem.mediaGuid] is empty: resolve
     * it with [playInfo] on the episode before playing it.
     */
    fun episodeList(parentGuid: String): List<FnosPlayItem> {
        val data = callOrThrow(ITEM_LIST, JSONObject().put("parent_guid", parentGuid).toString(), PAGE_ALL)
        val list = data.optJSONArray("list") ?: JSONArray()
        return (0 until list.length())
            .mapNotNull { list.optJSONObject(it) }
            .map { item(it, parentGuid) }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }

    suspend fun episodeListAsync(parentGuid: String): List<FnosPlayItem> = io { episodeList(parentGuid) }

    /**
     * One item's own row (`GET /item/{guid}`): what it is, the item above it, and the wide art it
     * carries. The endpoint needs the session token, like the cover pictures it points at.
     *
     * A show's `backdrops` and `logos` are `sys/img` paths; a movie carries a backdrop and usually
     * no logo.
     */
    fun detail(guid: String): FnosDetail {
        val data = callOrThrow(ITEM + guid, null, null, "GET")
        return FnosDetail(
            guid = data.optString("guid").ifEmpty { guid },
            type = data.optString("type"),
            title = data.optString("title"),
            parentGuid = data.optString("parent_guid"),
            backdrops = artPath(data, "backdrops"),
            logos = artPath(data, "logos"),
        )
    }

    suspend fun detailAsync(guid: String): FnosDetail = io { detail(guid) }

    // --- Playback --------------------------------------------------------------------------

    /**
     * File size, video format and quality menu of a media guid, for the player's quality menu. The
     * `ip` field is the account's md5, which only [login] knows: a client made for playback carries
     * the account it was signed in with.
     */
    fun stream(mediaGuid: String): FnosStream = stream(mediaGuid, user.orEmpty())

    fun stream(mediaGuid: String, user: String): FnosStream {
        val body = JSONObject()
            .put("header", JSONObject().put("User-Agent", JSONArray().put("Mozilla/5.0")))
            .put("level", 1)
            .put("media_guid", mediaGuid)
            .put("ip", md5(user))
            .put("nonce", nonce())
        val data = callOrThrow("/stream", body.toString(), null)
        val video = data.optJSONObject("video_stream") ?: JSONObject()
        val audioArray = data.optJSONArray("audio_streams") ?: JSONArray()
        val audio = (0 until audioArray.length()).mapNotNull { audioArray.optJSONObject(it) }
        val qualities = data.optJSONArray("qualities") ?: JSONArray()
        return FnosStream(
            qualities = (0 until qualities.length()).mapNotNull { qualities.optJSONObject(it) }.map {
                FnosQuality(it.optString("resolution"), it.optLong("bitrate"))
            },
            videoCodec = video.optString("codec_name"),
            width = video.optInt("width"),
            height = video.optInt("height"),
            fileSize = data.optJSONObject("file_stream")?.optLong("size") ?: 0,
            videoGuid = video.optString("guid"),
            audioGuids = audio.map { it.optString("guid") },
            audioCodecs = audio.map { it.optString("codec_name") },
            audioChannels = audio.firstOrNull()?.optInt("channels") ?: 0,
        )
    }

    suspend fun streamAsync(mediaGuid: String, user: String): FnosStream = io { stream(mediaGuid, user) }

    suspend fun streamAsync(mediaGuid: String): FnosStream = streamAsync(mediaGuid, user.orEmpty())

    /**
     * Where the film file itself is served, for [FnosDataSource]. The server honours byte ranges
     * on it, so seeking costs a request and not a re-read.
     */
    fun playUrl(mediaGuid: String): String = "$baseUrl/v/api/v1/media/range/$mediaGuid"

    /** A `play_link` as a URL: the API answers with a path relative to the same server. */
    fun playLinkUrl(link: String): String = when {
        link.startsWith("http://") || link.startsWith("https://") -> link
        link.startsWith("/") -> baseUrl + link
        else -> "$baseUrl/$link"
    }

    /**
     * Asks the server to transcode the media named by [FnosTranscode] and says where the stream is.
     *
     * The tracks are named by the guids `stream` gives, as the reference client does. On 影视 0.9.8
     * the endpoint answers `code 1001 System busy` to every combination tried so far: the answer is
     * returned to the caller as it came, never turned into a pretend switch.
     */
    fun playPlay(request: FnosTranscode): FnosPlayLink {
        val body = JSONObject()
            .put("media_guid", request.mediaGuid)
            .put("video_guid", request.videoGuid)
            .put("video_encoder", request.videoEncoder)
            .put("resolution", request.resolution)
            .put("bitrate", request.bitrate)
            .put("startTimestamp", request.startTimestampSec)
            .put("audio_guid", request.audioGuid)
            .put("audio_encoder", request.audioEncoder)
            .put("channels", request.channels)
            .put("subtitle_guid", request.subtitleGuid)
        val data = callOrThrow("/play/play", body.toString(), null)
        return FnosPlayLink(
            link = data.optString("play_link"),
            hlsTimeSec = data.optInt("hls_time"),
            subtitleLink = data.optString("subtitle_link"),
        )
    }

    suspend fun playPlayAsync(request: FnosTranscode): FnosPlayLink = io { playPlay(request) }

    /**
     * Tells the NAS where playback stopped: its own clients see the same position, and the item
     * stays in the continue-watching list.
     *
     * [resolution] is the quality that actually played; "original" for a direct play.
     */
    fun record(
        item: FnosPlayItem,
        mediaGuid: String,
        tsSec: Long,
        durationSec: Long,
        resolution: String = "original",
        audioGuid: String = "",
        subtitleGuid: String = NO_SUBTITLE,
    ) {
        val body = JSONObject()
            .put("item_guid", item.guid)
            .put("media_guid", mediaGuid)
            .put("video_guid", "")
            .put("audio_guid", audioGuid)
            .put("subtitle_guid", subtitleGuid)
            .put("resolution", resolution)
            .put("bitrate", 0)
            .put("ts", tsSec.coerceIn(0, durationSec.takeIf { it > 0 } ?: Long.MAX_VALUE))
            .put("duration", durationSec)
        callOrThrow("/play/record", body.toString(), null)
    }

    suspend fun recordAsync(
        item: FnosPlayItem,
        mediaGuid: String,
        tsSec: Long,
        durationSec: Long,
        resolution: String = "original",
        audioGuid: String = "",
        subtitleGuid: String = NO_SUBTITLE,
    ): Unit = io { record(item, mediaGuid, tsSec, durationSec, resolution, audioGuid, subtitleGuid) }

    /**
     * The value of the `Authx` header for [path] (the `/v/...` part of the URL) and [body], or null
     * for a request without one.
     *
     * The sign covers an md5 of the body, a fresh 6-digit nonce and the millisecond timestamp under
     * the web client's fixed key, so a request cannot be replayed or retargeted.
     */
    fun authx(path: String, data: String?): String = signature(path, data)

    /** The `data` object of a `/v/api/v1` [path]; throws [FnosException] on any failure. */
    private fun callOrThrow(path: String, body: String?, query: String?, method: String = "POST"): JSONObject {
        val result = capture { call(baseUrl + "/v/api/v1" + path, body, query, method) }
        return result.getOrElse { throw it }
    }

    /** A listing endpoint: the `list` of its `data`, as play items. */
    private fun listing(method: String, path: String, body: String? = null, query: String? = null): List<FnosPlayItem> {
        val data = callOrThrow(path, body, query, method)
        // A paged listing wraps its rows in `list`; `play/list` answers with the rows themselves.
        val list = data.optJSONArray("list") ?: data.optJSONArray(ARRAY) ?: JSONArray()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { item(it, null) }
    }

    /**
     * Sends one request and returns the `data` object of its envelope.
     *
     * [body] is the JSON to send, if any; [query] is appended verbatim, and both are covered by the
     * signature. [method] other than GET sends an empty body so the URL stays signature-exact.
     */
    private fun call(url: String, body: String?, query: String?, method: String = "POST"): JSONObject {
        if (token == null && !url.endsWith(LOGIN)) throw FnosException(-1, "not signed in")
        // The signature covers the path and query exactly as they are sent.
        val path = url.toHttpUrl().encodedPath
        val request = Request.Builder()
            .url(if (query == null) url else "$url?$query")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("x-trim-client", "web")
            .header("x-trim-client-version", CLIENT_VERSION)
            .header("Authx", authx(path, body))
            .header("Referer", baseUrl)
        if (token != null) request.header("Authorization", token)
        when {
            method == "GET" -> request.get()
            body != null -> request.post(body.toRequestBody(JSON))
            else -> request.post(ByteArray(0).toRequestBody(JSON))
        }
        val response = try {
            http.newCall(request.build()).execute()
        } catch (e: IOException) {
            throw FnosException(-1, "network: ${e.message}")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw FnosException(it.code, text.take(200).ifEmpty { it.message }, text.take(200))
            val json = try {
                JSONObject(text)
            } catch (e: org.json.JSONException) {
                throw FnosException(it.code, "not JSON: ${text.take(200)}")
            }
            val code = json.optInt("code", -1)
            if (code != 0) throw FnosException(code, json.optString("msg"), json.optString("msg"))
            // `data` is an object on most endpoints and a bare array on `play/list`.
            return json.optJSONObject("data")
                ?: JSONObject().put(ARRAY, json.optJSONArray("data") ?: JSONArray())
        }
    }

    private fun item(obj: JSONObject, parent: String?): FnosPlayItem = FnosPlayItem(
        guid = obj.optString("guid"),
        tvTitle = obj.optString("tv_title"),
        title = obj.optString("title"),
        season = obj.optInt("season_number"),
        episode = obj.optInt("episode_number"),
        poster = obj.optString("poster"),
        tsSec = obj.optLong("ts"),
        durationSec = obj.optLong("duration"),
        mediaGuid = obj.optString("media_guid"),
        parentGuid = obj.optString("parent_guid").ifEmpty { parent.orEmpty() },
    )

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }.distinct()

    /** A `sys/img` path stored as a string; an array of them is read as its first picture. */
    private fun artPath(data: JSONObject, key: String): String {
        val value = data.opt(key)
        return when (value) {
            is String -> value
            is JSONArray -> (0 until value.length())
                .map { value.optString(it) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            else -> ""
        }
    }

    private fun <T> capture(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: FnosException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(FnosException(-1, e.message ?: e.javaClass.simpleName))
    }

    companion object {
        private const val LOGIN = "/v/api/v1/login"
        private const val ITEM = "/item/"
        private const val ITEM_LIST = "/item/list"
        private const val CLIENT_NAME = "trimemedia-web"
        private const val CLIENT_VERSION = "608"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 9; Aerial) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val PAGE_ALL = "page=1&page_size=500"

        /** Key for a `data` that is a bare array rather than an object. */
        private const val ARRAY = "items"

        /** How the API spells "no subtitle track". */
        const val NO_SUBTITLE = "_no_display_"

        // The web client's fixed signing key pair, in the source on purpose: the NAS requires them.
        private const val SIGN_KEY = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh"
        private const val SIGN_SECRET = "16CCEB3D-AB42-077D-36A1-F355324E4237"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** No read timeout: a backlogged NAS can stall a film read for longer than any fixed limit. */
        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        /** `nas`, `nas:5666` or `http://nas:5666/` to `http://nas:5666`; throws on an empty host. */
        fun normalize(host: String): String {
            var h = host.trim().trimEnd('/')
            if (h.isEmpty()) throw FnosException(-1, "empty host")
            if (!h.startsWith("http://") && !h.startsWith("https://")) h = "http://$h"
            if (!h.removePrefix("https://").removePrefix("http://").contains(':')) h = "$h:$DEFAULT_PORT"
            return h.removeSuffix("/v")
        }

        private const val DEFAULT_PORT = "5666"

        private fun nonce(): String = (100_000 + (Math.random() * 900_000).toInt()).toString()

        /**
         * The `Authx` value for one request: a fresh nonce and timestamp with the sign over [path],
         * the md5 of [data] and the web client's key. Static so data sources can sign without a
         * client of their own.
         */
        fun signature(path: String, data: String?): String {
            val n = nonce()
            val ts = System.currentTimeMillis()
            val sign = md5(listOf(SIGN_KEY, path, n, ts.toString(), md5(data ?: ""), SIGN_SECRET).joinToString("_"))
            return "nonce=$n&timestamp=$ts&sign=$sign"
        }

        fun md5(text: String): String = digest("MD5", text)

        fun sha256(text: String): String = digest("SHA-256", text)

        private fun digest(algorithm: String, text: String): String =
            MessageDigest.getInstance(algorithm).digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
