package com.tikoncha.darcha.feature.viewer.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one mechanism that decides text colour (T28).
 *
 * T24 had a near-black heuristic that only ran in dark mode. It is gone — the
 * parser now records whether the document chose a colour at all, so this is a
 * measurement rather than a guess, and it is symmetric: the light-mode case had
 * been broken since v1.0 and the old heuristic could not have caught it.
 */
class TextLegibilityTest {

    // The real surfaces from DarchaTheme, so these are not hypothetical numbers.
    private val darkSurface = Color(0xFF141218)
    private val lightSurface = Color(0xFFFDF7FF)
    private val darkThemeText = Color(0xFFE4E1E6)
    private val lightThemeText = Color(0xFF202020)

    private fun onDark(own: Color?, behind: Color = darkSurface, filled: Boolean = false) =
        TextLegibility.resolve(own, darkThemeText, behind, filled)

    private fun onLight(own: Color?, behind: Color = lightSurface, filled: Boolean = false) =
        TextLegibility.resolve(own, lightThemeText, behind, filled)

    // --- no choice: the theme supplies the colour ---

    /**
     * The common case by a wide margin. Every real Excel file writes
     * `<color theme="1"/>` for ordinary text, which the parser now reports as
     * "chose nothing" rather than as black.
     */
    @Test
    fun theDocumentChoseNothing_soTheThemeDecides() {
        assertEquals(darkThemeText, onDark(null))
        assertEquals(lightThemeText, onLight(null))
    }

    @Test
    fun theDocumentChoseNothing_evenOnAFill_theThemeStillDecides() {
        // A fill without a font colour is common (a highlighted row of plain
        // text). There is no choice to honour, so the theme's text colour is
        // still the right answer.
        assertEquals(darkThemeText, onDark(null, behind = Color(0xFF1F3864), filled = true))
    }

    // --- the two invisible cases, one per theme ---

    @Test
    fun explicitBlackOnOurDarkSurface_isRescued() {
        assertEquals(darkThemeText, onDark(Color.Black))
    }

    /**
     * The inverse, and the one that has never worked. It cannot be caught by
     * anything that looks for *near-black*, which is exactly why T24's heuristic
     * had to go rather than gain a second branch.
     */
    @Test
    fun explicitWhiteOnOurLightSurface_isRescued() {
        assertEquals(lightThemeText, onLight(Color.White))
    }

    // --- what must NOT be touched ---

    @Test
    fun aDocumentChosePairing_isRenderedAsWritten() {
        // Black on the author's yellow: they picked both colours, so the
        // contrast is theirs to own. Substituting here would put light text on a
        // light fill — worse than the problem it set out to fix.
        val yellow = Color(0xFFFFFF00)
        assertEquals(Color.Black, onDark(Color.Black, behind = yellow, filled = true))
        assertEquals(Color.White, onLight(Color.White, behind = Color(0xFF1F3864), filled = true))
    }

    @Test
    fun aQuietGreyIsLeftQuiet() {
        // Low contrast on purpose is a legitimate way to make something
        // secondary. This is a legibility rescue, not a contrast enforcer: a
        // viewer that "fixed" grey text would be rewriting the document.
        assertEquals(Color(0xFF999999), onLight(Color(0xFF999999)))
        assertEquals(Color(0xFF555555), onDark(Color(0xFF555555)))
    }

    @Test
    fun aColourThatIsFineIsLeftAlone() {
        val red = Color(0xFFFF0000) // 3.8:1 on our light surface
        assertEquals(red, onLight(red))
        val salmon = Color(0xFFFF6B6B) // 6.7:1 on our dark surface
        assertEquals(salmon, onDark(salmon))
    }

    // --- the measurement itself ---

    @Test
    fun contrastRatioMatchesTheWcagDefinition() {
        // Black on white is the maximum, 21:1; a colour against itself is 1:1.
        assertEquals(21f, TextLegibility.contrastRatio(Color.Black, Color.White), 0.05f)
        assertEquals(1f, TextLegibility.contrastRatio(Color.Red, Color.Red), 0.001f)
    }

    @Test
    fun contrastRatioIsSymmetric() {
        val a = Color(0xFF3366CC)
        val b = Color(0xFFEEEEEE)
        assertEquals(
            TextLegibility.contrastRatio(a, b),
            TextLegibility.contrastRatio(b, a),
            0.0001f,
        )
    }

    /**
     * The threshold has to sit in the gap between "invisible" and "deliberately
     * quiet", or the rule either misses the bug or starts rewriting documents.
     * If a future palette change closes that gap, this fails and the number has
     * to be reconsidered — rather than the rule quietly doing the wrong thing.
     */
    @Test
    fun theThresholdSitsInARealGap() {
        val invisible = maxOf(
            TextLegibility.contrastRatio(Color.Black, darkSurface),
            TextLegibility.contrastRatio(Color.White, lightSurface),
        )
        val deliberate = minOf(
            TextLegibility.contrastRatio(Color(0xFF999999), lightSurface),
            TextLegibility.contrastRatio(Color(0xFF555555), darkSurface),
        )
        assertTrue("invisible text measured $invisible, expected well under the threshold", invisible < 1.2f)
        assertTrue("quiet-but-intended text measured $deliberate", deliberate > 2f)
        assertTrue(TextLegibility.MIN_CONTRAST > invisible)
        assertTrue(TextLegibility.MIN_CONTRAST < deliberate)
    }

    /**
     * The T24 heuristic ran only in dark mode. Its replacement must not have
     * inherited that asymmetry — the same input either side of the theme line
     * should get the same *kind* of answer.
     */
    @Test
    fun theRuleIsNotDarkModeOnly() {
        assertNotEquals("white on light must not survive", Color.White, onLight(Color.White))
        assertNotEquals("black on dark must not survive", Color.Black, onDark(Color.Black))
    }
}
