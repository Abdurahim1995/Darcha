package com.tikoncha.darcha.feature.viewer.mvi

import kotlin.math.abs

/**
 * The fling curve, as pure arithmetic (TECH_SPEC §9).
 *
 * Velocity decays geometrically, one step per frame:
 *
 * ```
 * v(n+1) = v(n) × FRICTION
 * ```
 *
 * which is the discrete form of exponential decay and integrates to a total
 * glide of roughly `v₀ × FRAME_SECONDS / (1 − FRICTION)`. At the values below
 * that is about `v₀ × 0.33 s`, so a 3,000 px/s flick travels ~1,000 px.
 *
 * Kept separate from the ViewModel so the curve can be unit tested without a
 * coroutine or a frame clock, and so the two knobs worth turning — [FRICTION]
 * and [MIN_VELOCITY] — sit in one place.
 */
internal object FlingDecay {

    /** Fraction of velocity surviving each frame. Lower stops sooner. */
    const val FRICTION: Float = 0.95f

    /** Below this speed (px/s) the glide is over. */
    const val MIN_VELOCITY: Float = 40f

    /** Nominal frame length; ~60 fps. */
    const val FRAME_MILLIS: Long = 16L

    /** [FRAME_MILLIS] in seconds, for velocity → distance. */
    const val FRAME_SECONDS: Float = FRAME_MILLIS / 1000f

    /** Velocity after one frame of friction. */
    fun decay(velocity: Float): Float = velocity * FRICTION

    /** Content pixels travelled in one frame at [velocity] px/s. */
    fun step(velocity: Float): Float = velocity * FRAME_SECONDS

    /** Whether a glide at ([vx], [vy]) still has enough speed to be worth a frame. */
    fun isMoving(vx: Float, vy: Float): Boolean =
        abs(vx) >= MIN_VELOCITY || abs(vy) >= MIN_VELOCITY
}
