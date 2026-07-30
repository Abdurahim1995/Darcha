package com.tikoncha.darcha.feature.viewer.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tikoncha.darcha.feature.viewer.geometry.GridGeometry
import com.tikoncha.darcha.feature.viewer.geometry.VisibleRange
import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.feature.viewer.mvi.ScrollBounds
import com.tikoncha.darcha.feature.viewer.mvi.Viewport
import com.tikoncha.darcha.model.DEFAULT_MAX_DIGIT_WIDTH
import com.tikoncha.darcha.model.SheetData
import com.tikoncha.darcha.model.StringTable
import kotlin.math.roundToInt

/**
 * The grid, drawn as a single Canvas (TECH_SPEC §9).
 *
 * Only the cells inside [GridGeometry.visibleRange] are visited, so cost tracks
 * the size of the window rather than the size of the sheet — a million-row sheet
 * draws the same few hundred cells as a ten-row one. Around the body sit fixed
 * header strips: column letters along the top, row numbers down the left.
 *
 * This is the static T13 renderer: no gestures (T14), no cell styling (T17), no
 * merged cells (T18) or frozen panes (T19).
 *
 * @param state the sheet to draw.
 * @param onDrawnCells reports how many body cells the last pass visited, so the
 *   caller can show it and prove culling.
 */
@Composable
public fun GridCanvas(
    sheet: SheetSnapshot,
    viewport: () -> Viewport,
    onScroll: (dx: Float, dy: Float) -> Unit,
    onFling: (vx: Float, vy: Float) -> Unit,
    onBoundsChanged: (ScrollBounds) -> Unit,
    modifier: Modifier = Modifier,
    onDrawnCells: (Int) -> Unit = {},
) {
    val density = LocalDensity.current.density
    val layout = sheet.layout

    // Geometry is built in *physical* pixels by folding the display density into
    // its two unit converters, so its output can be used as Canvas coordinates
    // directly. Its own arithmetic stays device-independent (TECH_SPEC §9.2).
    val geometry = remember(layout, density) {
        GridGeometry(
            layout = layout,
            maxDigitWidth = (DEFAULT_MAX_DIGIT_WIDTH * density).roundToInt(),
            pointToPixel = GridGeometry.POINTS_TO_PIXELS_96DPI * density,
        )
    }

    val textMeasurer = rememberTextMeasurer()
    // Deliberately not keyed on the sheet: entries are keyed by (text, zoom) and
    // the text style is fixed, so a measurement stays valid whatever sheet it
    // came from, and the cache is a bounded LRU. Keying it on the layout would
    // now throw the measurements away on every partial emission of a streaming
    // parse (T15.6) — exactly when they are being used most.
    val textCache = remember { CellTextCache() }

    val rowHeaderWidth = with(LocalDensity.current) { ROW_HEADER_WIDTH.toPx() }
    val columnHeaderHeight = with(LocalDensity.current) { COLUMN_HEADER_HEIGHT.toPx() }

    // How far this sheet can scroll. Computed once per sheet — the full grid is
    // 16k x 1M, so the *used* range is what makes scrolling feel finite.
    val used = remember(sheet.data) { sheet.data.usedBounds() }
    LaunchedEffect(geometry, used) {
        onBoundsChanged(
            ScrollBounds(
                maxScrollX = geometry.columnOffset(used.lastColumn),
                maxScrollY = geometry.rowOffset(used.lastRow),
            ),
        )
    }

    val gestures = Modifier.pointerInput(geometry) {
        val tracker = VelocityTracker()
        detectDragGestures(
            onDragStart = { tracker.resetTracking() },
            onDragCancel = { tracker.resetTracking() },
            onDragEnd = {
                val velocity = tracker.calculateVelocity()
                val zoom = viewport().zoom
                // Screen px/s -> content px/s, and inverted: flicking left sends
                // the viewport right (TECH_SPEC §9.2).
                onFling(-velocity.x / zoom, -velocity.y / zoom)
            },
            onDrag = { change, dragAmount ->
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                val zoom = viewport().zoom
                onScroll(-dragAmount.x / zoom, -dragAmount.y / zoom)
            },
        )
    }

    Canvas(modifier = modifier.then(gestures)) {
        // Read inside the draw block: a viewport change then invalidates only the
        // draw phase, never recomposing the surrounding chrome.
        val current = viewport()
        val bodyWidth = (size.width - rowHeaderWidth).coerceAtLeast(0f)
        val bodyHeight = (size.height - columnHeaderHeight).coerceAtLeast(0f)
        val range = geometry.visibleRange(current, bodyWidth, bodyHeight)

        drawBody(
            range = range,
            geometry = geometry,
            viewport = current,
            sheetData = sheet.data,
            strings = sheet.sharedStrings,
            originX = rowHeaderWidth,
            originY = columnHeaderHeight,
            textMeasurer = textMeasurer,
            cache = textCache,
        )
        drawHeaders(
            range = range,
            geometry = geometry,
            viewport = current,
            rowHeaderWidth = rowHeaderWidth,
            columnHeaderHeight = columnHeaderHeight,
            textMeasurer = textMeasurer,
            cache = textCache,
        )

        val drawn = range.cellCount
        if (drawn != lastLoggedCellCount) {
            lastLoggedCellCount = drawn
            // Proof of culling: this stays small and steady however far the
            // viewport travels into a large sheet.
            Log.d(
                LOG_TAG,
                "visible ${range.rowCount}x${range.columnCount} = $drawn cells " +
                    "(rows ${range.firstRow}..${range.lastRow}, cols ${range.firstColumn}..${range.lastColumn})",
            )
        }
        onDrawnCells(drawn)
    }
}

