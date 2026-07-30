package com.tikoncha.darcha.feature.viewer.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import com.tikoncha.darcha.feature.viewer.geometry.GridGeometry
import com.tikoncha.darcha.feature.viewer.geometry.MergeIndex
import com.tikoncha.darcha.feature.viewer.geometry.Pane
import com.tikoncha.darcha.feature.viewer.geometry.PaneRegion
import com.tikoncha.darcha.feature.viewer.geometry.PaneRegions
import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.feature.viewer.mvi.ScrollBounds
import com.tikoncha.darcha.feature.viewer.mvi.Viewport
import com.tikoncha.darcha.model.CellStyle
import com.tikoncha.darcha.model.DEFAULT_MAX_DIGIT_WIDTH
import com.tikoncha.darcha.model.FormattedValueCache
import com.tikoncha.darcha.model.SheetData
import com.tikoncha.darcha.model.StyleTable
import kotlin.math.roundToInt

/**
 * The grid, drawn as a single Canvas (TECH_SPEC §9).
 *
 * Only the cells inside [GridGeometry.visibleRange] are visited, so cost tracks
 * the size of the window rather than the size of the sheet — a million-row sheet
 * draws the same few hundred cells as a ten-row one. Around the body sit fixed
 * header strips: column letters along the top, row numbers down the left.
 *
 * Cell styling is applied here (T17): fills, bold/italic, text colour and
 * alignment, each clipped to its cell. Merged ranges are drawn once at their
 * anchor across the whole span, with the covered cells skipped (T18). Frozen
 * panes (T19) are still to come.
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

    // Both caches are keyed on the workbook's tables rather than on the sheet:
    // style ids and shared-string indices mean nothing outside the workbook they
    // came from, but they do not change while one streams in — so a partial
    // paint keeps everything measured so far (T15.6) and a new document starts
    // clean.
    val textCache = remember(sheet.styles) { CellTextCache<TextLayoutResult>() }

    // Built once per sheet, not per frame. Merges arrive only when the parse
    // completes (<mergeCells> follows <sheetData>, TECH_SPEC §7), so during a
    // progressive load this is empty and the cells draw unmerged until the last
    // chunk lands.
    val merges = remember(layout.merges) { MergeIndex.of(layout.merges) }

    // Frozen panes, unlike merges, are known from the first chunk (T19), so the
    // grid never re-splits mid-parse.
    val panes = remember(geometry, layout.frozenPanes) {
        PaneRegions(geometry, layout.frozenPanes)
    }

    // What a merged span paints over the gridlines it hides, when its anchor
    // carries no fill of its own.
    val surface = MaterialTheme.colorScheme.surface
    val formatted = remember(sheet.styles, sheet.sharedStrings, sheet.date1904) {
        FormattedValueCache(
            styles = sheet.styles,
            strings = sheet.sharedStrings,
            date1904 = sheet.date1904,
        )
    }

    val rowHeaderWidth = with(LocalDensity.current) { ROW_HEADER_WIDTH.toPx() }
    val columnHeaderHeight = with(LocalDensity.current) { COLUMN_HEADER_HEIGHT.toPx() }

    // How far this sheet can scroll. Computed once per sheet — the full grid is
    // 16k x 1M, so the *used* range is what makes scrolling feel finite.
    val used = remember(sheet.data) { sheet.data.usedBounds() }
    LaunchedEffect(geometry, used, panes) {
        onBoundsChanged(
            ScrollBounds(
                maxScrollX = geometry.columnOffset(used.lastColumn),
                maxScrollY = geometry.rowOffset(used.lastRow),
                // The scrolling region starts past the frozen strips.
                minScrollX = panes.minScrollX,
                minScrollY = panes.minScrollY,
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
        val regions = panes.regions(
            viewport = current,
            originX = rowHeaderWidth,
            originY = columnHeaderHeight,
            width = size.width,
            height = size.height,
        )

        for (region in regions) {
            if (!region.isVisible) continue
            drawRegion(
                region = region,
                geometry = geometry,
                sheetData = sheet.data,
                styles = sheet.styles,
                formatted = formatted,
                merges = merges,
                surface = surface,
                textMeasurer = textMeasurer,
                cache = textCache,
            )
        }

        drawHeaders(
            regions = regions,
            geometry = geometry,
            rowHeaderWidth = rowHeaderWidth,
            columnHeaderHeight = columnHeaderHeight,
            textMeasurer = textMeasurer,
            cache = textCache,
        )

        // The freeze separators, drawn once and last. Every region that meets a
        // split was positioned from the same Float this line uses, so the line
        // lands exactly on the seam instead of near it.
        drawFreezeSeparators(
            panes = panes,
            viewport = current,
            originX = rowHeaderWidth,
            originY = columnHeaderHeight,
        )

        // Every region's cells, not just the body's — freezing splits the same
        // window into pieces, so the total is what proves the culling.
        var drawn = 0
        for (region in regions) if (region.isVisible) drawn += region.cellCount
        if (drawn != lastLoggedCellCount) {
            lastLoggedCellCount = drawn
            val body = regions.first()
            // Proof of culling: this stays small and steady however far the
            // viewport travels into a large sheet.
            Log.d(
                LOG_TAG,
                "visible $drawn cells in ${regions.count { it.isVisible }} pane(s); " +
                    "body rows ${body.firstRow}..${body.lastRow}, " +
                    "cols ${body.firstColumn}..${body.lastColumn}; " +
                    "textCache ${textCache.size} entries, " +
                    "hit rate ${(textCache.hitRate * 100f).roundToInt()}%",
            )
        }
        onDrawnCells(drawn)
    }
}

/**
 * Fills, gridlines and values for one frozen region (TECH_SPEC §9).
 *
 * The region already carries its clip, its origin and a viewport with the frozen
 * axes zeroed, so this is the ordinary unfrozen draw — it never asks which pane
 * it is in. An unfrozen sheet is simply the case where the only region is the
 * body, covering everything.
 *
 * Order matters. Cell fills go under the gridlines the way they do in Excel;
 * then merged spans paint over both, because a merge has to hide the gridlines
 * running through it; then text, so nothing paints over a value.
 */
