package com.tikoncha.darcha.feature.viewer.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.pow

/**
 * Whether text the document coloured itself can actually be seen on the
 * background *we* chose (T28).
 *
 * **The problem this solves, in both directions.** An unfilled cell has no
 * background of its own — it shows the app's surface. So a font colour picked
 * against Excel's white sheet can land on our dark surface, and one picked
 * against a dark sheet can land on our light one. Black-on-dark is the case
 * dark mode made obvious; white-on-light is the same bug seen from the other
 * side, and it was broken in light mode from the very first release.
 *
 * **This replaced a heuristic, and only one mechanism decides now.** T24 guessed
 * that a near-black colour "probably means default" and swapped it out in dark
 * mode. That guess existed because the parser could not distinguish
 * `<color theme="1"/>` from a deliberate black. T28 made the parser record that
 * distinction — a colour the document did not choose is now `null` all the way
 * through the model — so the guess is gone, not demoted to a fallback. What
 * remains is this: a measurement, symmetric in both themes, applied only where
 * the background is ours to begin with.
 *
 * The threshold is deliberately low. This is not a contrast *enforcer*: grey
 * text on white is a legitimate way to make something look secondary, and a
 * viewer that "fixed" it would be rewriting the document. It rescues only text
 * that is effectively invisible.
 */
internal object TextLegibility {

    /**
     * Contrast ratio below which text counts as unreadable rather than subtle.
     *
     * WCAG asks for 4.5:1 for body text and 3:1 for large text — those are
     * *design* targets, and applying them here would override deliberate styling
     * on any lightly-coloured cell. The measured cases this must catch sit an
     * order of magnitude lower:
     *
     * | Text | On our surface | Ratio |
     * |---|---|---|
     * | black `#000000` | dark `#141218` | ≈ 1.09 |
     * | white `#FFFFFF` | light `#FDF7FF` | ≈ 1.03 |
     * | grey `#999999` | light `#FDF7FF` | ≈ 2.8 — deliberate, left alone |
     * | grey `#555555` | dark `#141218` | ≈ 2.4 — dim, left alone |
     *
     * 1.5 sits in the wide gap between "cannot see it" and "chose to be quiet".
     */
    const val MIN_CONTRAST: Float = 1.5f

    /**
     * The colour to draw [own] text in, on a cell whose background is [behind].
     *
     * @param own what the document chose, or `null` if it chose nothing.
     * @param fallback the theme's own text colour.
     * @param behind what the text will actually sit on — the cell's fill if it
     *   has one, otherwise the app's surface.
     * @param documentOwnsBackground `true` when the cell has a fill. Then the
     *   document picked *both* colours and we render its pairing faithfully,
     *   however low the contrast: black on the author's yellow stays black, and
     *   white on the author's navy stays white.
     */
    fun resolve(
        own: Color?,
        fallback: Color,
        behind: Color,
        documentOwnsBackground: Boolean,
    ): Color {
        if (own == null) return fallback
        if (documentOwnsBackground) return own
        return if (contrastRatio(own, behind) >= MIN_CONTRAST) own else fallback
    }

    /**
     * WCAG 2.1 contrast ratio between two opaque colours: `(L1 + 0.05) / (L2 + 0.05)`
     * with the lighter luminance on top. Ranges from 1 (identical) to 21
     * (black on white).
     */
    fun contrastRatio(a: Color, b: Color): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = if (la > lb) la else lb
        val darker = if (la > lb) lb else la
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /** WCAG relative luminance: sRGB channels linearised, then weighted. */
    private fun relativeLuminance(color: Color): Float =
        0.2126f * linearise(color.red) +
            0.7152f * linearise(color.green) +
            0.0722f * linearise(color.blue)

    /** Undo the sRGB transfer function for one channel. */
    private fun linearise(channel: Float): Float {
        val c = abs(channel)
        return if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
}
