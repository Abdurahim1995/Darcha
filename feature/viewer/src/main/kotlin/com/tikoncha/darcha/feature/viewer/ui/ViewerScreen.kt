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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tikoncha.darcha.feature.viewer.mvi.ViewerState
import com.tikoncha.darcha.model.ErrorKind

/**
 * The viewer screen: a stateless render of [ViewerState] (TECH_SPEC §10).
 *
 * This is the T11 placeholder — it reports what was loaded rather than drawing
 * it. The Canvas grid replaces the [ViewerState.Ready] branch in T13, and the
 * friendly error screens arrive in T23.
 *
 * @param state what to render.
 * @param onOpenFile invoked when the user asks for the file picker.
 * @param onRetry invoked to retry the last failed load.
 */
@Composable
public fun ViewerScreen(
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

            is ViewerState.Ready -> {
                Text(state.docMeta.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    // The T11 acceptance line: counts, not cells.
                    text = "Loaded: ${state.docMeta.rowCount} rows · ${state.docMeta.sheetNames.size} sheets",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
                Button(onClick = onOpenFile) { Text("Open another file") }
            }

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
