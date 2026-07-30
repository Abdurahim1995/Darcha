package com.tikoncha.darcha.feature.viewer.mvi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The focal-point maths behind pinch zoom (TECH_SPEC §9.2, T20).
 *
 * Rather than pinning the arithmetic, most of these assert the **property the
 * gesture promises**: the content point under the fingers is the same before and
 * after. That is what "the focal cell stays put" means, and it is checked at a
 * spread of zooms, focal points and scale factors instead of one worked example.
 */
class FocalZoomTest {

    /** Content coordinate currently under screen offset [screen] on this axis. */
    private fun contentUnder(scroll: Float, zoom: Float, screen: Float): Float =
        scroll + screen / zoom

    private val unbounded = ScrollBounds.UNKNOWN

    // --- the promise ---

    @Test
    fun theContentUnderTheFocus_doesNotMove() {
        val focals = floatArrayOf(0f, 1f, 137f, 540f, 1079f)
        val zooms = floatArrayOf(0.5f, 0.75f, 1f, 1.6f, 2f, 2.9f)
        val scales = floatArrayOf(0.52f, 0.8f, 0.99f, 1.01f, 1.25f, 1.9f)

        for (startZoom in zooms) {
            for (focus in focals) {
                for (scale in scales) {
                    val before = Viewport(scrollX = 3000f, scrollY = 7000f, zoom = startZoom)
                    val after = before.zoomedAt(scale, focus, focus, unbounded)
                    // At a clamp stop nothing moves at all; that is its own test.
                    if (after.zoom == before.zoom) continue

                    val label = "zoom $startZoom x$scale at $focus"
                    assertEquals(
                        label,
                        contentUnder(before.scrollX, before.zoom, focus),
                        contentUnder(after.scrollX, after.zoom, focus),
                        TOLERANCE,
                    )
                    assertEquals(
                        label,
                        contentUnder(before.scrollY, before.zoom, focus),
                        contentUnder(after.scrollY, after.zoom, focus),
                        TOLERANCE,
                    )
                }
            }
        }
    }

    /** Zooming about the top-left corner is pure scaling — scroll must not move. */
    @Test
    fun zoomingAboutTheOrigin_doesNotScroll() {
        val before = Viewport(scrollX = 1200f, scrollY = 800f, zoom = 1f)
        val after = before.zoomedAt(scale = 2f, focusX = 0f, focusY = 0f, bounds = unbounded)

        assertEquals(2f, after.zoom, TOLERANCE)
        assertEquals(before.scrollX, after.scrollX, TOLERANCE)
        assertEquals(before.scrollY, after.scrollY, TOLERANCE)
    }

    /** Zooming in pulls content toward the focus, so scroll increases. */
    @Test
    fun zoomingIn_scrollsTowardTheFocus() {
        val before = Viewport(scrollX = 1000f, scrollY = 1000f, zoom = 1f)
        val after = before.zoomedAt(scale = 2f, focusX = 600f, focusY = 400f, bounds = unbounded)

        assertTrue("scrollX should grow", after.scrollX > before.scrollX)
        assertTrue("scrollY should grow", after.scrollY > before.scrollY)
        // focus × (1/1 − 1/2) = focus / 2.
        assertEquals(1000f + 300f, after.scrollX, TOLERANCE)
        assertEquals(1000f + 200f, after.scrollY, TOLERANCE)
    }

    @Test
    fun zoomingOut_scrollsAwayFromTheFocus() {
        val before = Viewport(scrollX = 1000f, scrollY = 1000f, zoom = 2f)
        val after = before.zoomedAt(scale = 0.5f, focusX = 600f, focusY = 400f, bounds = unbounded)

        assertEquals(1f, after.zoom, TOLERANCE)
        // focus × (1/2 − 1/1) = −focus / 2.
        assertEquals(1000f - 300f, after.scrollX, TOLERANCE)
        assertEquals(1000f - 200f, after.scrollY, TOLERANCE)
    }

