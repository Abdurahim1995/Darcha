package com.tikoncha.darcha.feature.viewer.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tikoncha.darcha.feature.viewer.R
import com.tikoncha.darcha.feature.viewer.mvi.SearchState

/**
 * The search bar: query, count, and next/previous (T33).
 *
 * Sits directly above the sheet tabs, below the grid, so opening it takes height
 * from the grid rather than from the document header — the file name and the
 * Open button stay where they were.
 *
 * **The count tells the truth about three different situations**, which is the
 * whole reason it is not just a number:
 *
 * - a scan in flight, or the sheet still parsing → *searching*
 * - a finished scan with nothing → *no matches*
 * - matches, with the count marked **provisional** while the sheet is still
 *   growing, because more will appear and presenting the number as final would
 *   be a lie the reader has no way to detect
 */
@Composable
internal fun SearchBar(
    search: SearchState,
    onQueryChange: (String) -> Unit,
    onStep: (forward: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    // Opening the bar without the keyboard would make the user tap again for no
    // reason; the bar only ever appears because they asked for it.
    LaunchedEffect(Unit) { focus.requestFocus() }

    HorizontalDivider()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = search.query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = { Text(stringResource(R.string.search_label)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onStep(true) }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )

        Text(
            text = countLabel(search),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        val canStep = search.matchCount > 0
        TextButton(onClick = { onStep(false) }, enabled = canStep) {
            Text(stringResource(R.string.search_previous))
        }
        TextButton(onClick = { onStep(true) }, enabled = canStep) {
            Text(stringResource(R.string.search_next))
        }
        TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
    }
}

/** What the count area says, for each of the states it can be in. */
@Composable
private fun countLabel(search: SearchState): String = when {
    search.query.isEmpty() -> ""
    search.running -> stringResource(R.string.search_running)
    search.matchCount == 0 -> stringResource(R.string.search_no_matches)
    // "3/12" while complete, "3/12+" while the sheet is still growing. The plus
    // is the whole honesty of the number: more matches are still coming.
    search.countIsFinal ->
        stringResource(R.string.search_position, search.currentIndex + 1, search.matchCount)
    else ->
        stringResource(R.string.search_position_partial, search.currentIndex + 1, search.matchCount)
}
