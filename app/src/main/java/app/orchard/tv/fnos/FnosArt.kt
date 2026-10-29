package app.orchard.tv.fnos

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.orchard.tv.system.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pictures for the shelf and Home's hero, downloaded once per picture and kept on disk, so the
 * shelf shows at once on a cold start and a NAS that is slow or away does not leave it empty.
 *
 * Every file is named after the picture and the width asked of the server, under a folder of its
 * own per server: two fnos servers can carry different pictures under one path. The hero's show
 * metadata (which TV or movie a shelf item belongs to) is memoised for the run in [FnosArtIndex].
 */
object FnosArt {
    private const val TAG = "AerialFnos"
    private lateinit var root: File
    private val index = FnosArtIndex()
    private val resolving = Mutex()

    /** An episode, its season, its TV: four rows is room for the walk to the show and its root. */
    private const val ART_DEPTH = 4

    /** Pictures already decoded this run, by file name. */
    private val memory = HashMap<String, ImageBitmap>()

    fun init(context: Context) {
        if (::root.isInitialized) return
        root = File(context.cacheDir, "fnos").apply { mkdirs() }
    }

    /**
     * One file per picture and width: the shelf asks for 480 px covers, Home's hero for the 1920 px
     * backdrop and the 800 px logo, and the browser must not hand the hero a thumbnail.
     */
    private fun name(host: String, poster: String, width: Int): String =
        Integer.toHexString(host.hashCode()) + "-" + Integer.toHexString(poster.hashCode()) + "-" + width

    private fun file(host: String, poster: String, width: Int) =
        File(File(root, Integer.toHexString(host.hashCode())), name(host, poster, width))

    /**
     * The show art behind a shelf [item]: an episode's season and then its TV, or the movie itself.
     * The lookup is memoised for the run per server, account and show, so a shelf of episodes asks
     * once; a failed lookup is not remembered and is retried on the next title. Call off the main
     * thread: it does network I/O.
     */
    suspend fun showArt(host: String, token: String?, user: String, item: FnosPlayItem): FnosArtwork? =
        withContext(Dispatchers.IO) {
            val start = artStart(item)
            if (host.isBlank() || start.isBlank()) return@withContext null
            // One title is on screen at a time; the lock keeps two quick steps from fetching the
            // same show twice, and the second reads what the first stored.
            resolving.withLock {
                if (index.remembersShow(host, user, start)) {
                    index.show(host, user, start)
                } else {
                    val art = try {
                        resolve(FnosClient(host, token, user), host, user, start)
                    } catch (e: Exception) {
                        // A failed lookup is not stored: the next title retries it.
                        DiagLog.i("$TAG art ${start.take(12)} failed: ${e.message}")
                        return@withLock null
                    }
                    index.putShow(host, user, start, art)
                    art
                }
            }
        }

    /** Walks from [start] to the show, deepest first; see [chooseArtwork] for the pick. */
    private fun resolve(client: FnosClient, host: String, user: String, start: String): FnosArtwork? {
        val chain = ArrayList<FnosDetail>(3)
        var current = start
        var depth = 0
        while (depth < ART_DEPTH && current.isNotBlank()) {
            val row = detail(client, host, user, current)
            chain += row
            // A TV or a movie is the show: nothing above it carries its art.
            if (row.type.equals(TYPE_TV, ignoreCase = true) || row.type.equals(TYPE_MOVIE, ignoreCase = true)) break
            val parent = row.parentGuid
            if (parent.isBlank() || parent == current) break
            current = parent
            depth++
        }
        return chooseArtwork(chain)
    }

    /** One detail row, kept for the run. A failed call throws, so it is retried, not remembered. */
    private fun detail(client: FnosClient, host: String, user: String, guid: String): FnosDetail {
        index.detail(host, user, guid)?.let { return it }
        val row = client.detail(guid)
        index.putDetail(host, user, guid, row)
        return row
    }

    /** What is on disk already, or null. A few file checks and one decode: fine on the main thread. */
    fun cached(host: String, poster: String, width: Int): ImageBitmap? {
        val id = name(host, poster, width)
        memory[id]?.let { return it }
        val f = file(host, poster, width).takeIf { it.exists() } ?: return null
        val image = runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() ?: return null
        memory[id] = image
        return image
    }

    /**
     * The picture for [poster] at [width] pixels, fetched once and kept on disk; null when the
     * server has none. Call off the main thread: it decodes.
     */
    suspend fun load(host: String, poster: String, width: Int, token: String?): ImageBitmap? = withContext(Dispatchers.IO) {
        if (poster.isBlank() || host.isBlank()) return@withContext null
        cached(host, poster, width)?.let { return@withContext it }
        runCatching { fetch(host, poster, width, token) }
            .onFailure { DiagLog.i("$TAG cover ${poster.take(40)} failed: ${it.message}") }
            .getOrNull()
    }

    private fun fetch(host: String, poster: String, width: Int, token: String?): ImageBitmap? {
        val bytes = FnosClient(host, token).image(poster, width) ?: return null
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        writeAtomic(file(host, poster, width), bytes)
        val image = decoded.asImageBitmap()
        synchronized(memory) { memory[name(host, poster, width)] = image }
        return image
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }
}

/**
 * A cover as Compose state: whatever the disk has at once, then the loaded picture when it arrives.
 * [token] is left out of the key on purpose: signing in again must not refetch every cover.
 */
@Composable
fun rememberCover(host: String, poster: String, width: Int, token: String?): ImageBitmap? {
    var image by remember(host, poster, width) { mutableStateOf(FnosArt.cached(host, poster, width)) }
    LaunchedEffect(host, poster, width, token) {
        if (image != null) return@LaunchedEffect
        image = FnosArt.load(host, poster, width, token)
    }
    return image
}