    /** A pinch in and straight back out must land where it started. */
    @Test
    fun zoomingInAndBackOut_returnsToTheStart() {
        val start = Viewport(scrollX = 2500f, scrollY = 4100f, zoom = 1f)
        val zoomedIn = start.zoomedAt(1.75f, 480f, 900f, unbounded)
        val backOut = zoomedIn.zoomedAt(1f / 1.75f, 480f, 900f, unbounded)

        assertEquals(start.zoom, backOut.zoom, TOLERANCE)
        assertEquals(start.scrollX, backOut.scrollX, TOLERANCE)
        assertEquals(start.scrollY, backOut.scrollY, TOLERANCE)
    }

    // --- the clamps ---

    @Test
    fun zoomIsClampedToTheAllowedRange() {
        val wideOpen = Viewport(scrollX = 0f, scrollY = 0f, zoom = 1f)
        assertEquals(Viewport.MAX_ZOOM, wideOpen.zoomedAt(99f, 300f, 300f, unbounded).zoom, TOLERANCE)
        assertEquals(Viewport.MIN_ZOOM, wideOpen.zoomedAt(0.001f, 300f, 300f, unbounded).zoom, TOLERANCE)
    }

    /**
     * At a stop the zoom cannot change, so the compensation must not happen
     * either — otherwise pinching against the limit would drift the sheet while
     * it visibly stood still.
     */
    @Test
    fun atAStop_nothingMovesAtAll() {
        val atMax = Viewport(scrollX = 900f, scrollY = 700f, zoom = Viewport.MAX_ZOOM)
        val pinchedFurther = atMax.zoomedAt(1.5f, 400f, 400f, unbounded)
        assertEquals(atMax, pinchedFurther)

        val atMin = Viewport(scrollX = 900f, scrollY = 700f, zoom = Viewport.MIN_ZOOM)
        assertEquals(atMin, atMin.zoomedAt(0.5f, 400f, 400f, unbounded))
    }

    /** Partial travel toward a stop still zooms, and still holds the focus. */
    @Test
    fun approachingAStop_stillHoldsTheFocus() {
        val before = Viewport(scrollX = 500f, scrollY = 500f, zoom = 2.5f)
        val after = before.zoomedAt(scale = 4f, focusX = 300f, focusY = 200f, bounds = unbounded)

        assertEquals(Viewport.MAX_ZOOM, after.zoom, TOLERANCE)
        assertEquals(
            contentUnder(before.scrollX, before.zoom, 300f),
            contentUnder(after.scrollX, after.zoom, 300f),
            TOLERANCE,
        )
    }

    // --- interaction with scroll bounds ---

    @Test
    fun theCompensationRespectsTheScrollCeiling() {
        val bounds = ScrollBounds(maxScrollX = 1100f, maxScrollY = 1100f)
        val before = Viewport(scrollX = 1000f, scrollY = 1000f, zoom = 1f)
        val after = before.zoomedAt(scale = 3f, focusX = 900f, focusY = 900f, bounds = bounds)

        assertEquals("clamped, not overshot", 1100f, after.scrollX, TOLERANCE)
        assertEquals(1100f, after.scrollY, TOLERANCE)
    }

    /** On a frozen sheet the floor applies to the compensation too (T19). */
    @Test
    fun theCompensationRespectsTheFrozenFloor() {
        val bounds = ScrollBounds(
            maxScrollX = 9000f,
            maxScrollY = 9000f,
            minScrollX = 160f,
            minScrollY = 105f,
        )
        val before = Viewport(scrollX = 200f, scrollY = 150f, zoom = 2f)
        // Zooming out about a far focus would push scroll below the frozen extent.
        val after = before.zoomedAt(scale = 0.5f, focusX = 800f, focusY = 800f, bounds = bounds)

        assertEquals(160f, after.scrollX, TOLERANCE)
        assertEquals(105f, after.scrollY, TOLERANCE)
    }

    private companion object {
        /** Sub-pixel: the focus may drift by float noise, not by a pixel. */
        const val TOLERANCE = 0.01f
    }
}
