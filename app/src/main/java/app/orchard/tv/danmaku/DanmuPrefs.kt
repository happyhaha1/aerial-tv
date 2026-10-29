package app.orchard.tv.danmaku

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import app.orchard.tv.fnos.FnosStore

/**
 * What the danmaku layer is set to: the switch, which of the comment modes it draws, the service
 * address, and the drawing numbers of `docs/DANMAKU.md` §7. The values are Compose state, so the
 * player redraws when the settings screen or its own panel changes one.
 *
 * Reads before [init] answer the defaults, and writes before it change memory only: no path here
 * may throw because a screen ran in another order. Everything is clamped to the documented ranges,
 * so a text field that saves `999` cannot reach the lanes.
 */
object DanmuPrefs {
    private const val PREFS = "danmu"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SERVER = "server"
    private const val KEY_SIZE = "size"
    private const val KEY_ALPHA = "alpha"
    private const val KEY_AREA = "area"
    private const val KEY_SPEED = "speed"
    private const val KEY_SCROLL = "scroll"
    private const val KEY_TOP = "top"
    private const val KEY_BOTTOM = "bottom"
    private const val KEY_REVERSE = "reverse"

    /** The port a DanDanPlay-compatible service listens on when only the NAS address is known. */
    const val PORT = 9321

    const val MIN_SIZE = 14
    const val MAX_SIZE = 36

    /** Percent of the canvas height the lanes may use. */
    const val MIN_AREA = 20
    const val MAX_AREA = 100

    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 2f
    const val MIN_ALPHA = 0.2f
    const val MAX_ALPHA = 1f

    private const val DEFAULT_SIZE = 22
    private const val DEFAULT_AREA = 35
    private const val DEFAULT_SPEED = 1f
    private const val DEFAULT_ALPHA = 0.9f

    private var started = false
    private var prefs: SharedPreferences? = null

    /** Whether the player draws danmaku at all. */
    private val enabledState = mutableStateOf(false)
    var enabled: Boolean
        get() = enabledState.value
        set(value) {
            enabledState.value = value
            put { putBoolean(KEY_ENABLED, value) }
        }

    /** Whether the layer draws the scrolling comments (the format's modes 1 and 6). */
    private val scrollState = mutableStateOf(true)
    var scrollEnabled: Boolean
        get() = scrollState.value
        set(value) {
            scrollState.value = value
            put { putBoolean(KEY_SCROLL, value) }
        }

    /** Whether the layer draws the comments pinned to the top of the picture (mode 5). */
    private val topState = mutableStateOf(true)
    var topEnabled: Boolean
        get() = topState.value
        set(value) {
            topState.value = value
            put { putBoolean(KEY_TOP, value) }
        }

    /** Whether the layer draws the comments pinned to the bottom of the picture (mode 4). */
    private val bottomState = mutableStateOf(true)
    var bottomEnabled: Boolean
        get() = bottomState.value
        set(value) {
            bottomState.value = value
            put { putBoolean(KEY_BOTTOM, value) }
        }

    /** Whether the layer draws the right-to-left scrolling comments (the format's mode 6). */
    private val reverseState = mutableStateOf(true)
    var reverseEnabled: Boolean
        get() = reverseState.value
        set(value) {
            reverseState.value = value
            put { putBoolean(KEY_REVERSE, value) }
        }

    /** The service base URL; empty means "the NAS host, on [PORT]". */
    private val serverState = mutableStateOf("")
    var server: String
        get() = serverState.value
        set(value) {
            val trimmed = value.trim()
            serverState.value = trimmed
            put { putString(KEY_SERVER, trimmed) }
        }

    /** Text size in canvas dp, not sp: the TV's font scale must not change the lanes. */
    private val sizeState = mutableStateOf(DEFAULT_SIZE)
    var size: Int
        get() = sizeState.value
        set(value) {
            sizeState.value = value.coerceIn(MIN_SIZE, MAX_SIZE)
            put { putInt(KEY_SIZE, sizeState.value) }
        }

    /** Opacity of the whole layer. */
    private val alphaState = mutableStateOf(DEFAULT_ALPHA)
    var alpha: Float
        get() = alphaState.value
        set(value) {
            alphaState.value = value.coerceIn(MIN_ALPHA, MAX_ALPHA)
            put { putFloat(KEY_ALPHA, alphaState.value) }
        }

    /** Percent of the canvas height used by the lanes. */
    private val areaState = mutableStateOf(DEFAULT_AREA)
    var area: Int
        get() = areaState.value
        set(value) {
            areaState.value = value.coerceIn(MIN_AREA, MAX_AREA)
            put { putInt(KEY_AREA, areaState.value) }
        }

    /** How much faster than the model of `docs/DANMAKU.md` the comments travel. */
    private val speedState = mutableStateOf(DEFAULT_SPEED)
    var speedMul: Float
        get() = speedState.value
        set(value) {
            speedState.value = value.coerceIn(MIN_SPEED, MAX_SPEED)
            put { putFloat(KEY_SPEED, speedState.value) }
        }

    /** Loads the saved values. Safe from every entry point; only the first call reads. */
    @Synchronized
    fun init(context: Context) {
        if (started) return
        started = true
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = saved
        enabledState.value = saved.getBoolean(KEY_ENABLED, false)
        serverState.value = saved.getString(KEY_SERVER, null).orEmpty().trim()
        scrollState.value = saved.getBoolean(KEY_SCROLL, true)
        topState.value = saved.getBoolean(KEY_TOP, true)
        bottomState.value = saved.getBoolean(KEY_BOTTOM, true)
        reverseState.value = saved.getBoolean(KEY_REVERSE, true)
        sizeState.value = saved.getInt(KEY_SIZE, DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE)
        alphaState.value = saved.getFloat(KEY_ALPHA, DEFAULT_ALPHA).coerceIn(MIN_ALPHA, MAX_ALPHA)
        areaState.value = saved.getInt(KEY_AREA, DEFAULT_AREA).coerceIn(MIN_AREA, MAX_AREA)
        speedState.value = saved.getFloat(KEY_SPEED, DEFAULT_SPEED).coerceIn(MIN_SPEED, MAX_SPEED)
    }

    /**
     * The base URL to call: the configured one, else the NAS host on [PORT] (the reference
     * implementation's rule, `docs/DANMAKU.md` §1). Empty when neither is known, which the layer
     * reads as "no danmaku service" instead of as a failure.
     */
    fun serverUrl(): String {
        val saved = serverState.value
        if (saved.isNotEmpty()) return saved.trimEnd('/')
        val host = FnosStore.host
        if (host.isEmpty()) return ""
        // "http://nas:5666" → "nas": the danmaku service is another port on the same machine.
        val authority = host.substringAfter("//", host).substringBefore('/')
        val name = authority.substringBeforeLast(':', authority)
        return if (name.isEmpty()) "" else "http://$name:$PORT"
    }

    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        val saved = prefs ?: return
        val editor = saved.edit()
        editor.block()
        editor.apply()
    }
}