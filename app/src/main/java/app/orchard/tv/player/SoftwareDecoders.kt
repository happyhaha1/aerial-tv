package app.orchard.tv.player

import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * A decoder list of Google's own software codecs only (`omx.google.*`, `c2.android.*`), for the one
 * fallback the player makes when a TV's hardware decoder refuses a file.
 *
 * Filtering the platform's list rather than building one keeps the platform's own ordering and its
 * secure/tunneled checks; a TV without a software codec for the mime type yields an empty list, and
 * the decoder query then fails the way it would have anyway.
 */
object SoftwareDecoders {
    val SELECTOR: MediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunneledPlayback ->
        MediaCodecSelector.DEFAULT
            .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunneledPlayback)
            .filter(::isGoogleSoftware)
    }

    /** Whether [info] is one of Android's own software decoders rather than the chip vendor's. */
    fun isGoogleSoftware(info: MediaCodecInfo): Boolean =
        info.name.startsWith("omx.google.") || info.name.startsWith("c2.android.")
}