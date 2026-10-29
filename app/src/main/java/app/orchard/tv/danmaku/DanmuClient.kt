package app.orchard.tv.danmaku

import app.orchard.tv.BuildConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.random.Random

/**
 * One comment of the DanDanPlay format (`docs/DANMAKU.md` §1): [text] at [atSec] seconds into the
 * episode, drawn in [mode] and in [colorArgb] (opaque).
 */
data class Danmaku(val text: String, val atSec: Float, val colorArgb: Int, val mode: Int) {
    companion object {
        /** Rolls right to left across its lane (Bilibili types 1, 2 and 3). */
        const val SCROLL = 1

        /** Fixed, at the bottom of the content, for a few seconds. */
        const val BOTTOM = 4

        /** Fixed, at the top of the lane area. */
        const val TOP = 5

        /** Rolls left to right across its lane. */
        const val REVERSE = 6
    }
}

/**
 * Reads the service's comments. The field returns three envelopes — `{comments:[…]}`, `{data:[…]}`
 * and a bare array — and the odd line inside them is junk, so nothing here throws: what cannot be
 * read is left out and an unreadable body reads as "no comments".
 */
object DanmuParser {
    /** The most comments kept for one episode; a longer list is thinned by ratio. */
    const val MAX_COMMENTS = 5000

    private const val WHITE = 0xFFFFFF
    private val OPAQUE = 0xFF000000.toInt()

    /** Fixed, so thinning the same episode twice keeps the same comments. */
    private val spread = Random(0x44414E4D)

    /** The comments of [body], earliest first, at most [limit] of them. */
    fun parse(body: String, limit: Int = MAX_COMMENTS): List<Danmaku> {
        val entries = entries(body)
        val comments = ArrayList<Danmaku>(entries.length())
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            comment(entry)?.let(comments::add)
        }
        comments.sortBy { it.atSec }
        return if (limit < 1 || comments.size <= limit) comments else thin(comments, limit)
    }

    /** The entries, whichever envelope the service used. */
    private fun entries(body: String): JSONArray {
        val text = body.trim()
        if (text.isEmpty()) return JSONArray()
        return try {
            if (text.startsWith("[")) JSONArray(text)
            else JSONObject(text).let { it.optJSONArray("comments") ?: it.optJSONArray("data") ?: JSONArray() }
        } catch (error: JSONException) {
            JSONArray()
        }
    }

    /** One `{"m": …, "p": …}` entry, or null when the line is not one. */
    private fun comment(entry: JSONObject): Danmaku? {
        // A comment is one line by construction: a text field with a break in it would draw over
        // the lane below it.
        val text = entry.optString("m").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        if (text.isBlank()) return null
        val fields = entry.optString("p").split(',')
        val atSec = fields[0].trim().toFloatOrNull() ?: return null
        if (!atSec.isFinite() || atSec < 0f) return null
        // No second field is the short form, which is a scrolling comment. A field that is there
        // but not one of the four modes is dropped: guessing would put a special comment in the
        // wrong place, and a wrong place is worse than no comment.
        val mode = modeOf(fields.getOrNull(1)?.trim()) ?: return null
        val rgb = if (fields.size > 2) {
            fields[2].trim().toIntOrNull() ?: WHITE
        } else {
            // The short form carries the colour beside the time instead of inside `p`.
            entry.optInt("c", WHITE)
        }
        return Danmaku(text, atSec, OPAQUE or (rgb and WHITE), mode)
    }

    /**
     * The service's mode field canonicalized to [Danmaku]'s four: Bilibili's legacy right-to-left
     * types 2 and 3 are the same comment as 1, 6 is the left-to-right scroll, and 7 (special), 8
     * (code) and 9 (BAS) have no lane model here. Null means the entry is dropped.
     */
    private fun modeOf(raw: String?): Int? {
        if (raw == null) return Danmaku.SCROLL
        return when (raw.toIntOrNull()) {
            1, 2, 3 -> Danmaku.SCROLL
            4 -> Danmaku.BOTTOM
            5 -> Danmaku.TOP
            6 -> Danmaku.REVERSE
            else -> null
        }
    }

    /**
     * Keeps every [step]th comment when there are more than [limit]. A spread across the whole
     * episode beats its first [limit] lines, which would cover only its opening.
     */
    private fun thin(comments: List<Danmaku>, limit: Int): List<Danmaku> {
        val step = comments.size.toFloat() / limit
        val kept = ArrayList<Danmaku>(limit)
        var next = spread.nextFloat() * step
        for (comment in comments) {
            next += 1f
            if (next >= step) {
                next -= step
                kept += comment
            }
        }
        return kept
    }
}

/** What one fetch produced. */
sealed class DanmuFetch {
    /** The comments of the episode; empty when the service knows it and has nothing for it. */
    class Ok(val comments: List<Danmaku>) : DanmuFetch()

    /** The service did not answer (or was never configured). */
    object Unreachable : DanmuFetch()

    /** The service answered but has no episode for this file. */
    object NoMatch : DanmuFetch()
}

/**
 * The DanDanPlay-compatible service of `docs/DANMAKU.md` §1, over plain HttpURLConnection: the
 * service runs on the owner's NAS, so nothing here may hold a TV's player up, and a fetch is only
 * made when neither the memory nor the disk has the episode.
 */
