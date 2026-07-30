package com.tikoncha.darcha.feature.viewer.mvi

/**
 * How far the viewport may scroll, in content pixels (TECH_SPEC §9.2).
 *
 * The grid is always the full 16,384 × 1,048,576 addressable sheet, so clamping
 * to *that* would let a three-row document scroll for a kilometre. The limits
 * here follow the **used range** instead — the last populated row and column —
 * so scrolling stops once the last real cell reaches the top-left corner.
 *
 * Scrolling also has a **floor**, which is zero on an ordinary sheet and the
 * frozen extent on a frozen one (T19): the scrolling region begins at the first
 * unfrozen column, and letting scroll fall below that would draw the frozen
 * columns a second time inside the body.
 *
 * Only the renderer can compute these: they depend on the display density that
 * it folds into the geometry (§9.2). It publishes them with
 * [RenderEvent.BoundsChanged] whenever the sheet or the density changes.
 */
public data class ScrollBounds(
    public val maxScrollX: Float,
    public val maxScrollY: Float,
    public val minScrollX: Float = 0f,
    public val minScrollY: Float = 0f,
) {
    public companion object {
        /**
         * The limits before the renderer has measured anything. Scrolling is
         * still clamped at zero, so an unmeasured grid cannot go negative.
         */
        public val UNKNOWN: ScrollBounds = ScrollBounds(
            maxScrollX = Float.MAX_VALUE,
            maxScrollY = Float.MAX_VALUE,
        )
    }
}