private fun DrawScope.drawRegion(
    region: PaneRegion,
    geometry: GridGeometry,
    sheetData: SheetData,
    styles: StyleTable,
    formatted: FormattedValueCache,
    merges: MergeIndex,
    surface: Color,
    textMeasurer: TextMeasurer,
    cache: CellTextCache<TextLayoutResult>,
) {
    val viewport = region.viewport
    val originX = region.originX
    val originY = region.originY

    clipRect(left = region.left, top = region.top, right = region.right, bottom = region.bottom) {
        // A frozen strip has to be opaque: the body region is drawn first and
        // scrolls underneath it.
        if (region.pane != Pane.BODY) {
            drawRect(
                color = surface,
                topLeft = Offset(region.left, region.top),
                size = Size(region.right - region.left, region.bottom - region.top),
            )
        }

        // Fills first, so gridlines stay visible on top of a filled cell. Covered
        // cells are skipped — their span is painted with the anchor's fill below.
        for (row in region.firstRow..region.lastRow) {
            val cells = sheetData.row(row) ?: continue
            val top = originY + geometry.screenYOf(row, viewport)
            val height = geometry.screenHeightOf(row, viewport)
            for (i in cells.columns.indices) {
                val column = cells.columns[i]
                if (column < region.firstColumn) continue
                if (column > region.lastColumn) break
                if (merges.indexOf(row, column) != MergeIndex.NONE) continue
                val fill = styles[cells.styleIds[i]]?.fillColor ?: continue
                drawRect(
                    color = fill.toCompose(),
                    topLeft = Offset(originX + geometry.screenXOf(column, viewport), top),
                    size = Size(geometry.screenWidthOf(column, viewport), height),
                )
            }
        }

        // Gridlines, one per visible boundary, drawn only inside this region.
        for (column in region.firstColumn..region.lastColumn + 1) {
            val x = originX + geometry.screenXOf(column, viewport)
            if (x in region.left..region.right) {
                drawLine(GRID_LINE, Offset(x, region.top), Offset(x, region.bottom), GRID_STROKE)
            }
        }
        for (row in region.firstRow..region.lastRow + 1) {
            val y = originY + geometry.screenYOf(row, viewport)
            if (y in region.top..region.bottom) {
                drawLine(GRID_LINE, Offset(region.left, y), Offset(region.right, y), GRID_STROKE)
            }
        }

        // Merged spans: one rect per range, painted over the gridlines crossing
        // it, then outlined so the merge still reads as a cell.
        if (!merges.isEmpty) {
            merges.forEachIntersecting(
                region.firstRow,
                region.lastRow,
                region.firstColumn,
                region.lastColumn,
            ) { index ->
                val anchorRow = merges.startRow(index)
                val anchorCol = merges.startCol(index)
                val left = originX + geometry.screenXOf(anchorCol, viewport)
                val top = originY + geometry.screenYOf(anchorRow, viewport)
                val width = geometry.spanWidthOf(anchorCol, merges.endCol(index), viewport)
                val height = geometry.spanHeightOf(anchorRow, merges.endRow(index), viewport)
                val fill = sheetData.styleIdAt(anchorRow, anchorCol)
                    ?.let { styles[it]?.fillColor?.toCompose() }
                    ?: surface
                drawRect(color = fill, topLeft = Offset(left, top), size = Size(width, height))
                drawRect(
                    color = GRID_LINE,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    style = Stroke(width = GRID_STROKE),
                )
            }
        }

        // Values. Rows are sparse, so absent rows cost nothing.
        for (row in region.firstRow..region.lastRow) {
            val cells = sheetData.row(row) ?: continue
            val top = originY + geometry.screenYOf(row, viewport)
            val height = geometry.screenHeightOf(row, viewport)
            for (i in cells.columns.indices) {
                val column = cells.columns[i]
                if (column < region.firstColumn) continue
                if (column > region.lastColumn) break // columns are sorted ascending
                val styleId = cells.styleIds[i]
                val value = cells.values[i]
                val text = formatted.format(value, styleId)
                if (text.isEmpty()) continue

                // A merged range shows one value, at its anchor, laid out across
                // the whole span. Whatever a covered cell holds is not shown —
                // Excel keeps such values but does not display them either.
                val merge = merges.indexOf(row, column)
                if (merge != MergeIndex.NONE && !merges.isAnchor(merge, row, column)) continue
                val cellLeft = originX + geometry.screenXOf(column, viewport)
                val cellWidth: Float
                val cellHeight: Float
                if (merge == MergeIndex.NONE) {
                    cellWidth = geometry.screenWidthOf(column, viewport)
                    cellHeight = height
                } else {
                    cellWidth = geometry.spanWidthOf(column, merges.endCol(merge), viewport)
                    cellHeight = geometry.spanHeightOf(row, merges.endRow(merge), viewport)
                }

                val style = styles[styleId] ?: CellStyle.DEFAULT
                drawCellText(
                    text = text,
                    styleId = styleId,
                    left = cellLeft,
                    top = top,
                    width = cellWidth,
                    height = cellHeight,
                    zoom = viewport.zoom,
                    textMeasurer = textMeasurer,
                    cache = cache,
                    color = style.fontColor?.toCompose() ?: CELL_TEXT,
                    weight = style.fontWeight,
                    italic = style.fontStyle,
                    align = style.resolveAlignment(value),
                    verticalOffset = { textHeight ->
                        style.verticalOffset(cellHeight, textHeight, CELL_PADDING)
                    },
                )
            }
        }
    }
}

