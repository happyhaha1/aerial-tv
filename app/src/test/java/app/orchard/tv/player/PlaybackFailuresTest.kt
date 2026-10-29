package app.orchard.tv.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The player's retry policy: which failures can be retried, how often, and which never are. */
class PlaybackFailuresTest {

    @Test
    fun transportErrorsRetryAndDecodingErrorsFallBackToSoftware() {
        assertEquals(
            FailureAction.Retry,
            PlaybackFailures.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
        )
        assertEquals(
            FailureAction.Retry,
            PlaybackFailures.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
        )
        assertEquals(
            FailureAction.Retry,
            PlaybackFailures.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
        assertEquals(
            FailureAction.SoftwareDecode,
            PlaybackFailures.classify(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED),
        )
        assertEquals(
            FailureAction.SoftwareDecode,
            PlaybackFailures.classify(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED),
        )
        // A container the player cannot read is not a decoder problem and gains nothing from a retry.
        assertEquals(
            FailureAction.Report,
            PlaybackFailures.classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED),
        )
    }

    @Test
    fun aRefusedSessionIsNeverRetried() {
        assertEquals(FailureAction.SignIn, PlaybackFailures.classifyHttp(401))
        assertEquals(FailureAction.SignIn, PlaybackFailures.classifyHttp(403))
        // A server that may only be busy is retried.
        assertEquals(FailureAction.Retry, PlaybackFailures.classifyHttp(503))
        assertEquals(FailureAction.SignIn, PlaybackFailures.classifyServer(401))
        // 1001 is what this server answers to every transcoding request: reported, not retried.
        assertEquals(FailureAction.Report, PlaybackFailures.classifyServer(1001))
        assertEquals(FailureAction.Report, PlaybackFailures.classifyServer(-1))
    }

    @Test
    fun retriesStopAfterTheLimitWithGrowingBackoff() {
        assertEquals(1_500L, PlaybackFailures.retryDelayMs(0))
        assertEquals(3_000L, PlaybackFailures.retryDelayMs(1))
        assertNull(PlaybackFailures.retryDelayMs(PlaybackFailures.RETRY_LIMIT))
        assertNull(PlaybackFailures.retryDelayMs(-1))
        assertNull(PlaybackFailures.retryDelayMs(9))
    }
}