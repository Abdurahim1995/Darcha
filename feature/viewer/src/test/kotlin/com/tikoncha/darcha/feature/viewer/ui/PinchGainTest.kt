package com.tikoncha.darcha.feature.viewer.ui

import com.tikoncha.darcha.feature.viewer.mvi.Viewport
import com.tikoncha.darcha.feature.viewer.mvi.zoomedAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Pinch damping (T35), and the property that makes it the right shape.
 *
 * The complaint was that a single ordinary spread ran the whole 0.5..3.0 range.
 * The arithmetic was never wrong — these tests pin *why* the fix is an exponent
 * on each increment rather than a factor on the delta, and re-assert T20's focal
 * promise across a damped pinch so the fix cannot have cost it.
 */
class PinchGainTest {

    /** A pinch, as the gesture loop sees it: one ratio per pointer event. */
    private fun increments(distances: FloatArray): FloatArray =
        FloatArray(distances.size - 1) { distances[it + 1] / distances[it] }

    /** Replay a pinch through the real gesture-to-reducer path. */
    private fun replay(
        start: Viewport,
        distances: FloatArray,
        focus: Float = 0f,
    ): Viewport {
        var viewport = start
        for (ratio in increments(distances)) {
            viewport = viewport.zoomedAt(dampedPinchScale(ratio), focus, focus)
        }
        return viewport
    }

    // --- the property: only the endpoints matter ---

    /**
     * The whole reason for an exponent. `∏ (dᵢ/dᵢ₋₁)^γ = (dEnd/dStart)^γ`, so the
     * same finger travel gives the same zoom whether the device reported 6
     * events or 60. A factor on the delta — `1 + (r-1)*k` — is a sum in
     * disguise and fails exactly here: see [aFactorOnTheDelta_wouldNotHaveHeld].
     */
    @Test
    fun theSameFingerTravel_zoomsTheSameAtAnyEventRate() {
        val coarse = floatArrayOf(200f, 400f, 600f)
        val fine = FloatArray(41) { 200f + it * 10f } // same 200 -> 600, 40 steps
        val start = Viewport(zoom = 1f)

        val byCoarse = replay(start, coarse).zoom
        val byFine = replay(start, fine).zoom

        assertEquals("event rate must not change the zoom", byCoarse, byFine, 1e-4f)
        assertEquals("and it is (dEnd/dStart)^gain", 3f.pow(PINCH_GAIN), byFine, 1e-4f)
    }

    /**
     * The rejected alternative, kept as a test so the reasoning is not folklore.
     *
     * Both forms are compared on the *same* finger travel sampled two ways, and
     * the claim is relative rather than absolute: the exponent is
     * rate-independent to float noise while the linear form is not. Measured
     * here, 200 px → 600 px reported as 2 events versus 40: the exponent moves by
     * ~1e-7 and the linear form by ~0.14, one being 6 orders of magnitude larger
     * than the other. A bare "> 0.2" threshold would have been a number tuned to
     * pass, and it would have failed — the real gap is 0.143.
     */
    @Test
    fun aFactorOnTheDelta_wouldNotHaveHeld() {
        fun linear(r: Float, k: Float = 0.5f) = 1f + (r - 1f) * k
        fun replayWith(distances: FloatArray, step: (Float) -> Float): Float {
            var zoom = 1f
            for (r in increments(distances)) zoom *= step(r)
            return zoom
        }

        val coarse = floatArrayOf(200f, 400f, 600f)
        val fine = FloatArray(41) { 200f + it * 10f }

        val exponentDrift =
            kotlin.math.abs(replayWith(coarse, ::dampedPinchScale) - replayWith(fine, ::dampedPinchScale))
        val linearDrift =
            kotlin.math.abs(replayWith(coarse, ::linear) - replayWith(fine, ::linear))

        assertTrue(
            "the linear form is rate-dependent ($linearDrift) and the exponent is not ($exponentDrift)",
            linearDrift > 100f * exponentDrift.coerceAtLeast(1e-6f),
        )
    }

    @Test
    fun pinchingInAndBackOut_returnsToTheStart() {
        val out = floatArrayOf(200f, 300f, 500f, 900f)
        val back = out.reversedArray()
        val start = Viewport(zoom = 1.4f)

        val there = replay(start, out)
        val andBack = replay(there, back)

        assertEquals("a gesture must be reversible", start.zoom, andBack.zoom, 1e-4f)
    }

    // --- the value, stated as behaviour rather than as a constant ---

    @Test
    fun doublingTheZoom_takesFourTimesTheFingerDistance() {
        val result = replay(Viewport(zoom = 1f), floatArrayOf(150f, 600f))
        assertEquals(2f, result.zoom, 1e-4f)
    }

    /**
     * The measured complaint: a spread that reached 2.7x in half a second used to
     * pin the zoom at the 3.0 ceiling. It must now land somewhere a reader can
     * stop — around one useful step, not the end of the range.
     */
    @Test
    fun theGestureThatUsedToHitTheCeiling_nowLandsMidRange() {
        val before = Viewport(zoom = 1f)
        val undamped = before.zoomedAt(2.7f, 0f, 0f)
        val damped = replay(before, floatArrayOf(200f, 540f))

        assertEquals("what it did before", 2.7f, undamped.zoom, 1e-4f)
        assertEquals("what it does now", 1.643f, damped.zoom, 1e-3f)
        assertTrue("must leave room to keep going", damped.zoom < Viewport.MAX_ZOOM)
    }

    @Test
    fun theFullRangeStillReachable_withADeliberateSpread() {
        // 9x finger travel: the ceiling is reachable, just not by accident.
        val result = replay(Viewport(zoom = 1f), floatArrayOf(120f, 1080f))
        assertEquals(Viewport.MAX_ZOOM, result.zoom, 1e-4f)
    }

    @Test
    fun aDegenerateRatioIsIgnored_ratherThanCrashing() {
        // Two pointers landing on the same pixel give a zero spread.
        assertEquals(1f, dampedPinchScale(0f), 0f)
        assertEquals(1f, dampedPinchScale(-1f), 0f)
    }

    // --- T20's promise, re-asserted across a damped pinch ---

    /**
     * Damping changes *how far* each event zooms, so the focal compensation now
     * runs on different numbers. The promise it makes is unchanged: the content
     * under the fingers does not move. Re-checked event by event, at every focal
     * point, rather than only at the end of the gesture.
     */
    @Test
    fun theContentUnderTheFingers_stillDoesNotMove() {
        val distances = floatArrayOf(180f, 240f, 310f, 420f, 560f, 700f, 830f)
        val focals = floatArrayOf(0f, 137f, 540f, 1079f)

        for (focus in focals) {
            var viewport = Viewport(scrollX = 3000f, scrollY = 7000f, zoom = 1f)
            for (ratio in increments(distances)) {
                val before = viewport
                viewport = before.zoomedAt(dampedPinchScale(ratio), focus, focus)
                if (viewport.zoom == before.zoom) continue

                assertEquals(
                    "x drift at focus $focus",
                    before.scrollX + focus / before.zoom,
                    viewport.scrollX + focus / viewport.zoom,
                    1e-2f,
                )
                assertEquals(
                    "y drift at focus $focus",
                    before.scrollY + focus / before.zoom,
                    viewport.scrollY + focus / viewport.zoom,
                    1e-2f,
                )
            }
        }
    }
}
