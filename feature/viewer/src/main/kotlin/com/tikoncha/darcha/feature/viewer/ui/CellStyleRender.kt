package com.tikoncha.darcha.feature.viewer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.tikoncha.darcha.model.CellStyle
import com.tikoncha.darcha.model.CellValue
import com.tikoncha.darcha.model.HorizontalAlignment
import com.tikoncha.darcha.model.VerticalAlignment

/** A model colour as a Compose one — both are packed ARGB. */
internal fun com.tikoncha.darcha.model.Color.toCompose(): Color = Color(argb)

internal val CellStyle.fontWeight: FontWeight
    get() = if (bold) FontWeight.Bold else FontWeight.Normal

internal val CellStyle.fontStyle: FontStyle
    get() = if (italic) FontStyle.Italic else FontStyle.Normal

/**
 * Where a value sits horizontally in its cell.
 *
 * `GENERAL` is not "left": Excel aligns by the *type* of the value, which is why
 * a column of numbers lines up on its digits while a column of names lines up on
 * its first letter. Getting this wrong makes a spreadsheet unreadable even when
 * every other pixel is right.
 *
 * The alignments Darcha does not implement — `FILL`, `JUSTIFY`,
 * `CENTER_CONTINUOUS`, `DISTRIBUTED` — need text layout this renderer has no
 * concept of yet, and fall back to the same edge `LEFT` would use.
 */
internal fun CellStyle.resolveAlignment(value: CellValue): TextAlign =
    when (horizontalAlignment) {
        HorizontalAlignment.LEFT -> TextAlign.Left
        HorizontalAlignment.CENTER -> TextAlign.Center
        HorizontalAlignment.RIGHT -> TextAlign.Right
        HorizontalAlignment.GENERAL -> when (value) {
            is CellValue.Number -> TextAlign.Right
            // Excel centres these, and it reads as "this is not data you typed".
            is CellValue.Bool, is CellValue.Error -> TextAlign.Center
            is CellValue.SharedText, is CellValue.InlineText -> TextAlign.Left
        }
        HorizontalAlignment.FILL,
        HorizontalAlignment.JUSTIFY,
        HorizontalAlignment.CENTER_CONTINUOUS,
        HorizontalAlignment.DISTRIBUTED,
        -> TextAlign.Left
    }

/**
 * The y offset of a text layout of [textHeight] inside a cell of [cellHeight].
 *
 * Excel's default is `BOTTOM`, not centre — text sits on the bottom edge of its
 * row, which is what makes a row of mixed font sizes share a baseline.
 */
internal fun CellStyle.verticalOffset(cellHeight: Float, textHeight: Float, padding: Float): Float {
    val free = (cellHeight - textHeight).coerceAtLeast(0f)
    return when (verticalAlignment) {
        VerticalAlignment.TOP -> padding.coerceAtMost(free)
        VerticalAlignment.CENTER -> free / 2f
        // Justified and distributed need multi-line layout; bottom is the honest
        // approximation and matches the default.
        VerticalAlignment.BOTTOM,
        VerticalAlignment.JUSTIFY,
        VerticalAlignment.DISTRIBUTED,
        -> (free - padding).coerceAtLeast(0f)
    }
}

/** The x offset of a text layout of [textWidth] inside a cell of [cellWidth]. */
internal fun horizontalOffset(
    align: TextAlign,
    cellWidth: Float,
    textWidth: Float,
    padding: Float,
): Float = when (align) {
    TextAlign.Right -> (cellWidth - textWidth - padding).coerceAtLeast(padding)
    TextAlign.Center -> ((cellWidth - textWidth) / 2f).coerceAtLeast(padding)
    else -> padding
}
