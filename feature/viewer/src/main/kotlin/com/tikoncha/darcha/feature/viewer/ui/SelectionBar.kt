package com.tikoncha.darcha.feature.viewer.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tikoncha.darcha.feature.viewer.R
import com.tikoncha.darcha.feature.viewer.mvi.CellRef

/**
 * What is selected, and the way to copy it (T29).
 *
 * It earns its row of screen twice over. A cell narrower than its contents is
 * clipped in the grid — that is what a spreadsheet does — so this is the only
 * place a long value can actually be read. And it makes copying discoverable: a
 * long-press would have been tidier and nobody would ever have found it.
 *
 * Absent when nothing is selected, so the grid keeps its full height until the
 * user asks for this.
 */
@Composable
internal fun SelectionBar(
    selection: CellRef,
    displayText: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    HorizontalDivider()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${columnLabel(selection.col)}${selection.row + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = displayText,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        // A labelled button rather than an icon. `ContentCopy` lives in
        // material-icons-extended, which is a dependency this does not justify,
        // and a word is more discoverable than a glyph anyway.
        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(displayText))
                confirmCopy(context)
            },
            // Disabled rather than hidden on an empty cell: the button keeps its
            // place, so the bar does not reflow as the selection moves.
            enabled = displayText.isNotEmpty(),
        ) {
            Text(stringResource(R.string.action_copy))
        }
    }
}

/**
 * Tell the user the copy happened — but only where the system will not.
 *
 * Android 13 (API 33) shows its own clipboard confirmation for every copy, and a
 * toast on top of it is two notifications for one action. Below 13 there is no
 * system feedback at all, and a copy that says nothing is indistinguishable from
 * a button that does nothing.
 */
private fun confirmCopy(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
}
