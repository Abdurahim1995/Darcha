package com.tikoncha.darcha.feature.viewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tikoncha.darcha.feature.viewer.R
import com.tikoncha.darcha.feature.viewer.data.RecentDocument

/**
 * The home screen: recent documents, or an invitation to open one (T22).
 *
 * The list is deliberately short and honest — see [RecentDocument] for why an
 * entry that cannot be reopened is never written in the first place. What can
 * still happen is that a document goes away *after* it was remembered: the file
 * is deleted, or the app that provided it is uninstalled. Those rows say so and
 * offer to remove themselves, rather than waiting to fail when tapped.
 *
 * @param recents the list, newest first.
 * @param onOpenFile open the system picker.
 * @param onOpenRecent open a stored document by its id.
 * @param onForgetRecent drop a stored document from the list.
 */
@Composable
internal fun HomeScreen(
    recents: List<RecentDocument>,
    onOpenFile: () -> Unit,
    onOpenRecent: (String) -> Unit,
    onForgetRecent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 48.dp),
        )

        if (recents.isEmpty()) {
            EmptyRecents(onOpenFile)
            return@Column
        }

        Text(
            text = stringResource(R.string.home_prompt),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onOpenFile, modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)) {
            Text(stringResource(R.string.action_open_file))
        }

        Text(
            text = stringResource(R.string.home_recent_header),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(recents, key = { it.id }) { document ->
                RecentRow(
                    document = document,
                    onOpen = { onOpenRecent(document.id) },
                    onForget = { onForgetRecent(document.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

/** First launch, or after the last recent is removed. */
@Composable
private fun EmptyRecents(onOpenFile: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.home_prompt),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.home_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
        Button(onClick = onOpenFile) { Text(stringResource(R.string.action_open_file)) }
    }
}

/**
 * One recent.
 *
 * An unavailable row is not tappable — offering to open something that cannot
 * open is how a list starts lying — but it is always removable, so the list does
 * not accumulate rows the user can neither use nor get rid of.
 */
@Composable
private fun RecentRow(
    document: RecentDocument,
    onOpen: () -> Unit,
    onForget: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (document.available) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = document.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (document.available) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = if (document.available) {
                    document.sizeBytes?.let { formatSize(it) }
                        ?: stringResource(R.string.home_recent_generic_subtitle)
                } else {
                    stringResource(R.string.home_recent_unavailable)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (document.available) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        TextButton(onClick = onForget) { Text(stringResource(R.string.action_remove)) }
    }
}

/** A size a person can read, in the units a file manager would use. */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "${bytes / 1_024} KB"
    else -> "${"%.1f".format(bytes / (1_024.0 * 1_024.0))} MB"
}
