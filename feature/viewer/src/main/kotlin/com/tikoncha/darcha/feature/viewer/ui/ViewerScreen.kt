package com.tikoncha.darcha.feature.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tikoncha.darcha.feature.viewer.mvi.ScrollBounds
import com.tikoncha.darcha.feature.viewer.mvi.ViewerState
import com.tikoncha.darcha.feature.viewer.mvi.Viewport
import com.tikoncha.darcha.model.ErrorKind

/**
 * The viewer screen: a render of [ViewerState] (TECH_SPEC §10).
 *
 * Takes the state as a **holder** rather than a value so that scrolling, which
 * changes the viewport many times a second, does not recompose this tree. The
 * pieces that actually change shape — which screen to show, the open sheet — are
 * pulled out with `derivedStateOf`, which only fires when *they* change; the
 * viewport itself is passed down as a lambda and read in the draw phase.
 *
 * The friendly per-error screens arrive in T23.
 *
 * @param state holder for the current state.
 * @param onOpenFile invoked when the user asks for the file picker.
 * @param onRetry invoked to retry the last failed load.
 */
@Composable
public fun ViewerScreen(
    state: State<ViewerState>,
    onOpenFile: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onScroll: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onFling: (vx: Float, vy: Float) -> Unit = { _, _ -> },
    onZoom: (scale: Float) -> Unit = {},
    onBoundsChanged: (ScrollBounds) -> Unit = {},
) {
    // Stable slices: these change on load, not on every frame of a drag.
    val sheet by remember(state) { derivedStateOf { (state.value as? ViewerState.Ready)?.sheet } }
    val docMeta by remember(state) { derivedStateOf { (state.value as? ViewerState.Ready)?.docMeta } }

    val readySheet = sheet
    val readyMeta = docMeta
    if (readySheet != null && readyMeta != null) {
        GridScreen(
            sheet = readySheet,
            docMeta = readyMeta,
            viewport = {
                (state.value as? ViewerState.Ready)?.viewport ?: Viewport.INITIAL
            },
            onOpenFile = onOpenFile,
            onScroll = onScroll,
            onFling = onFling,
            onZoom = onZoom,
            onBoundsChanged = onBoundsChanged,
            modifier = modifier,
        )
        return
    }

    NonGridStates(
        state = state.value,
        onOpenFile = onOpenFile,
        onRetry = onRetry,
        modifier = modifier,
    )
}

/** Idle, Parsing and Error — the states that are a centred column of text. */
@Composable
private fun NonGridStates(
    state: ViewerState,
    onOpenFile: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            ViewerState.Idle -> {
                Text("Darcha", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "Open an .xlsx file to view it.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
                Button(onClick = onOpenFile) { Text("Open file") }
            }

            is ViewerState.Parsing -> {
                Text("Parsing…", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
            }

            // Handled by GridScreen before we get here.
            is ViewerState.Ready -> Unit

            is ViewerState.Error -> {
                Text("Could not open the file", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = state.kind.describe(),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
                Button(onClick = onRetry) { Text("Retry") }
                Button(
                    onClick = onOpenFile,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Open another file") }
            }
        }
    }
}

/**
 * A one-line explanation for [ErrorKind]. Placeholder copy in English; T23
 * replaces this with proper string resources and per-kind screens, and T24
 * localizes it.
 */
private fun ErrorKind.describe(): String = when (this) {
    is ErrorKind.Encrypted -> "This file is password-protected, so it cannot be opened."
    is ErrorKind.Corrupted -> "This file is damaged or is not a valid .xlsx document."
    is ErrorKind.Unsupported -> "This file format is not supported."
    is ErrorKind.TooLarge -> "This file is too large to open on this device."
}
