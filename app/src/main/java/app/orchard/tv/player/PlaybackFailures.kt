package app.orchard.tv.player

import androidx.media3.common.PlaybackException

/** What the player does about a failed playback. */
enum class FailureAction {
    /** A transport problem: start the same source again after a backoff. */
    Retry,

    /** The TV's own decoder could not take the file: start again on Google's software decoders. */
    SoftwareDecode,

    /** The NAS no longer accepts the session, or the request was refused: ask for a new sign-in. */
    SignIn,

    /** Anything else: say what happened and stop. */
    Report,
}

/**
 * The retry policy, as pure decisions: which playback errors are worth retrying, how long the
 * pauses between the retries are, and where the NAS's own answer fits.
 *
 * A transport failure gets [RETRY_LIMIT] automatic restarts before the failure screen; a decoding
 * failure gets one restart on the software decoder instead, because a TV's hardware decoder that
 * refused a file will refuse it again. The NAS's own codes are never retried: a refusal is the
 * server's answer, not a stall.
 */
object PlaybackFailures {
    /** How many automatic retries a transport failure gets before the failure screen. */
    const val RETRY_LIMIT = 2

    private val TRANSPORT = setOf(
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    )

    private val DECODER = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
    )

    /** What a player error code asks for. */
    fun classify(errorCode: Int): FailureAction = when (errorCode) {
        in TRANSPORT -> FailureAction.Retry
        in DECODER -> FailureAction.SoftwareDecode
        else -> FailureAction.Report
    }

    /**
     * What an HTTP status asks for. Only 401 and 403 are final — the session is gone; anything else
     * (a 5xx, a 404 that may be a stale guid) is treated as transport and retried.
     */
    fun classifyHttp(status: Int): FailureAction =
        if (status == 401 || status == 403) FailureAction.SignIn else FailureAction.Retry

    /** What the NAS's own `code` field asks for. Codes below zero are ours: they mean no answer. */
    fun classifyServer(code: Int): FailureAction =
        if (code == 401 || code == 403) FailureAction.SignIn else FailureAction.Report

    /**
     * The pause before the automatic retry [attempt] (0 for the first), or null once
     * [RETRY_LIMIT] attempts have been spent. Backoff doubles, and it is a pause the user watches:
     * long enough to let a busy NAS free itself, short enough not to feel like a hang.
     */
    fun retryDelayMs(attempt: Int): Long? = when {
        attempt < 0 || attempt >= RETRY_LIMIT -> null
        else -> 1_500L shl attempt
    }
}