package com.tikoncha.darcha.feature.viewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tikoncha.darcha.feature.viewer.R
import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.feature.viewer.mvi.CellRef
import com.tikoncha.darcha.feature.viewer.mvi.DocumentMeta
import com.tikoncha.darcha.feature.viewer.mvi.ScrollBounds
import com.tikoncha.darcha.feature.viewer.mvi.Viewport
import com.tikoncha.darcha.model.FormattedValueCache

/**
 * The grid screen: the document header, the [GridCanvas], and the sheet tabs.
 *
 * Nothing here reads the viewport during composition. [viewport] is a lambda the
 * Canvas calls inside its draw block, so dragging invalidates the draw phase
 * only — the header, the tabs and the layout are never recomposed while
 * scrolling (TECH_SPEC §9).
 */
@Composable
internal fun GridScreen(
    sheet: SheetSnapshot,
    docMeta: DocumentMeta,
    activeSheetId: Int,
    loadingSheetId: Int?,
    loadProgress: Float?,
    viewport: () -> Viewport,
    onOpenFile: () -> Unit,
    onScroll: (dx: Float, dy: Float) -> Unit,
    onFling: (vx: Float, vy: Float) -> Unit,
    onZoom: (scale: Float, focalX: Float, focalY: Float) -> Unit,
    onResetZoom: (focalX: Float, focalY: Float) -> Unit,
    onBoundsChanged: (ScrollBounds) -> Unit,
    onSelectSheet: (Int) -> Unit,
    selection: CellRef?,
    onSelect: (CellRef?) -> Unit,
    onStopMotion: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    var drawnCells by remember { mutableIntStateOf(0) }

    // The same formatter the Canvas draws with, so the bar can never disagree
    // with the cell — see SelectionBar for why the *displayed* string is what
    // gets copied.
    val dateNames = rememberDateNames()
    val formatted = remember(sheet.styles, sheet.sharedStrings, sheet.date1904, dateNames) {
        FormattedValueCache(
            styles = sheet.styles,
            strings = sheet.sharedStrings,
            date1904 = sheet.date1904,
            names = dateNames,
        )
    }

    // Keep the chrome clear of the status and navigation bars.
    Column(modifier = modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(docMeta.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${docMeta.rowCount} rows · ${docMeta.sheetNames.size} sheets · " +
                        "drawn $drawnCells cells",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = onOpenFile) { Text(stringResource(R.string.action_open_short)) }
        }

        GridCanvas(
            sheet = sheet,
            viewport = viewport,
            onScroll = onScroll,
            onFling = onFling,
            onZoom = onZoom,
            onResetZoom = onResetZoom,
            onBoundsChanged = onBoundsChanged,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            onDrawnCells = { drawnCells = it },
            // A lambda, not a value: the Canvas reads it inside its draw block,
            // so moving the selection repaints without recomposing this Column.
            selection = { selection },
            onSelect = onSelect,
            onStopMotion = onStopMotion,
        )

        if (selection != null) {
            SelectionBar(
                selection = selection,
                displayText = sheet.displayTextAt(selection, formatted),
            )
        }

        // Either the first sheet is still streaming in (T15.5) or another tab is
        // being read on demand (T15). Both keep the grid on screen and show the
        // bar underneath it.
        when {
            loadProgress != null -> LinearProgressIndicator(
                progress = { loadProgress },
                modifier = Modifier.fillMaxWidth(),
            )
            loadingSheetId != null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        SheetTabs(
            sheetNames = docMeta.sheetNames,
            activeSheetId = activeSheetId,
            loadingSheetId = loadingSheetId,
            onSelectSheet = onSelectSheet,
        )
    }
}

/**
 * The string shown for [cell] — exactly what the grid draws there.
 *
 * **Decision: the displayed text is what gets copied, not the raw value.** The
 * user selected this cell by *looking* at it: a date cell holds the serial
 * `45306`, and nobody who taps a cell reading `1/15/24` means to copy `45306`
 * into a message. Darcha is a viewer with no editing and no export, so the
 * spreadsheet-round-trip argument for raw values has nothing to round-trip into.
 *
 * The honest cost: `General` renders 11 significant digits, so copying a cell
 * that shows `0.333333333333` gives those digits and not the underlying double.
 * A value that is *displayed* rounded copies rounded. That follows from the same
 * principle rather than contradicting it — what you see is what you get — but it
 * is a real limitation and belongs written down rather than discovered.
 *
 * An empty cell yields `""`, and the bar's Copy button disables rather than
 * putting an empty string on the clipboard.
 */
private fun SheetSnapshot.displayTextAt(cell: CellRef, formatted: FormattedValueCache): String {
    val row = data.row(cell.row) ?: return ""
    val value = row.valueAt(cell.col) ?: return ""
    return formatted.format(value, row.styleIdAt(cell.col) ?: 0)
}

/**
 * The sheet tabs, in workbook order. Scrollable because a workbook may hold more
 * names than fit, and they can be long.
 */
@Composable
private fun SheetTabs(
    sheetNames: List<String>,
    activeSheetId: Int,
    loadingSheetId: Int?,
    onSelectSheet: (Int) -> Unit,
) {
    if (sheetNames.isEmpty()) return
    ScrollableTabRow(
        selectedTabIndex = activeSheetId.coerceIn(sheetNames.indices),
        edgePadding = 8.dp,
    ) {
        sheetNames.forEachIndexed { index, name ->
            Tab(
                selected = index == activeSheetId,
                // Ignore taps on a tab already being read, so a double tap cannot
                // queue the same parse twice.
                onClick = { if (index != loadingSheetId) onSelectSheet(index) },
                text = {
                    Text(
                        text = name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}
