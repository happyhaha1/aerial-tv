package app.orchard.tv

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.orchard.tv.danmaku.DanmakuOverlay
import app.orchard.tv.danmaku.DanmuPrefs
import app.orchard.tv.fnos.FnosClient
import app.orchard.tv.fnos.FnosException
import app.orchard.tv.fnos.FnosPlayItem
import app.orchard.tv.fnos.FnosPlayLink
import app.orchard.tv.fnos.FnosQuality
import app.orchard.tv.fnos.FnosShelf
import app.orchard.tv.fnos.FnosStream
import app.orchard.tv.fnos.FnosTranscode
import app.orchard.tv.player.ASPECTS
import app.orchard.tv.player.AspectMode
import app.orchard.tv.player.ControlBar
import app.orchard.tv.player.DANMAKU_AREAS
import app.orchard.tv.player.DANMAKU_OPACITIES
import app.orchard.tv.player.DANMAKU_SIZES
import app.orchard.tv.player.DanmakuRow
import app.orchard.tv.player.Episodes
import app.orchard.tv.player.FailureAction
import app.orchard.tv.player.FnosDataSource
import app.orchard.tv.player.FnosHttpException
import app.orchard.tv.player.MenuEntry
import app.orchard.tv.player.MenuKind
import app.orchard.tv.player.PanelMenu
import app.orchard.tv.player.PlaybackFailures
import app.orchard.tv.player.PlayerOverlayState
import app.orchard.tv.player.PlayerPhase
import app.orchard.tv.player.PlayerPrefs
import app.orchard.tv.player.SPEEDS
import app.orchard.tv.player.SeekPace
import app.orchard.tv.player.SoftwareDecoders
import app.orchard.tv.player.StatusLayer
import app.orchard.tv.player.SubtitleLayer
import app.orchard.tv.player.VideoFrame
import app.orchard.tv.player.aspectLabel
import app.orchard.tv.player.danmakuRowOf
import app.orchard.tv.player.playerBar
import app.orchard.tv.player.speedLabel
import app.orchard.tv.player.surfaceSize
import app.orchard.tv.system.DiagLog
import app.orchard.tv.system.Sounds
import app.orchard.tv.ui.theme.canvasDensity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One Activity window: video and transparent controls share the window's composition.
 *
 * The picture is Aerial's own: the item is resolved with `play/info`, the NAS file is played through
 * [FnosDataSource] into a `SurfaceView` under a transparent Compose layer, the position is written
 * back every fifteen seconds, and the controls a remote reaches — audio, subtitles, episodes, speed,
 * quality, aspect, danmaku — live in `player/PlayerOverlay.kt` and `player/PlayerMenus.kt`.
 *
 * The failure policy is `player/PlaybackFailures.kt`: a transport failure is retried twice with a
 * backoff, a decoding failure once on Google's software decoders, and the NAS's own refusal is never
 * retried but told as it is.
 */
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var client: FnosClient? = null
    private var token: String? = null
    private var item by mutableStateOf(FnosPlayItem("", "", "", 0, 0, "", 0, 0, "", ""))
    private var mediaGuid = ""
    private var durationMs by mutableLongStateOf(0L)
    private var resumeMs = 0L
    private var resolved = false
    private var ready = false
    private var userSeeked = false
    private var shouldPlay = true
    private var resumed = false
    private var rendered = false
    private var foreground = false
    private var prepareJob: Job? = null
    private var recordJob: Job? = null
    private var hideJob: Job? = null
    private var retryJob: Job? = null

    /** The burst ladder of the arrow keys, and the job that clears the plate it raises. */
    private val seekPace = SeekPace()
    private var flashJob: Job? = null

    /** The plate the arrow keys raise ("+30 s"); separate from [notice], which is the resume line. */
    private var seekHint by mutableStateOf<String?>(null)

    /** When Back last armed the exit; a second press inside [BACK_ARM_MS] leaves the film. */
    private var backAt = 0L
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private var videoAspect by mutableFloatStateOf(0f)

    /** The season the episode menu lists, and the show's episodes once the NAS has sent them. */
    private var parentGuid = ""
    private var episodesLoaded = false
    private var episodes by mutableStateOf<List<FnosPlayItem>>(emptyList())

    /** The quality menu's own request: the file's track guids and its steps, loaded after the first
     * frame so a busy NAS is not asked for them twice while the film starts. */
    private var stream: FnosStream? = null
    private var qualities by mutableStateOf<List<FnosQuality>>(emptyList())
    private var qualityError by mutableStateOf<String?>(null)
    private var switching by mutableStateOf(false)
    private var currentResolution = "original"
    private var sourceUrl: String? = null
    private var subtitleGuid: String? = null

    /** Retry bookkeeping: a transport failure gets two restarts, a decoding failure one. */
    private var retries = 0
    private var softwareOnly = false
    private var languageApplied = false

    private var positionMs by mutableLongStateOf(0L)
    private var state by mutableStateOf(PlayerPhase.Loading)
    private var controlsVisible by mutableStateOf(false)
    private var playing by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var notice by mutableStateOf<String?>(null)
    private var upNext by mutableStateOf<String?>(null)
    private var status by mutableStateOf<String?>(null)
    private var canRetry by mutableStateOf(false)
    private var cues by mutableStateOf<List<Cue>>(emptyList())
    private var tracks by mutableStateOf(Tracks.EMPTY)
    private var speed by mutableFloatStateOf(1f)
    private var aspectMode by mutableStateOf(AspectMode.Fit)
    private var menu by mutableStateOf<MenuKind?>(null)
    private var barFocus by mutableStateOf(MenuKind.Audio)
    private var menuIndex by mutableIntStateOf(0)

    // Capture on the main thread, send in order, and drain the final snapshot after destruction.
    private data class Record(
        val item: FnosPlayItem,
        val media: String,
        val seconds: Long,
        val duration: Long,
        val resolution: String,
        val subtitle: String,
    )

    private val records = Channel<Record>(Channel.UNLIMITED)
    private var lastRecord: Record? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val guid = intent.getStringExtra(EXTRA_GUID).orEmpty()
        if (guid.isEmpty()) {
            finish()
            return
        }
        item = item.copy(
            guid = guid,
            tvTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            season = intent.getIntExtra(EXTRA_SEASON, 0),
            episode = intent.getIntExtra(EXTRA_EPISODE, 0),
            tsSec = intent.getLongExtra(EXTRA_TS, 0),
            parentGuid = intent.getStringExtra(EXTRA_PARENT).orEmpty(),
        )
        parentGuid = item.parentGuid.ifEmpty {
            // The shelf is the one place the season is known without another request; the caller's
            // item usually carries it already.
            FnosShelf.items.firstOrNull { it.guid == guid }?.parentGuid.orEmpty()
        }
        speed = PlayerPrefs.speed(this)
        aspectMode = PlayerPrefs.aspect(this)
        DanmuPrefs.init(this)
        val session = FnosShelf.sessionFor
        if (session != null) {
            client = FnosClient(session.host, session.token, session.user)
            token = session.token
        } else {
            fail(getString(R.string.player_signin), allowRetry = false)
        }
        attachVideo()
        CoroutineScope(Dispatchers.IO).launch {
            for (record in records) {
                try {
                    client?.recordAsync(
                        record.item, record.media, record.seconds, record.duration,
                        record.resolution, "", record.subtitle,
                    )
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    DiagLog.e("fnos record failed", error)
                }
            }
        }
        lifecycleScope.launch {
            while (true) {
                // Position is sampled for the visible controls and for the danmaku clock; it never
                // drives video rendering. While nothing on screen needs it, nothing is read.
                if (foreground && (controlsVisible || danmuOn())) updatePosition()
                delay(if (danmuOn() && playing) DANMAKU_TICK_MS else TICK_MS)
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun attachVideo() {
        root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        val controls = ComposeView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val canvas = Density(canvasDensity(resources.displayMetrics), LocalDensity.current.fontScale)
                CompositionLocalProvider(LocalDensity provides canvas) {
                    PlayerScreen()
                }
            }
        }
        root.addView(controls, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        // Only the root behind the surface paints black; the window and Compose stay transparent.
        window.setBackgroundDrawable(null)
        DiagLog.i("fnos video attached: SurfaceView below transparent controls")
    }

    /** The picture's own shape on screen: the surface is what the film is fitted to. */
    private fun fitVideo() {
        val size = surfaceSize(root.width, root.height, videoAspect, aspectMode) ?: return
        val params = surface.layoutParams as FrameLayout.LayoutParams
        if (params.width != size.width || params.height != size.height) {
            params.width = size.width
            params.height = size.height
            surface.layoutParams = params
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (client == null) return
        prepare()
    }

    /** Resolves what is not resolved yet, then starts the player; a retry runs this again. */
    private fun prepare() {
        val c = client ?: return
        prepareJob?.cancel()
        prepareJob = lifecycleScope.launch {
            try {
                if (!resolved) {
                    val info = c.playInfoAsync(item.guid)
                    mediaGuid = info.mediaGuid
                    require(mediaGuid.isNotEmpty()) { "play/info has no media guid" }
                    resumeMs = info.tsSec.coerceAtLeast(0) * 1000
                    durationMs = info.durationSec.coerceAtLeast(0) * 1000
                    subtitleGuid = info.subtitleGuid
                    if (info.tvTitle.isNotBlank()) {
                        item = item.copy(tvTitle = info.tvTitle, season = info.season, episode = info.episode)
                    }
                    resolved = true
                }
                if (foreground) startPlayer(fresh = true)
                loadEpisodes()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                DiagLog.e("fnos playback preparation failed", error)
                failResolution(error)
            }
        }
    }

    /** What a failed resolve is told as: the NAS's answer, the session, or the network. */
    private fun failResolution(error: Exception) {
        when {
            error !is FnosException -> fail(getString(R.string.fnos_offline), allowRetry = true)
            PlaybackFailures.classifyServer(error.code) == FailureAction.SignIn ->
                fail(getString(R.string.player_signin), allowRetry = false)
            error.code < 0 -> fail(getString(R.string.fnos_offline), allowRetry = true)
            else -> fail(
                getString(R.string.player_server, error.code, error.detail ?: error.message.orEmpty()),
                allowRetry = false,
            )
        }
    }

    /** Builds the player; the software-decoder retry is the same player on a filtered codec list. */
    private fun buildPlayer(): ExoPlayer {
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
            .build()
        val builder = ExoPlayer.Builder(this)
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(FnosDataSource.factory(token)))
        if (softwareOnly) {
            // Software only, and no decoder fallback: a fallback would take the hardware decoder
            // back, which is the one that just refused the file.
            builder.setRenderersFactory(
                DefaultRenderersFactory(this)
                    .setMediaCodecSelector(SoftwareDecoders.SELECTOR)
                    .setEnableDecoderFallback(false),
            )
        }
        return builder.build()
    }

    private fun startPlayer(fresh: Boolean) {
        check(player == null)
        if (fresh) {
            retries = 0
            languageApplied = false
        }
        ready = false
        rendered = false
        userSeeked = false
        state = PlayerPhase.Loading
        val p = buildPlayer()
        player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        p.duration.takeIf { it > 0 }?.let { durationMs = it }
                        state = PlayerPhase.Playing
                        if (!ready) {
                            // The server resume wins over the stale shelf card, once per instance.
                            ready = true
                            if (resumeMs > 0 && !userSeeked) {
                                val target = clampPosition(resumeMs)
                                p.seekTo(target)
                                notice = formatClock(target)
                            }
                            p.playWhenReady = shouldPlay && resumed
                            upNext = null
                            startRecording()
                        }
                        applyPreferredTracks()
                        if (stream == null) loadStream()
                        updatePosition()
                        DiagLog.i("fnos READY at ${p.currentPosition} duration=${p.duration}")
                    }
                    Player.STATE_BUFFERING -> state = PlayerPhase.Buffering
                    Player.STATE_ENDED -> {
                        savePosition()
                        val next = Episodes.next(episodes, item)
                        if (next != null) playEpisode(next) else finish()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                scheduleHide()
            }

            override fun onRenderedFirstFrame() {
                rendered = true
                DiagLog.i("fnos first frame at ${p.currentPosition}; surface=${surface.width}x${surface.height} valid=${surface.holder.surface.isValid}")
                savePosition()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                DiagLog.i("fnos video size ${videoSize.width}x${videoSize.height} pixelRatio=${videoSize.pixelWidthHeightRatio}")
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    fitVideo()
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }

            override fun onTracksChanged(tracks: Tracks) {
                this@PlayerActivity.tracks = tracks
                applyPreferredTracks()
            }

            override fun onPlayerError(error: PlaybackException) {
                // Decoder allocation failures are not evidence of an obscured surface. Keep the cause.
                DiagLog.e("fnos playback error ${error.errorCodeName}", error)
                handleFailure(error)
            }
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                DiagLog.i("fnos video decoder $decoderName initialized in ${initializationDurationMs}ms")
            }

            override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
                DiagLog.e("fnos video codec error", videoCodecError)
            }
        })
        p.setVideoSurfaceView(surface)
        p.setMediaItem(MediaItem.fromUri(sourceUrl ?: client!!.playUrl(mediaGuid)))
        p.setPlaybackSpeed(speed)
        p.prepare()
    }

    /** The retry policy in action: what the failure gets, and what the screen then says. */
    private fun handleFailure(error: PlaybackException) {
        val action = if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
            PlaybackFailures.classifyHttp(httpStatusOf(error) ?: 0)
        } else {
            PlaybackFailures.classify(error.errorCode)
        }
        when (action) {
            FailureAction.Retry -> {
                val backoff = PlaybackFailures.retryDelayMs(retries)
                if (backoff == null) {
                    fail(getString(R.string.fnos_offline), allowRetry = true)
                } else {
                    retries++
                    restart(backoff, null)
                }
            }
            FailureAction.SoftwareDecode -> if (!softwareOnly) {
                softwareOnly = true
                restart(0L, getString(R.string.player_software))
            } else {
                fail(getString(R.string.player_decoder), allowRetry = true)
            }
            FailureAction.SignIn -> fail(getString(R.string.player_signin), allowRetry = false)
            FailureAction.Report -> fail(
                getString(R.string.player_error, error.errorCodeName), allowRetry = true,
            )
        }
    }

    /** The HTTP status behind a playback error, when the data source carried one. */
    private fun httpStatusOf(error: Throwable?): Int? {
        var cause = error
        while (cause != null) {
            if (cause is FnosHttpException) return cause.status
            cause = cause.cause
        }
        return null
    }

    /** Starts the same media again from where it stood, after [delayMs], with a line on screen. */
    private fun restart(delayMs: Long, line: String?) {
        resumeMs = (player?.currentPosition ?: resumeMs).coerceAtLeast(0)
        // The position is ours to keep, not the viewer's: the READY path seeks back to it.
        userSeeked = false
        releasePlayer()
        state = PlayerPhase.Buffering
        status = line
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            if (delayMs > 0) delay(delayMs)
            status = null
            if (foreground && player == null && client != null && mediaGuid.isNotEmpty()) startPlayer(fresh = false)
        }
    }

    /** The failure screen: what happened, and whether the remote can ask for it again. */
    private fun fail(text: String, allowRetry: Boolean) {
        DiagLog.i("fnos failure: $text retry=$allowRetry")
        message = text
        canRetry = allowRetry
        state = PlayerPhase.Failed
        retryJob?.cancel()
        // The failure is the whole screen: no panel, no bar, no "up next" plate underneath it.
        menu = null
        controlsVisible = false
        upNext = null
        status = null
        releasePlayer()
    }

    /** Retries by hand: the automatic budgets are cleared, and the hardware decoder is tried again. */
    private fun retry() {
        if (!canRetry || !foreground) return
        canRetry = false
        retries = 0
        softwareOnly = false
        status = null
        releasePlayer()
        if (!resolved) {
            prepare()
            return
        }
        resumeMs = resumeMs.coerceAtLeast(0)
        state = PlayerPhase.Buffering
        startPlayer(fresh = true)
    }

    /** The quality menu's data: the file's tracks and the steps the server offers for it. */
    private fun loadStream() {
        val c = client ?: return
        val media = mediaGuid
        if (stream != null || media.isEmpty()) return
        lifecycleScope.launch {
            try {
                val fetched = c.streamAsync(media)
                stream = fetched
                qualities = fetched.qualities
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                DiagLog.e("fnos stream failed", error)
            }
        }
    }

    /**
     * Asks the server to transcode [quality] and plays what it answers, keeping the position.
     *
     * The server currently answers `code 1001 System busy` to every combination Aerial sends, so a
     * refusal is shown as the server's own words and the menu's steps go disabled — the switch is
     * never pretended.
     */
    private fun submitQuality(quality: FnosQuality) {
        val c = client ?: return
        val s = stream ?: return
        val p = player ?: return
        if (switching || qualityError != null) return
        val at = p.currentPosition.coerceAtLeast(0)
        switching = true
        status = getString(R.string.player_switching)
        lifecycleScope.launch {
            try {
                val link = c.playPlayAsync(
                    FnosTranscode(
                        mediaGuid = mediaGuid,
                        videoGuid = s.videoGuid,
                        videoEncoder = s.videoCodec,
                        resolution = quality.resolution,
                        bitrate = quality.bitrate,
                        startTimestampSec = at / 1000,
                        audioGuid = s.audioGuids.firstOrNull().orEmpty(),
                        audioEncoder = s.audioCodecs.firstOrNull().orEmpty(),
                        channels = s.audioChannels,
                        subtitleGuid = subtitlePick(),
                    ),
                )
                switching = false
                status = null
                switchSource(link, quality, at)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                switching = false
                status = null
                qualityError = if (error is FnosException) {
                    error.detail?.ifEmpty { error.message.orEmpty() } ?: error.message.orEmpty()
                } else {
                    error.message.orEmpty()
                }
                DiagLog.e("fnos quality switch failed", error)
            }
        }
    }

    /** Plays the link the server answered with, from the same place, or says why it cannot. */
    private fun switchSource(link: FnosPlayLink, quality: FnosQuality, at: Long) {
        if (link.link.isEmpty()) {
            qualityError = getString(R.string.player_quality_nolink)
            return
        }
        if (link.isHls) {
            // The transcoded stream is HLS, and this build has no HLS module: say so rather than
            // hand ExoPlayer a manifest it cannot read.
            qualityError = getString(R.string.player_quality_hls)
            return
        }
        sourceUrl = client!!.playLinkUrl(link.link)
        currentResolution = quality.resolution
        resumeMs = at
        userSeeked = false
        releasePlayer()
        state = PlayerPhase.Loading
        startPlayer(fresh = false)
        notice = null
    }

    /** The subtitle guid `play/play` and `play/record` want: the file's, or none when off. */
    private fun subtitlePick(): String =
        if (textOff()) FnosClient.NO_SUBTITLE else subtitleGuid ?: FnosClient.NO_SUBTITLE

    private fun startRecording() {
        recordJob?.cancel()
        recordJob = lifecycleScope.launch {
            while (true) {
                delay(15_000)
                savePosition()
            }
        }
    }

    private fun savePosition() {
        val p = player ?: return
        // Do not replace a real server resume with zero during preparation or failed decoder init.
        if (!ready || !rendered || mediaGuid.isEmpty() || p.playbackState == Player.STATE_IDLE) return
        val record = Record(
            item, mediaGuid, p.currentPosition.coerceAtLeast(0) / 1000, durationMs.coerceAtLeast(0) / 1000,
            currentResolution, subtitlePick(),
        )
        if (record == lastRecord) return
        if (records.trySend(record).isSuccess) lastRecord = record
    }

    override fun onPause() {
        resumed = false
        player?.let { p ->
            if (ready) shouldPlay = p.playWhenReady
            savePosition()
            p.pause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        if (ready && shouldPlay) player?.play()
    }

    override fun onStop() {
        foreground = false
        prepareJob?.cancel()
        recordJob?.cancel()
        hideJob?.cancel()
        retryJob?.cancel()
        flashJob?.cancel()
        seekHint = null
        seekPace.reset()
        backAt = 0L
        player?.let { p ->
            savePosition()
            if (ready) resumeMs = p.currentPosition.coerceAtLeast(0)
        }
        releasePlayer()
        DiagLog.i("fnos player released at $resumeMs")
        super.onStop()
    }

    override fun onDestroy() {
        records.close()
        super.onDestroy()
    }

    /** Releases the player and the decoder behind it; the position is the caller's to keep. */
    private fun releasePlayer() {
        recordJob?.cancel()
        player?.let { p ->
            p.clearVideoSurfaceView(surface)
            p.release()
        }
        player = null
        ready = false
        playing = false
        cues = emptyList()
        tracks = Tracks.EMPTY
        languageApplied = false
    }

    // --- The episode chain ---------------------------------------------------------------------

    /**
     * The show's episodes, asked for once and only for a show: a film's item carries no season, and
     * the menu and autoplay then keep the old behaviour of finishing at the end.
     */
    private fun loadEpisodes() {
        val c = client ?: return
        if (episodesLoaded || parentGuid.isEmpty() || !resolved) return
        episodesLoaded = true
        lifecycleScope.launch {
            try {
                episodes = c.episodeListAsync(parentGuid)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Let the next start try again rather than keep a show without its episodes.
                episodesLoaded = false
                DiagLog.e("fnos episode list failed", error)
            }
        }
    }

    /** Switches to [episode] of the same show, carrying the position it was left at. */
    private fun playEpisode(episode: FnosPlayItem) {
        savePosition()
        upNext = episodeTitle(episode)
        releasePlayer()
        item = item.copy(
            guid = episode.guid,
            title = episode.title,
            season = episode.season,
            episode = episode.episode,
            tsSec = episode.tsSec,
        )
        mediaGuid = ""
        resolved = false
        stream = null
        qualities = emptyList()
        qualityError = null
        currentResolution = "original"
        sourceUrl = null
        softwareOnly = false
        controlsVisible = false
        DiagLog.i("fnos next episode ${episode.guid} S${episode.season}E${episode.episode}")
        prepare()
    }

    /** "S1 E3  Title" for the menus, or the guid when the NAS gave neither. */
    private fun episodeTitle(episode: FnosPlayItem): String {
        val number = if (episode.season > 0 && episode.episode > 0) {
            getString(R.string.fnos_episode, episode.season, episode.episode)
        } else {
            ""
        }
        return listOf(number, episode.title.ifBlank { episode.tvTitle })
            .filter { it.isNotBlank() }
            .joinToString("  ")
            .ifBlank { episode.guid }
    }

    // --- Tracks --------------------------------------------------------------------------------

    /** One selectable track of the media playing now. */
    private data class TrackEntry(val group: Tracks.Group, val index: Int, val label: String, val language: String) {
        /** What a preference stores for this track: its language, or its name when it has none. */
        val key: String get() = language.ifEmpty { "label:$label" }
    }

    private fun trackEntries(type: Int): List<TrackEntry> = tracks.getGroups()
        .filter { it.getType() == type }
        .flatMap { group ->
            (0 until group.length).map { index ->
                val format = group.getTrackFormat(index)
                val label = format.label?.takeIf { it.isNotBlank() }
                    ?: format.language?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.player_track, index + 1)
                TrackEntry(group, index, label, format.language.orEmpty())
            }
        }

    /** Puts a title's remembered tracks back, once the media's own tracks are known. */
    private fun applyPreferredTracks() {
        val p = player ?: return
        if (languageApplied || tracks.getGroups().isEmpty()) return
        languageApplied = true
        val title = titleKey()
        val params = p.trackSelectionParameters.buildUpon()
        PlayerPrefs.language(this, PlayerPrefs.AUDIO, title)?.let { key ->
            trackEntries(C.TRACK_TYPE_AUDIO).firstOrNull { it.key == key }?.let { params.select(it, C.TRACK_TYPE_AUDIO) }
        }
        val text = PlayerPrefs.language(this, PlayerPrefs.TEXT, title)
        when (text) {
            null -> Unit
            PlayerPrefs.OFF -> params.textOff()
            else -> trackEntries(C.TRACK_TYPE_TEXT).firstOrNull { it.key == text }
                ?.let { params.select(it, C.TRACK_TYPE_TEXT) }
        }
        p.trackSelectionParameters = params.build()
    }

    private fun TrackSelectionParameters.Builder.select(entry: TrackEntry, type: Int) {
        setTrackTypeDisabled(type, false)
        setOverrideForType(TrackSelectionOverride(entry.group.getMediaTrackGroup(), entry.index))
    }

    private fun TrackSelectionParameters.Builder.textOff() {
        clearOverridesOfType(C.TRACK_TYPE_TEXT)
        setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    }

    private fun textOff(): Boolean =
        player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true

    /** The key a title's track choices are remembered under. */
    private fun titleKey(): String = item.tvTitle.ifBlank { item.title }.ifBlank { item.guid }

    // --- Menus ---------------------------------------------------------------------------------

    private fun panelFor(kind: MenuKind): PanelMenu = when (kind) {
        MenuKind.Audio -> PanelMenu(
            R.string.player_audio,
            trackEntries(C.TRACK_TYPE_AUDIO)
                .map { MenuEntry(it.label, current = it.group.isTrackSelected(it.index)) }
                .ifEmpty { listOf(MenuEntry(getString(R.string.player_empty_audio), enabled = false)) },
        )

        MenuKind.Subtitles -> PanelMenu(
            R.string.player_subtitles,
            buildList {
                val off = textOff()
                add(MenuEntry(getString(R.string.player_off), current = off))
                val entries = trackEntries(C.TRACK_TYPE_TEXT)
                entries.forEach { add(MenuEntry(it.label, current = !off && it.group.isTrackSelected(it.index))) }
                if (entries.isEmpty()) {
                    add(MenuEntry(getString(R.string.player_empty_text), enabled = false))
                }
            },
        )

        MenuKind.Episodes -> PanelMenu(
            R.string.player_episodes,
            episodes.map { MenuEntry(episodeTitle(it), current = it.guid == item.guid) }
                .ifEmpty { listOf(MenuEntry(getString(R.string.player_empty_episodes), enabled = false)) },
        )

        MenuKind.Speed -> PanelMenu(
            R.string.player_speed,
            SPEEDS.map { MenuEntry(speedLabel(it), current = it == speed) },
        )

        MenuKind.Quality -> PanelMenu(
            R.string.player_quality,
            qualities.map {
                MenuEntry(
                    "${it.resolution}  ${it.bitrate / 1_000_000} Mbps",
                    current = it.resolution == currentResolution,
                    enabled = qualityError == null,
                )
            }.ifEmpty { listOf(MenuEntry(getString(R.string.player_empty_quality), enabled = false)) },
            note = qualityError,
        )

        MenuKind.Aspect -> PanelMenu(
            R.string.player_aspect,
            ASPECTS.map { MenuEntry(getString(aspectLabel(it)), current = it == aspectMode) },
        )

        MenuKind.Danmaku -> PanelMenu(
            R.string.player_danmaku,
            DanmakuRow.entries.map { row ->
                MenuEntry(
                    label = danmakuRowLabel(row),
                    current = when (row) {
                        DanmakuRow.On -> danmuOn()
                        DanmakuRow.Off -> !danmuOn()
                        else -> false
                    },
                )
            },
        )

        MenuKind.DanmakuArea -> PanelMenu(
            R.string.danmu_area,
            DANMAKU_AREAS.map {
                MenuEntry(getString(R.string.danmu_area_value, it), current = it == DanmuPrefs.area)
            },
        )

        MenuKind.DanmakuSize -> PanelMenu(
            R.string.danmu_size,
            DANMAKU_SIZES.map { MenuEntry("$it", current = it == DanmuPrefs.size) },
        )

        MenuKind.DanmakuOpacity -> PanelMenu(
            R.string.danmu_opacity,
            DANMAKU_OPACITIES.map {
                MenuEntry(getString(R.string.danmu_area_value, it), current = it == opacityPercent())
            },
        )

        MenuKind.DanmakuSpeed -> PanelMenu(
            R.string.danmu_speed,
            SPEEDS.map { MenuEntry(speedLabel(it), current = it == DanmuPrefs.speedMul) },
        )
    }

    /** Opens the focused button's panel, with the choice now playing under the focus. */
    private fun openPanel() {
        val kind = barFocus
        val entries = panelFor(kind).entries
        menu = kind
        menuIndex = (entries.indexOfFirst { it.current }.takeIf { it >= 0 } ?: 0)
            .coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        Sounds.select()
        wake()
    }

    /** Takes the focused row of the open panel. */
    private fun chooseEntry() {
        val kind = menu ?: return
        val entry = panelFor(kind).entries.getOrNull(menuIndex)
        if (entry == null || !entry.enabled) {
            Sounds.limit()
            return
        }
        Sounds.select()
        when (kind) {
            MenuKind.Audio -> trackEntries(C.TRACK_TYPE_AUDIO).getOrNull(menuIndex)
                ?.let { selectTrack(it, C.TRACK_TYPE_AUDIO) }
            MenuKind.Subtitles -> if (menuIndex == 0) {
                setTextOff()
            } else {
                trackEntries(C.TRACK_TYPE_TEXT).getOrNull(menuIndex - 1)?.let { selectTrack(it, C.TRACK_TYPE_TEXT) }
            }
            MenuKind.Episodes -> episodes.getOrNull(menuIndex)?.let {
                if (it.guid != item.guid) {
                    menu = null
                    playEpisode(it)
                    return
                }
            }
            MenuKind.Speed -> SPEEDS.getOrNull(menuIndex)?.let { applySpeed(it) }
            MenuKind.Quality -> qualities.getOrNull(menuIndex)?.let { submitQuality(it) }
            MenuKind.Aspect -> ASPECTS.getOrNull(menuIndex)?.let { setAspect(it) }
            // The danmaku panel's own rows change a setting and leave the panel open: a viewer
            // tuning the layer sees the picture behind it change without reopening the panel.
            MenuKind.Danmaku -> {
                chooseDanmakuRow(DanmakuRow.entries.getOrNull(menuIndex) ?: return)
                wake()
                return
            }
            MenuKind.DanmakuArea -> {
                DANMAKU_AREAS.getOrNull(menuIndex)?.let { DanmuPrefs.area = it }
                wake()
                return
            }
            MenuKind.DanmakuSize -> {
                DANMAKU_SIZES.getOrNull(menuIndex)?.let { DanmuPrefs.size = it }
                wake()
                return
            }
            MenuKind.DanmakuOpacity -> {
                DANMAKU_OPACITIES.getOrNull(menuIndex)?.let { DanmuPrefs.alpha = it / 100f }
                wake()
                return
            }
            MenuKind.DanmakuSpeed -> {
                SPEEDS.getOrNull(menuIndex)?.let { DanmuPrefs.speedMul = it }
                wake()
                return
            }
        }
        menu = null
        wake()
    }

    /** One row of the danmaku panel: a switch, a mode toggle, or the page that holds its values. */
    private fun chooseDanmakuRow(row: DanmakuRow) {
        when (row) {
            DanmakuRow.On -> setDanmaku(true)
            DanmakuRow.Off -> setDanmaku(false)
            DanmakuRow.Scroll -> DanmuPrefs.scrollEnabled = !DanmuPrefs.scrollEnabled
            DanmakuRow.Top -> DanmuPrefs.topEnabled = !DanmuPrefs.topEnabled
            DanmakuRow.Bottom -> DanmuPrefs.bottomEnabled = !DanmuPrefs.bottomEnabled
            DanmakuRow.Reverse -> DanmuPrefs.reverseEnabled = !DanmuPrefs.reverseEnabled
            DanmakuRow.Area, DanmakuRow.Size, DanmakuRow.Opacity, DanmakuRow.Speed ->
                row.page?.let { openDanmakuPage(it) }
        }
    }

    /** Opens a value page of the danmaku panel on the choice nearest what is set now. */
    private fun openDanmakuPage(kind: MenuKind) {
        menu = kind
        menuIndex = when (kind) {
            MenuKind.DanmakuArea -> nearestIndex(DANMAKU_AREAS, DanmuPrefs.area)
            MenuKind.DanmakuSize -> nearestIndex(DANMAKU_SIZES, DanmuPrefs.size)
            MenuKind.DanmakuOpacity -> nearestIndex(DANMAKU_OPACITIES, opacityPercent())
            MenuKind.DanmakuSpeed -> SPEEDS.indices.minByOrNull { abs(SPEEDS[it] - DanmuPrefs.speedMul) } ?: 0
            else -> 0
        }
    }

    private fun nearestIndex(choices: List<Int>, value: Int): Int =
        choices.indices.minByOrNull { abs(choices[it] - value) } ?: 0

    /** A row of the danmaku panel: the setting's name, and the value in force. */
    private fun danmakuRowLabel(row: DanmakuRow): String {
        val name = getString(row.label)
        return when (row) {
            DanmakuRow.On, DanmakuRow.Off -> name
            DanmakuRow.Scroll -> settingValue(name, onOff(DanmuPrefs.scrollEnabled))
            DanmakuRow.Top -> settingValue(name, onOff(DanmuPrefs.topEnabled))
            DanmakuRow.Bottom -> settingValue(name, onOff(DanmuPrefs.bottomEnabled))
            DanmakuRow.Reverse -> settingValue(name, onOff(DanmuPrefs.reverseEnabled))
            DanmakuRow.Area -> settingValue(name, getString(R.string.danmu_area_value, DanmuPrefs.area))
            DanmakuRow.Size -> settingValue(name, "${DanmuPrefs.size}")
            DanmakuRow.Opacity -> settingValue(name, getString(R.string.danmu_area_value, opacityPercent()))
            DanmakuRow.Speed -> settingValue(name, speedLabel(DanmuPrefs.speedMul))
        }
    }

    private fun settingValue(name: String, value: String): String =
        getString(R.string.danmu_setting_value, name, value)

    private fun onOff(on: Boolean): String = getString(if (on) R.string.player_on else R.string.player_off)

    /** The layer's opacity as the whole percent its panel shows. */
    private fun opacityPercent(): Int = (DanmuPrefs.alpha * 100).roundToInt()

    private fun selectTrack(entry: TrackEntry, type: Int) {
        val p = player ?: return
        val params = p.trackSelectionParameters.buildUpon()
        params.select(entry, type)
        p.trackSelectionParameters = params.build()
        PlayerPrefs.setLanguage(
            this,
            if (type == C.TRACK_TYPE_AUDIO) PlayerPrefs.AUDIO else PlayerPrefs.TEXT,
            titleKey(),
            entry.key,
        )
    }

    private fun setTextOff() {
        val p = player ?: return
        val params = p.trackSelectionParameters.buildUpon()
        params.textOff()
        p.trackSelectionParameters = params.build()
        PlayerPrefs.setLanguage(this, PlayerPrefs.TEXT, titleKey(), PlayerPrefs.OFF)
    }

    private fun applySpeed(value: Float) {
        speed = value
        PlayerPrefs.setSpeed(this, value)
        player?.setPlaybackSpeed(value)
    }

    private fun setAspect(mode: AspectMode) {
        aspectMode = mode
        PlayerPrefs.setAspect(this, mode)
        fitVideo()
    }

    private fun setDanmaku(on: Boolean) {
        DanmuPrefs.enabled = on
    }

    /** Whether the danmaku layer is on: `DanmuPrefs` owns the switch, the player only reads it. */
    private fun danmuOn(): Boolean = DanmuPrefs.enabled

    // --- Keys ----------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            DiagLog.i("fnos key $code bar=$controlsVisible focus=$barFocus menu=$menu position=${player?.currentPosition}")
        }
        // Volume and mute belong to the TV: the system handles them, and they never wake the bar.
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN ||
            code == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            return super.dispatchKeyEvent(event)
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU -> Unit
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.isCanceled) return true
        if (state == PlayerPhase.Failed) {
            if (code == KeyEvent.KEYCODE_BACK) {
                finish()
            } else if (event.repeatCount == 0) {
                retry()
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.repeatCount == 0) onBack()
            return true
        }
        if (code != KeyEvent.KEYCODE_BACK) backAt = 0L
        val media = code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        // Arrows seek while the bar is down, as the fnOS TV player does: a viewer who taps right
        // twice to skip the titles must not land in the button row on the second tap.
        val arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        when {
            media -> seekKey(event, code)
            arrow && !controlsVisible -> seekKey(event, code)
            controlsVisible -> navigate(code)
            else -> seekKey(event, code)
        }
        return true
    }

    /** The picture's own keys: the seek ladder, play/pause, and the bar's own opener. */
    private fun seekKey(event: KeyEvent, code: Int) {
        val p = player
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> p?.let { nudge(it, -1) }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> p?.let { nudge(it, 1) }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU -> if (event.repeatCount == 0) wake()
            KeyEvent.KEYCODE_DPAD_DOWN -> Unit
            else -> if (p != null && event.repeatCount == 0) {
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> p.play()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> p.pause()
                    else -> if (p.playWhenReady) p.pause() else p.play()
                }
                shouldPlay = p.playWhenReady
                if (!shouldPlay) savePosition()
                Sounds.select()
                wake()
            }
        }
    }

    /**
     * One press of the seek ladder: the picture moves and a small hint says how far, but the control
     * bar stays down. Opening it here is what used to turn the second press of right into a move
     * between buttons instead of another skip.
     */
    private fun nudge(p: ExoPlayer, direction: Int) {
        val step = seekPace.step(direction)
        userSeeked = true
        p.seekTo(clampPosition(p.currentPosition + step))
        DiagLog.i("fnos seek step=$step target=${p.currentPosition} bar=$controlsVisible")
        updatePosition()
        Sounds.focusMoved()
        flashSeek(step)
    }

    /** The "+30 s" plate the picture shows while seeking, cleared once the burst ends. */
    private fun flashSeek(stepMs: Long) {
        val text = getString(R.string.player_seek_hint, abs(stepMs / 1000).toInt())
        seekHint = (if (stepMs > 0) "▶ " else "◀ ") + text
        flashJob?.cancel()
        flashJob = lifecycleScope.launch {
            delay(SEEK_HINT_MS)
            seekHint = null
            seekPace.reset()
        }
    }

    /** The bar's keys: D-pad moves the focus, OK chooses, and Back closes a panel or the bar. */
    private fun navigate(code: Int) {
        val open = menu
        if (open != null) {
            val size = panelFor(open).entries.size
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> stepMenu(-1, size)
                KeyEvent.KEYCODE_DPAD_DOWN -> stepMenu(1, size)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> chooseEntry()
                else -> Unit
            }
            return
        }
        val bar = playerBar(episodes.isNotEmpty())
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> stepBar(bar, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> stepBar(bar, 1)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> openPanel()
            // Down and Menu both put the bar away, as the fnOS player's Menu does.
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> {
                controlsVisible = false
                hideJob?.cancel()
                notice = null
            }
            else -> Unit
        }
    }

    private fun stepBar(bar: List<MenuKind>, delta: Int) {
        val at = bar.indexOf(barFocus).coerceAtLeast(0)
        val next = (at + delta).coerceIn(0, (bar.size - 1).coerceAtLeast(0))
        if (next == at) Sounds.limit() else {
            barFocus = bar[next]
            Sounds.focusMoved()
        }
        wake()
    }

    private fun stepMenu(delta: Int, size: Int) {
        val next = (menuIndex + delta).coerceIn(0, (size - 1).coerceAtLeast(0))
        if (next == menuIndex) Sounds.limit() else {
            menuIndex = next
            Sounds.focusMoved()
        }
        wake()
    }

    private fun onBack() {
        val open = menu
        if (open != null) {
            // Back from one of the danmaku settings pages lands on the row that opened it, so a
            // second press closes the panel and a third puts the bar away.
            val row = danmakuRowOf(open)
            menu = if (row != null) MenuKind.Danmaku else null
            menuIndex = row?.ordinal ?: 0
            Sounds.select()
            wake()
            return
        }
        if (controlsVisible) {
            controlsVisible = false
            hideJob?.cancel()
            notice = null
            return
        }
        // As the fnOS TV player does: one press arms the exit and a second inside two seconds
        // takes it, so a viewer who wanted the bar back does not leave the film by accident.
        val now = System.currentTimeMillis()
        if (now - backAt <= BACK_ARM_MS) {
            finish()
        } else {
            backAt = now
            // The plate lives outside the bar, so the prompt is visible with the bar down too.
            seekHint = getString(R.string.player_press_again)
            flashJob?.cancel()
            flashJob = lifecycleScope.launch {
                delay(BACK_ARM_MS)
                seekHint = null
            }
        }
    }

    // --- Playing -------------------------------------------------------------------------------

    private fun clampPosition(position: Long): Long =
        position.coerceIn(0, player?.duration?.takeIf { it > 0 } ?: durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)

    private fun updatePosition() {
        player?.let { positionMs = it.currentPosition.coerceAtLeast(0) }
    }

    private fun seekTo(position: Long) {
        val p = player ?: return
        userSeeked = true
        p.seekTo(clampPosition(position))
        updatePosition()
        Sounds.focusMoved()
        wake()
    }

    private fun wake() {
        flashJob?.cancel()
        seekHint = null
        seekPace.reset()
        controlsVisible = true
        updatePosition()
        scheduleHide()
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        // A menu on screen is a screen the viewer is reading: it stays until Back.
        if (!controlsVisible || !playing || menu != null || switching) return
        hideJob = lifecycleScope.launch {
            delay(BAR_MS)
            controlsVisible = false
            notice = null
        }
    }

    @Composable
    private fun PlayerScreen() {
        // No opaque full-screen Compose draw: the SurfaceView punches through this window.
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { wake() } }) {
            val frame = remember(maxWidth, maxHeight, videoAspect, aspectMode) {
                surfaceSize(maxWidth.value.roundToInt(), maxHeight.value.roundToInt(), videoAspect, aspectMode)
                    ?.let { VideoFrame(it.width.dp, it.height.dp) }
            }
            if (danmuOn()) {
                // What the layer must not cover: the control bar while it is up, the subtitles
                // while they are, and otherwise just the picture's own edge. The subtitles sit
                // inside the fitted picture, so their inset carries the letterbox under it.
                val danmuBottom = when {
                    controlsVisible -> 180.dp
                    cues.isNotEmpty() -> frame?.let {
                        (maxHeight - it.height) / 2 + it.height * 0.05f + 82.dp
                    } ?: 82.dp
                    else -> 12.dp
                }
                DanmakuOverlay(
                    title = item.tvTitle.ifBlank { item.title },
                    episode = item.episode,
                    positionMs = positionMs,
                    playing = playing,
                    playbackSpeed = speed,
                    bottomInsetDp = danmuBottom,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            frame?.let { SubtitleLayer(cues, it, Modifier.align(Alignment.Center)) }
            val ui = PlayerOverlayState(
                phase = state,
                title = item.tvTitle.ifBlank { item.title },
                subtitleLine = if (item.season > 0 && item.episode > 0) {
                    stringResource(R.string.fnos_episode, item.season, item.episode)
                } else {
                    null
                },
                positionMs = positionMs,
                durationMs = durationMs,
                notice = notice,
                upNext = upNext,
                failure = message,
                canRetry = canRetry,
                status = status,
                bar = playerBar(episodes.isNotEmpty()),
                barFocus = barFocus,
                panel = menu?.let { panelFor(it) },
                menuIndex = menuIndex,
                seekHint = seekHint,
            )
            StatusLayer(ui)
            if (controlsVisible) ControlBar(ui, onSeek = { fraction -> if (durationMs > 0) seekTo((durationMs * fraction).toLong()) })
        }
    }

    companion object {
        private const val BAR_MS = 3_000L
        private const val TICK_MS = 500L
        private const val DANMAKU_TICK_MS = 100L

        /** How long the seek plate stays after the last press of a burst. */
        private const val SEEK_HINT_MS = 1_500L

        /** The window in which a second Back leaves the film, as the fnOS TV player uses. */
        private const val BACK_ARM_MS = 2_000L
        private const val EXTRA_GUID = "guid"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TS = "ts"
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"
        private const val EXTRA_PARENT = "parent"

        fun intent(context: Context, item: FnosPlayItem, @Suppress("UNUSED_PARAMETER") bounds: Rect?): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_GUID, item.guid)
                .putExtra(EXTRA_TITLE, item.tvTitle.ifBlank { item.title })
                .putExtra(EXTRA_TS, item.tsSec)
                .putExtra(EXTRA_SEASON, item.season)
                .putExtra(EXTRA_EPISODE, item.episode)
                .putExtra(EXTRA_PARENT, item.parentGuid)
    }
}

internal fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}