/**
 * The two lines marking where the sheet is frozen.
 *
 * Drawn once, after every region, and derived from the same frozen extent the
 * regions were positioned from — so the seam is covered by a line that is
 * exactly on it, at any zoom, rather than one that has been rounded to a
 * neighbouring pixel.
 */
private fun DrawScope.drawFreezeSeparators(
    panes: PaneRegions,
    viewport: Viewport,
    originX: Float,
    originY: Float,
) {
    if (!panes.isFrozen) return
    val splitX = (originX + panes.frozenWidth(viewport)).coerceIn(originX, size.width)
    val splitY = (originY + panes.frozenHeight(viewport)).coerceIn(originY, size.height)

    if (panes.minScrollX > 0f && splitX < size.width) {
        drawLine(FREEZE_LINE, Offset(splitX, 0f), Offset(splitX, size.height), FREEZE_STROKE)
    }
    if (panes.minScrollY > 0f && splitY < size.height) {
        drawLine(FREEZE_LINE, Offset(0f, splitY), Offset(size.width, splitY), FREEZE_STROKE)
    }
}

/**
 * The fixed column-letter and row-number strips.
 *
 * They have to be split the same way the grid is (TECH_SPEC §9): if column A is
 * frozen, its letter must stay put while the letters to its right scroll past.
 * Each strip therefore reuses the regions rather than deriving its own split —
 * the letter over a column is drawn from that column's own region, so a header
 * can never disagree with the cells beneath it.
 */
