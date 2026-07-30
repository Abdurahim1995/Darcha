package com.tikoncha.darcha.feature.viewer.mvi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The double-tap zoom animation (T20). */
class ZoomAnimationTest {

    @Test
    fun theAnimationStartsWhereItIsAndEndsAtOne() {
        for (start in floatArrayOf(0.5f, 0.8f, 1.4f, 2.2f, 3f)) {
            assertEquals("from $start", start, ZoomAnimation.zoomAt(start, 0f), TOLERANCE)
            assertEquals("from $start", ZoomAnimation.TARGET, ZoomAnimation.zoomAt(start, 1f), TOLERANCE)
        }
    }

    /**
     * Zoom is geometric, so a *ratio* interpolation is what makes zooming out
     * from 3× and zooming in from 0.5× feel like the same gesture. Halfway from
     * 4 to 1 is 2 — not 2.5, which is what a linear ramp would give.
     */
    @Test
    fun theMidpointIsGeometricNotArithmetic() {
        assertEquals(2f, ZoomAnimation.zoomAt(start = 4f, eased = 0.5f), TOLERANCE)
        assertEquals(0.5f, ZoomAnimation.zoomAt(start = 0.25f, eased = 0.5f), TOLERANCE)
    }

    /** Each frame is the same ratio, whichever direction the animation runs. */
    @Test
    fun everyStepIsTheSameRatio() {
        val start = 3f
        val steps = (0..4).map { ZoomAnimation.zoomAt(start, it / 4f) }
        val ratios = steps.zipWithNext { a, b -> b / a }
        for (ratio in ratios) assertEquals(ratios.first(), ratio, TOLERANCE)
    }

    @Test
    fun theEasingDeceleratesAndIsBounded() {
        assertEquals(0f, ZoomAnimation.ease(0f), TOLERANCE)
        assertEquals(1f, ZoomAnimation.ease(1f), TOLERANCE)
        // Ease-out: more than half the distance is covered by half the time.
        assertTrue(ZoomAnimation.ease(0.5f) > 0.5f)
        // Monotonic, so the zoom never doubles back.
        var previous = -1f
        for (i in 0..20) {
            val value = ZoomAnimation.ease(i / 20f)
            assertTrue("not monotonic at $i", value >= previous)
            previous = value
        }
        // Out-of-range input is clamped rather than extrapolated.
        assertEquals(0f, ZoomAnimation.ease(-3f), TOLERANCE)
        assertEquals(1f, ZoomAnimation.ease(9f), TOLERANCE)
    }

    /** Composing every frame's ratio must land exactly on 1, with no drift. */
    @Test
    fun theFramesCompoundToExactlyOne() {
        for (start in floatArrayOf(0.5f, 1.7f, 3f)) {
            var zoom = start
            for (frame in 1..ZoomAnimation.FRAMES) {
                val want = ZoomAnimation.zoomAt(start, ZoomAnimation.ease(frame.toFloat() / ZoomAnimation.FRAMES))
                zoom *= (want / zoom) // exactly what the ViewModel dispatches
            }
            assertEquals("from $start", ZoomAnimation.TARGET, zoom, TOLERANCE)
        }
    }

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}
