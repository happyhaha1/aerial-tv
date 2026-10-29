package app.orchard.tv.player

/** How the picture is fitted to the screen. */
enum class AspectMode {
    /** The whole picture, letterboxed or pillarboxed to the screen. */
    Fit,

    /** The whole screen, the picture stretched to it. */
    Fill,

    /** The picture at 16:9, whatever its own aspect is. */
    Wide16x9,
}

/** The size the video surface takes inside a root of [rootWidth]×[rootHeight]: [width]×[height]. */
data class SurfaceSize(val width: Int, val height: Int)

/**
 * The size the `SurfaceView` takes for [mode] inside a root of [rootWidth]×[rootHeight].
 *
 * The picture is decoded into the surface, so the surface's own shape is what the film is fitted
 * to: the same size, the same aspect. Null while the picture's size is unknown and the mode needs
 * it — the surface stays full screen, and black, until the first frames arrive.
 */
fun surfaceSize(rootWidth: Int, rootHeight: Int, videoAspect: Float, mode: AspectMode): SurfaceSize? {
    if (rootWidth <= 0 || rootHeight <= 0) return null
    if (mode == AspectMode.Fill) return SurfaceSize(rootWidth, rootHeight)
    val aspect = if (mode == AspectMode.Wide16x9) 16f / 9f else videoAspect
    if (aspect <= 0f) return null
    val width = minOf(rootWidth, (rootHeight * aspect).toInt()).coerceAtLeast(1)
    val height = minOf(rootHeight, (width / aspect).toInt()).coerceAtLeast(1)
    return SurfaceSize(width, height)
}