object DanmuClient {
    private const val MATCH = "/api/v2/match"
    private const val BANGUMI = "/api/v2/bangumi/"
    private const val COMMENT = "/api/v2/comment/"
    private const val CACHE_DIR = "danmaku"

    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 8000

    private val HEX = "0123456789abcdef".toCharArray()

    /** Episodes already fetched this run, so a second look is free. */
    private val memory = HashMap<String, List<Danmaku>>()

    /**
     * The comments of [title]'s [episode] on [server]. [cacheDir] keeps a fetch for the next run.
     * Blocking: call it on IO. Suspends only to give up early when the screen has moved on.
     */
    suspend fun fetch(server: String, title: String, episode: Int, cacheDir: File?): DanmuFetch {
        val base = server.trim().trimEnd('/')
        if (base.isEmpty()) return DanmuFetch.Unreachable
        val cacheKey = key(base, title, episode)
        synchronized(memory) { memory[cacheKey] }?.let { return DanmuFetch.Ok(it) }
        val file = cacheFile(cacheDir, cacheKey)
        if (file != null && file.isFile) {
            // The file is the service's own body, not parsed comments: every read goes through
            // the current parser, so an old file's mode 6 comes back as REVERSE and mode 7 is
            // dropped instead of being silently read as a scrolling comment.
            val cached = try {
                DanmuParser.parse(file.readText())
            } catch (_: IOException) {
                null // Android may trim a cache file between isFile and the read.
            }
            if (cached != null) {
                remember(cacheKey, cached)
                return DanmuFetch.Ok(cached)
            }
        }
        return try {
            currentCoroutineContext().ensureActive()
            val id = episodeId(base, title, episode) ?: return DanmuFetch.NoMatch
            currentCoroutineContext().ensureActive()
            val body = request("$base$COMMENT$id?format=json", "GET", null) ?: return DanmuFetch.NoMatch
            val comments = DanmuParser.parse(body)
            file?.let { write(it, body) }
            remember(cacheKey, comments)
            DanmuFetch.Ok(comments)
        } catch (error: IOException) {
            DanmuFetch.Unreachable
        } catch (error: JSONException) {
            // An answer that is not DanDanPlay's JSON is no better than no answer at all.
            DanmuFetch.NoMatch
        }
    }

    /**
     * The episode to ask for: the number the player gave decides, and the service's own pick is
     * only the fallback, since `/match` matches the file and not the number we asked for.
     */
    private fun episodeId(base: String, title: String, episode: Int): Int? {
        val match = request(base + MATCH, "POST", JSONObject().put("fileName", fileName(title, episode)).toString())
            ?.let { JSONObject(it) } ?: return null
        val first = match.optJSONArray("matches")?.optJSONObject(0) ?: return null
        val matched = first.optInt("episodeId", 0).takeIf { it > 0 }
        if (episode <= 0) return matched
        val animeId = first.optInt("animeId", 0)
        if (animeId > 0) {
            // The service nests the list under `bangumi`; accept a bare `episodes` too.
            val body = request("$base$BANGUMI$animeId", "GET", null)?.let { JSONObject(it) }
            val episodes = body?.optJSONObject("bangumi")?.optJSONArray("episodes")
                ?: body?.optJSONArray("episodes")
            for (index in 0 until (episodes?.length() ?: 0)) {
                val entry = episodes?.optJSONObject(index) ?: continue
                // The service sends `episodeNumber` as a JSON string ("1"), not a number.
                val number = entry.optInt("episodeNumber", -1).takeIf { it > 0 }
                    ?: entry.optString("episodeNumber").toIntOrNull()
                if (number == episode) {
                    entry.optInt("episodeId", 0).takeIf { it > 0 }?.let { return it }
                }
            }
        }
        return matched?.takeIf { number(first.optString("episodeTitle")) == episode }
    }

    /** The episode number inside the service's own episode title ("第 3 话", "E03", "3"). */
    private fun number(title: String): Int? = Regex("\\d+").find(title)?.value?.toIntOrNull()

    /** What `/match` is asked for: `"<title> S01E03"`, the shape DanDanPlay expects. */
    private fun fileName(title: String, episode: Int): String {
        val name = title.trim()
        return if (episode > 0) "%s S01E%02d".format(name, episode) else name
    }

    /** One request; null when the service answers 404, and [IOException] when it does not answer. */
    private fun request(url: String, method: String, body: String?): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Aerial/" + BuildConfig.VERSION_NAME)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code !in 200..299) throw IOException("HTTP $code from $url")
            return connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun remember(key: String, comments: List<Danmaku>) {
        synchronized(memory) { memory[key] = comments }
    }

    /** A cache entry belongs to one server: another service may number its episodes differently. */
    private fun key(server: String, title: String, episode: Int): String =
        server.trim().trimEnd('/') + "#" + title.trim() + "#" + episode

    private fun cacheFile(cacheDir: File?, key: String): File? {
        if (cacheDir == null) return null
        return File(File(cacheDir, CACHE_DIR), digest(key) + ".json")
    }

    /** A fetch is kept as the service sent it, so a change in the parser is picked up on the next run. */
    private fun write(file: File, body: String) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(body)
        } catch (error: IOException) {
            // A cache that cannot be written is not worth failing a fetch over.
        }
    }

    private fun digest(key: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            out.append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
        }
        return out.toString()
    }
}