package com.tikoncha.darcha.model

/** Horizontal cell text alignment (OOXML `alignment/@horizontal`). */
public enum class HorizontalAlignment {
    /** No explicit alignment — the renderer aligns by value type. */
    GENERAL,
    LEFT,
    CENTER,
    RIGHT,
    FILL,
    JUSTIFY,
    CENTER_CONTINUOUS,
    DISTRIBUTED,
}

/** Vertical cell text alignment (OOXML `alignment/@vertical`). */
public enum class VerticalAlignment {
    TOP,
    CENTER,

    /** Excel's default vertical alignment. */
    BOTTOM,
    JUSTIFY,
    DISTRIBUTED,
}