private fun DrawScope.drawHeaders(
    regions: List<PaneRegion>,
    geometry: GridGeometry,
    rowHeaderWidth: Float,
    columnHeaderHeight: Float,
    textMeasurer: TextMeasurer,
    cache: CellTextCache<TextLayoutResult>,
) {
    drawRect(HEADER_FILL, Offset.Zero, Size(size.width, columnHeaderHeight))
    drawRect(HEADER_FILL, Offset.Zero, Size(rowHeaderWidth, size.height))

    // Column letters: one pass per region that owns columns, clipped to that
    // region's horizontal span so a scrolling letter cannot slide over a frozen
    // one. CORNER and LEFT share the frozen columns, TOP and BODY the scrolling
    // ones, so one of each pair is enough.
    for (region in regions) {
        if (!region.isVisible) continue
        if (region.pane == Pane.CORNER || region.pane == Pane.TOP) continue
        clipRect(
            left = region.left,
            top = 0f,
            right = region.right,
            bottom = columnHeaderHeight,
        ) {
            for (column in region.firstColumn..region.lastColumn) {
                drawCellText(
                    text = columnLabel(column),
                    styleId = CellTextCache.HEADER_STYLE_ID,
                    align = TextAlign.Center,
                    left = region.originX + geometry.screenXOf(column, region.viewport),
                    top = 0f,
                    width = geometry.screenWidthOf(column, region.viewport),
                    height = columnHeaderHeight,
                    zoom = region.viewport.zoom,
                    textMeasurer = textMeasurer,
                    cache = cache,
                    color = HEADER_TEXT,
                )
            }
        }
    }

    // Row numbers: the mirror image — CORNER and TOP share the frozen rows,
    // LEFT and BODY the scrolling ones.
    for (region in regions) {
        if (!region.isVisible) continue
        if (region.pane == Pane.CORNER || region.pane == Pane.LEFT) continue
        clipRect(
            left = 0f,
            top = region.top,
            right = rowHeaderWidth,
            bottom = region.bottom,
        ) {
            for (row in region.firstRow..region.lastRow) {
                drawCellText(
                    text = (row + 1).toString(),
                    styleId = CellTextCache.HEADER_STYLE_ID,
                    align = TextAlign.Center,
                    left = 0f,
                    top = region.originY + geometry.screenYOf(row, region.viewport),
                    width = rowHeaderWidth,
                    height = geometry.screenHeightOf(row, region.viewport),
                    zoom = region.viewport.zoom,
                    textMeasurer = textMeasurer,
                    cache = cache,
                    color = HEADER_TEXT,
                )
            }
        }
    }

    // Separators between the strips and the body.
    drawLine(GRID_LINE, Offset(0f, columnHeaderHeight), Offset(size.width, columnHeaderHeight), GRID_STROKE)
    drawLine(GRID_LINE, Offset(rowHeaderWidth, 0f), Offset(rowHeaderWidth, size.height), GRID_STROKE)
}

/**
 * Draw [text] inside its cell rect, measured through [cache] and clipped to fit.
 *
 * The layout is measured **unbounded** and positioned afterwards, so one
 * measurement serves the same text in any column width — the clip below is what
 * stops a long value bleeding into its neighbour.
 */
private fun DrawScope.drawCellText(
    text: String,
    styleId: Int,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    zoom: Float,
    textMeasurer: TextMeasurer,
    cache: CellTextCache<TextLayoutResult>,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    italic: FontStyle = FontStyle.Normal,
    align: TextAlign = TextAlign.Left,
    verticalOffset: (textHeight: Float) -> Float = { textHeight -> (height - textHeight) / 2f },
) {
    if (width <= 0f || height <= 0f) return
    // TextStyle is built inside the lambda so a cache hit — the common case —
    // allocates nothing on the per-cell hot path.
    val layoutResult = cache.get(text, styleId, zoom) {
        textMeasurer.measure(
            text = text,
            style = TextStyle(
                fontSize = CELL_FONT_SIZE * zoom,
                color = color,
                fontWeight = weight,
                fontStyle = italic,
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
    // Clip so a long value cannot bleed into the neighbouring cell.
    clipRect(left = left, top = top, right = left + width, bottom = top + height) {
        drawText(
            textLayoutResult = layoutResult,
            color = color,
            topLeft = Offset(
                x = left + horizontalOffset(align, width, layoutResult.size.width.toFloat(), CELL_PADDING),
                y = top + verticalOffset(layoutResult.size.height.toFloat()),
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

/** The freeze split, darker than a gridline so the reader can see the sheet is frozen. */
private val FREEZE_LINE = Color(0xFF9098A8)
private const val FREEZE_STROKE = 2f
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