/** Cells, gridlines and values for the visible block. */
private fun DrawScope.drawBody(
    range: VisibleRange,
    geometry: GridGeometry,
    viewport: Viewport,
    sheetData: SheetData,
    strings: StringTable,
    originX: Float,
    originY: Float,
    textMeasurer: TextMeasurer,
    cache: CellTextCache,
) {
    clipRect(left = originX, top = originY, right = size.width, bottom = size.height) {
        // Vertical gridlines, one per visible column boundary.
        for (column in range.firstColumn..range.lastColumn + 1) {
            val x = originX + geometry.screenXOf(column, viewport)
            if (x in originX..size.width) {
                drawLine(GRID_LINE, Offset(x, originY), Offset(x, size.height), GRID_STROKE)
            }
        }
        for (row in range.firstRow..range.lastRow + 1) {
            val y = originY + geometry.screenYOf(row, viewport)
            if (y in originY..size.height) {
                drawLine(GRID_LINE, Offset(originX, y), Offset(size.width, y), GRID_STROKE)
            }
        }

        // Values. Rows are sparse, so absent rows cost nothing.
        for (row in range.firstRow..range.lastRow) {
            val cells = sheetData.row(row) ?: continue
            val top = originY + geometry.screenYOf(row, viewport)
            val height = geometry.screenHeightOf(row, viewport)
            for (i in cells.columns.indices) {
                val column = cells.columns[i]
                if (column < range.firstColumn) continue
                if (column > range.lastColumn) break // columns are sorted ascending
                val text = cells.values[i].displayText(strings)
                if (text.isEmpty()) continue
                drawCellText(
                    text = text,
                    left = originX + geometry.screenXOf(column, viewport),
                    top = top,
                    width = geometry.screenWidthOf(column, viewport),
                    height = height,
                    zoom = viewport.zoom,
                    textMeasurer = textMeasurer,
                    cache = cache,
                    color = CELL_TEXT,
                )
            }
        }
    }
}

