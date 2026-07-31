package com.tikoncha.darcha.feature.viewer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The grid's own colours (T24).
 *
 * Material's scheme covers buttons and surfaces; a spreadsheet also needs a
 * gridline, a header strip and a freeze marker, and those have to change with
 * the theme too or dark mode stops at the edge of the sheet.
 *
 * @property cellText the colour for text the document does not colour itself.
 * @property selection the selected cell's outline (T29). Drawn over the
 *   document's own colours, so it has to read against any fill an author might
 *   have used — hence the app's accent rather than anything derived from the
 *   sheet.
 */
@Immutable
internal data class GridColors(
    val gridLine: Color,
    val freezeLine: Color,
    val cellText: Color,
    val headerFill: Color,
    val headerText: Color,
    val selection: Color,
)

private val LightGrid = GridColors(
    gridLine = Color(0xFFD0D0D0),
    freezeLine = Color(0xFF9098A8),
    cellText = Color(0xFF202020),
    headerFill = Color(0xFFF2F2F2),
    headerText = Color(0xFF606060),
    selection = Color(0xFF6750A4),
)

private val DarkGrid = GridColors(
    gridLine = Color(0xFF3A3A40),
    freezeLine = Color(0xFF8A93A8),
    cellText = Color(0xFFE4E1E6),
    headerFill = Color(0xFF26232A),
    headerText = Color(0xFF9C97A4),
    selection = Color(0xFFD0BCFF),
)

internal val LocalGridColors = staticCompositionLocalOf { LightGrid }

/** The grid palette for the current theme. */
internal val gridColors: GridColors
    @Composable @ReadOnlyComposable get() = LocalGridColors.current

// Darcha's purple, the one already on the buttons, expressed as a scheme.
private val Purple = Color(0xFF6750A4)
private val PurpleLight = Color(0xFFD0BCFF)

private val LightScheme = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    surface = Color(0xFFFDF7FF),
    onSurface = Color(0xFF1D1B20),
    onSurfaceVariant = Color(0xFF49454F),
    background = Color(0xFFFDF7FF),
    onBackground = Color(0xFF1D1B20),
    error = Color(0xFFB3261E),
)

private val DarkScheme = darkColorScheme(
    primary = PurpleLight,
    onPrimary = Color(0xFF381E72),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    onSurfaceVariant = Color(0xFFCAC4D0),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    error = Color(0xFFF2B8B5),
)

/**
 * The app's Material 3 theme, light or dark by system setting (T24).
 *
 * Dynamic colour is deliberately not used. Darcha is a document viewer: the
 * wallpaper deciding what a gridline looks like would make the same spreadsheet
 * read differently on two phones, and the sheet is the product.
 */
@Composable
public fun DarchaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalGridColors provides if (darkTheme) DarkGrid else LightGrid) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            content = content,
        )
    }
}
