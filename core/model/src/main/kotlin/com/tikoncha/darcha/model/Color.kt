package com.tikoncha.darcha.model

/**
 * A color as a packed ARGB integer (`0xAARRGGBB`, alpha in the high byte).
 *
 * The parser resolves OOXML `rgb`, `indexed`, and (with a documented v1
 * fallback) `theme` color references to ARGB (TECH_SPEC §7 step 4).
 *
 * @property argb the packed color value.
 */
@JvmInline
public value class Color(public val argb: Int) {
    public companion object {
        public val BLACK: Color = Color(0xFF000000.toInt())
        public val WHITE: Color = Color(0xFFFFFFFF.toInt())
    }
}
