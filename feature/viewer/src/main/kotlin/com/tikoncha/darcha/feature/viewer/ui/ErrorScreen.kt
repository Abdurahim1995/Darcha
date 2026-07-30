package com.tikoncha.darcha.feature.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tikoncha.darcha.feature.viewer.R
import com.tikoncha.darcha.model.ErrorKind

/**
 * What each failure looks like to the person holding the phone (T23).
 *
 * Kept as a separate type from [ErrorKind] on purpose. The taxonomy is the
 * parser's vocabulary — precise, and useless to a reader; this is the
 * translation into what happened and what to do about it. Adding a kind to the
 * taxonomy therefore forces a decision here rather than falling through to
 * something vague, because the `when` below is exhaustive.
 *
 * @property retryable whether offering "Retry" makes sense. It does not for a
 *   file that is damaged or unsupported: the same bytes will fail the same way,
 *   and a button that cannot work is worse than no button.
 */
internal data class ErrorPresentation(
    val icon: ImageVector,
    val titleRes: Int,
    val bodyRes: Int,
    val retryable: Boolean,
)

/** The screen for [kind]. */
internal fun ErrorKind.presentation(): ErrorPresentation = when (this) {
    is ErrorKind.Encrypted -> ErrorPresentation(
        icon = Icons.Filled.Lock,
        titleRes = R.string.error_encrypted_title,
        bodyRes = R.string.error_encrypted_body,
        // The password will not have gone away by the time they tap it.
        retryable = false,
    )

    is ErrorKind.Corrupted -> ErrorPresentation(
        icon = Icons.Filled.Warning,
        titleRes = R.string.error_corrupted_title,
        bodyRes = R.string.error_corrupted_body,
        retryable = false,
    )

    is ErrorKind.Unsupported -> ErrorPresentation(
        icon = Icons.Filled.Info,
        titleRes = R.string.error_unsupported_title,
        bodyRes = R.string.error_unsupported_body,
        retryable = false,
    )

    is ErrorKind.TooLarge -> ErrorPresentation(
        icon = Icons.Filled.Warning,
        titleRes = R.string.error_too_large_title,
        bodyRes = R.string.error_too_large_body,
        retryable = false,
    )

    // The one worth retrying: a grant can be re-granted and a file can come
    // back, so the same tap really might work the second time.
    is ErrorKind.Unreadable -> ErrorPresentation(
        icon = Icons.Filled.Refresh,
        titleRes = R.string.error_unreadable_title,
        bodyRes = R.string.error_unreadable_body,
        retryable = true,
    )
}

/** A full-screen explanation of [kind], with a way out of it. */
@Composable
internal fun ErrorScreen(
    kind: ErrorKind,
    onOpenFile: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = kind.presentation()
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = presentation.icon,
            contentDescription = stringResource(R.string.cd_error_icon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(presentation.titleRes),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = stringResource(presentation.bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, bottom = 28.dp),
        )

        // "Open another file" is always the way forward, so it is the primary
        // action; retry, where it applies, sits under it.
        Button(onClick = onOpenFile) { Text(stringResource(R.string.action_open_another)) }
        if (presentation.retryable) {
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}
