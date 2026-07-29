package com.tikoncha.darcha.feature.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tikoncha.darcha.feature.viewer.mvi.ViewerState

/**
 * The T13 grid screen: a header line, the [GridCanvas], and temporary debug
 * controls that move the viewport.
 *
 * The controls exist only to exercise culling before gestures arrive in T14 —
 * they dispatch the same `Scroll` and `Zoom` intents a drag eventually will, so
 * the flow stays unidirectional (TECH_SPEC §10).
 */
@Composable
internal fun GridScreen(
    state: ViewerState.Ready,
    onOpenFile: () -> Unit,
    onScroll: (dx: Float, dy: Float) -> Unit,
    onZoom: (scale: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var drawnCells by remember { mutableIntStateOf(0) }
    val viewport = state.viewport

    // Keep the chrome clear of the status and navigation bars.
    Column(modifier = modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(state.docMeta.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${state.docMeta.rowCount} rows · " +
                        "${state.docMeta.sheetNames.size} sheets · drawn $drawnCells cells",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = onOpenFile) { Text("Open") }
        }

        GridCanvas(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            onDrawnCells = { drawnCells = it },
        )

        // Debug viewport controls — replaced by real gestures in T14.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { onScroll(-STEP, 0f) }) { Text("←") }
            OutlinedButton(onClick = { onScroll(STEP, 0f) }) { Text("→") }
            OutlinedButton(onClick = { onScroll(0f, -STEP) }) { Text("↑") }
            OutlinedButton(onClick = { onScroll(0f, STEP) }) { Text("↓") }
            OutlinedButton(onClick = { onZoom(1 / ZOOM_STEP) }) { Text("−") }
            OutlinedButton(onClick = { onZoom(ZOOM_STEP) }) { Text("+") }
        }
        Text(
            text = "x=${viewport.scrollX.toInt()} y=${viewport.scrollY.toInt()} " +
                "zoom=${"%.1f".format(viewport.zoom)}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
        )
    }
}

/** Content pixels moved per debug step — roughly two default columns. */
private const val STEP = 128f

/** Multiplier per zoom step. */
private const val ZOOM_STEP = 1.25f
