package com.tikoncha.darcha.feature.viewer.mvi

/**
 * The double-tap zoom animation (T20), kept beside [FlingDecay] as the other
 * piece of motion the ViewModel drives.
 *
 * Zoom is **geometric, not linear**: going from 3.0 to 1.0 and from 0.5 to 1.0
 * are both "half the distance" in the way the eye reads them, even though one
 * spans 2.0 and the other 0.5. So the animation interpolates the *ratio*, which
 * makes zooming out from 3× and zooming in from 0.5× feel like the same gesture
 * at the same speed.
 *
 * Pure and free of Compose, so it is testable on a plain JVM.
 */
internal object ZoomAnimation {

    /** Where a double tap always lands. */
    const val TARGET: Float = 1f

    /** Frames the animation runs for — about 200 ms at [FlingDecay.FRAME_MILLIS]. */
    const val FRAMES: Int = 12

    /** Below this the zoom is already 1 for practical purposes. */
    const val EPSILON: Float = 0.001f

    /**
     * Ease-out: most of the distance early, settling gently.
     *
     * A linear ramp reads as mechanical because it stops dead; this is the same
     * curve Material uses for a decelerating transition, written out rather than
     * pulled in so `:feature:viewer`'s motion stays in one place.
     */
    fun ease(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t)
    }

    /**
     * The zoom [eased] of the way from [start] to [TARGET], interpolated
     * geometrically.
     *
     * `start × (target / start)^eased` — which is `start` at 0 and exactly
     * [TARGET] at 1, with each step a constant *ratio* rather than a constant
     * difference.
     */
    fun zoomAt(start: Float, eased: Float): Float {
        if (start <= 0f) return TARGET
        val t = eased.coerceIn(0f, 1f)
        return (start * Math.pow((TARGET / start).toDouble(), t.toDouble()).toFloat())
    }
}
