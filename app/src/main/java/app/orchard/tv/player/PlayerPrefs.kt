package app.orchard.tv.player

import android.content.Context

/**
 * The player's own memory, in a `player` preferences file of its own.
 *
 * The audio and subtitle choice is remembered per title — the same film or episode opened again
 * comes back with the tracks it was watched with — while the speed and the aspect are the way this
 * TV's viewer wants every film, and are remembered once for all of them.
 */
object PlayerPrefs {
    private const val FILE = "player"
    private const val KEY_SPEED = "speed"
    private const val KEY_ASPECT = "aspect"

    /** Kinds of track choice; the key prefixes of one title's remembered languages. */
    const val AUDIO = "audio"
    const val TEXT = "text"

    /** How "subtitles off" is remembered; a language name never looks like this. */
    const val OFF = "_off_"

    /** The language [kind] track was last watched in for [title], or null when never chosen. */
    fun language(context: Context, kind: String, title: String): String? =
        prefs(context).getString("$kind.$title", null)?.takeIf { it.isNotEmpty() }

    fun setLanguage(context: Context, kind: String, title: String, value: String) {
        prefs(context).edit().putString("$kind.$title", value).apply()
    }

    /** The playback speed the viewer left, 1.0 when never chosen. */
    fun speed(context: Context): Float = prefs(context).getFloat(KEY_SPEED, 1f).takeIf { it > 0f } ?: 1f

    fun setSpeed(context: Context, speed: Float) {
        prefs(context).edit().putFloat(KEY_SPEED, speed).apply()
    }

    /** How the picture was fitted last. */
    fun aspect(context: Context): AspectMode {
        val name = prefs(context).getString(KEY_ASPECT, null) ?: return AspectMode.Fit
        return AspectMode.entries.firstOrNull { it.name == name } ?: AspectMode.Fit
    }

    fun setAspect(context: Context, mode: AspectMode) {
        prefs(context).edit().putString(KEY_ASPECT, mode.name).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}