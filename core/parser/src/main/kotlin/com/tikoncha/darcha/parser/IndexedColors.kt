package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.Color

/**
 * The standard OOXML indexed color palette (TECH_SPEC §7 step 4).
 *
 * Legacy `indexed="N"` color references resolve against this 64-entry palette.
 * v1 ships the standard palette and does NOT read a workbook's optional
 * `<colors><indexedColors>` override — that is a known simplification.
 */
internal object IndexedColors {

    // Standard palette as 0xRRGGBB; alpha is added in [colorFor].
    private val RGB: IntArray = intArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
    )

    /**
     * The [Color] for palette [index], or `null` if out of range (e.g. the
     * system foreground/background indices 64/65, treated as default here).
     */
    fun colorFor(index: Int): Color? =
        if (index in RGB.indices) Color(0xFF000000.toInt() or RGB[index]) else null
}