/** Fixed column-letter and row-number strips. */
private fun DrawScope.drawHeaders(
    range: VisibleRange,
    geometry: GridGeometry,
    viewport: Viewport,
    rowHeaderWidth: Float,
    columnHeaderHeight: Float,
    textMeasurer: TextMeasurer,
    cache: CellTextCache,
) {
    drawRect(HEADER_FILL, Offset.Zero, Size(size.width, columnHeaderHeight))
    drawRect(HEADER_FILL, Offset.Zero, Size(rowHeaderWidth, size.height))

    clipRect(left = rowHeaderWidth, top = 0f, right = size.width, bottom = columnHeaderHeight) {
        for (column in range.firstColumn..range.lastColumn) {
            drawCellText(
                text = columnLabel(column),
                left = rowHeaderWidth + geometry.screenXOf(column, viewport),
                top = 0f,
                width = geometry.screenWidthOf(column, viewport),
                height = columnHeaderHeight,
                zoom = viewport.zoom,
                textMeasurer = textMeasurer,
                cache = cache,
                color = HEADER_TEXT,
            )
        }
    }

    clipRect(left = 0f, top = columnHeaderHeight, right = rowHeaderWidth, bottom = size.height) {
        for (row in range.firstRow..range.lastRow) {
            drawCellText(
                text = (row + 1).toString(),
                left = 0f,
                top = columnHeaderHeight + geometry.screenYOf(row, viewport),
                width = rowHeaderWidth,
                height = geometry.screenHeightOf(row, viewport),
                zoom = viewport.zoom,
                textMeasurer = textMeasurer,
                cache = cache,
                color = HEADER_TEXT,
            )
        }
    }

    // Separators between the strips and the body.
    drawLine(GRID_LINE, Offset(0f, columnHeaderHeight), Offset(size.width, columnHeaderHeight), GRID_STROKE)
    drawLine(GRID_LINE, Offset(rowHeaderWidth, 0f), Offset(rowHeaderWidth, size.height), GRID_STROKE)
}

/** Draw [text] inside its cell rect, measured through [cache] and clipped to fit. */
private fun DrawScope.drawCellText(
    text: String,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    zoom: Float,
    textMeasurer: TextMeasurer,
    cache: CellTextCache,
    color: Color,
) {
    if (width <= 0f || height <= 0f) return
    // TextStyle is built inside the lambda so a cache hit — the common case —
    // allocates nothing on the per-cell hot path.
    val layoutResult = cache.get(text, zoom) {
        textMeasurer.measure(
            text = text,
            style = TextStyle(fontSize = CELL_FONT_SIZE * zoom, color = color),
            maxLines = 1,
            softWrap = false,
            // Unbounded on purpose: the layout is measured once at its natural
            // width and then clipped to the cell below, so one measurement can
            // be reused for the same text in a narrower or wider column.
        )
    }
    // Clip so a long value cannot bleed into the neighbouring cell.
    clipRect(left = left, top = top, right = left + width, bottom = top + height) {
        drawText(
            textLayoutResult = layoutResult,
            color = color,
            topLeft = Offset(
                x = left + CELL_PADDING,
                y = top + (height - layoutResult.size.height) / 2f,
            ),
        )
    }
}

/** Last count written to logcat, so an unchanged viewport does not spam it. */
private var lastLoggedCellCount = -1

private const val LOG_TAG = "Darcha.Grid"
private val ROW_HEADER_WIDTH = 44.dp
private val COLUMN_HEADER_HEIGHT = 22.dp
private val CELL_FONT_SIZE = 11.sp
private const val CELL_PADDING = 4f
private const val GRID_STROKE = 1f
private val GRID_LINE = Color(0xFFD0D0D0)
private val CELL_TEXT = Color(0xFF202020)
private val HEADER_FILL = Color(0xFFF2F2F2)
private val HEADER_TEXT = Color(0xFF606060)

/** The last populated row and column of a sheet. */
internal data class UsedBounds(val lastRow: Int, val lastColumn: Int)

/**
 * Find the sheet's used range — one pass over the sparse rows, done once per
 * sheet rather than per frame. Columns within a row are sorted, so the last
 * entry is the rightmost.
 */
internal fun SheetData.usedBounds(): UsedBounds {
    var lastRow = 0
    var lastColumn = 0
    for ((row, cells) in rows) {
        if (row > lastRow) lastRow = row
        if (cells.size > 0) {
            val rightmost = cells.columns[cells.size - 1]
            if (rightmost > lastColumn) lastColumn = rightmost
        }
    }
    return UsedBounds(lastRow, lastColumn)
}
