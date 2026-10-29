package app.orchard.tv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.orchard.tv.fnos.FnosClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * A range request the NAS answered with a status other than 200 or 206.
 *
 * The player reads [status] to tell a session the server no longer accepts (401, 403) from one that
 * may simply be busy, which it retries.
 */
class FnosHttpException(val status: Int, message: String) : IOException(message)

/**
 * Reads a film off the NAS film library's `/v/api/v1/media/range` endpoint for ExoPlayer.
 *
 * The endpoint serves byte ranges, so a seek is one request at a new offset and ExoPlayer maps its
 * read sizes onto [DataSpec.position]. Each open is signed afresh: an `Authx` signature is good for
 * one request, and ExoPlayer opens many.
 *
 * Reads block for as long as the NAS takes: there is no read timeout (see [factory]), because a
 * backlogged NAS can stall the first frames for minutes.
 */
class FnosDataSource(
    private val client: OkHttpClient,
    private val token: String?,
) : BaseDataSource(true) {

    /** Only the signature is borrowed from the API client: the URL is the one ExoPlayer was given. */
    private val signer = FnosClient("", null)

    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var input: InputStream? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        dataSpec = spec
        val url = spec.uri.toString()
        val end = if (spec.length == C.LENGTH_UNSET.toLong()) null else spec.position + spec.length - 1
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("x-trim-client", "web")
            .header("x-trim-client-version", CLIENT_VERSION)
            .header("Cookie", "mode=relay")
            .header("Authx", FnosClient.signature(Uri.parse(url).encodedPath.orEmpty(), null))
            .header("Range", if (end == null) "bytes=${spec.position}-" else "bytes=${spec.position}-$end")
            .apply { if (!token.isNullOrEmpty()) header("Authorization", token) }
            .get()
            .build()

        val http = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw IOException("fnos: ${e.message}", e)
        }
        if (http.code != 200 && http.code != 206) {
            http.close()
            throw FnosHttpException(http.code, "fnos: HTTP ${http.code} for ${spec.uri}")
        }
        response = http
        input = http.body?.byteStream() ?: run { http.close(); throw IOException("fnos: no body") }
        // A 206 carries the rest of the file from the offset asked of it; a 200 is the whole file
        // from byte 0. The caller is told how many bytes follow the position it asked for, and a
        // read past that (or past the file's end) ends the stream.
        val total = http.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
        bytesRemaining = when {
            http.code == 200 -> total?.let { (it - spec.position).coerceAtLeast(0) } ?: C.LENGTH_UNSET.toLong()
            spec.length != C.LENGTH_UNSET.toLong() -> spec.length
            total != null -> (total - spec.position).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }
        transferStarted(spec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val stream = input ?: throw IOException("fnos: not open")
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        val read = stream.read(buffer, offset, toRead)
        if (read == -1) {
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        dataSpec = null
        try {
            input?.close()
        } catch (e: IOException) {
            throw e
        } finally {
            input = null
            response?.close()
            response = null
        }
        transferEnded()
    }

    companion object {
        private const val CLIENT_VERSION = "608"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 9; Aerial) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * A factory over one shared [OkHttpClient]. No read timeout, ten seconds to connect: the NAS
         * answers a range at playback speed, but can take minutes to find a file on a busy disk.
         */
        fun factory(token: String?): DataSource.Factory {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            return DataSource.Factory { FnosDataSource(client, token) }
        }
    }
}
