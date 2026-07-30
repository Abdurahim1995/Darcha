package com.tikoncha.darcha.feature.viewer.mvi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The fling curve as arithmetic — no coroutine, no frame clock. */
class FlingDecayTest {

    @Test
    fun velocityDecaysGeometrically() {
        var v = 1_000f
        v = FlingDecay.decay(v)
        assertEquals(950f, v, 0.01f)
        v = FlingDecay.decay(v)
        assertEquals(902.5f, v, 0.01f)
    }

    @Test
    fun decayPreservesDirection() {
        assertTrue(FlingDecay.decay(-800f) < 0f)
        assertTrue(FlingDecay.decay(800f) > 0f)
    }

    @Test
    fun aFlingAlwaysTerminates() {
        // However hard the flick, friction must bring it under the threshold.
        var v = 100_000f
        var frames = 0
        while (FlingDecay.isMoving(v, 0f) && frames < 10_000) {
            v = FlingDecay.decay(v)
            frames++
        }
        assertFalse("a fling must stop", FlingDecay.isMoving(v, 0f))
        assertTrue("and within a second or two, not minutes: $frames frames", frames < 200)
    }

    @Test
    fun glideDistanceMatchesTheGeometricSum() {
        // Σ v₀·f^n·dt = v₀·dt/(1−f). The loop must land near that closed form.
        val initial = 3_000f
        var v = initial
        var distance = 0f
        while (FlingDecay.isMoving(v, 0f)) {
            distance += FlingDecay.step(v)
            v = FlingDecay.decay(v)
        }
        val closedForm = initial * FlingDecay.FRAME_SECONDS / (1f - FlingDecay.FRICTION)
        assertTrue(
            "travelled $distance, closed form $closedForm",
            abs(distance - closedForm) < closedForm * 0.05f,
        )
        // Sanity: a hard flick should cross about a screen and a half, not a pixel.
        assertTrue("distance $distance", distance in 800f..1_200f)
    }

    @Test
    fun slowReleasesDoNotFling() {
        // Lifting a finger while barely moving should not start a glide.
        assertFalse(FlingDecay.isMoving(10f, 10f))
        assertTrue(FlingDecay.isMoving(0f, FlingDecay.MIN_VELOCITY))
    }

    @Test
    fun stepConvertsVelocityToOneFrameOfTravel() {
        // 600 px/s for one 16 ms frame ≈ 9.6 px.
        assertEquals(9.6f, FlingDecay.step(600f), 0.01f)
    }
}
