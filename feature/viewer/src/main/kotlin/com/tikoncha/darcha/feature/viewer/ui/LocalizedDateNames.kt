package com.tikoncha.darcha.feature.viewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringArrayResource
import com.tikoncha.darcha.feature.viewer.R
import com.tikoncha.darcha.model.DateNames

/**
 * The month and weekday names for the device's current language (T24).
 *
 * This is the other half of a decision made in T16. `:core:model` formats dates
 * but must not know about locale — no `java.util.Locale`, no resources, nothing
 * platform-shaped — so `DateNames` was made an *input* rather than a constant.
 * This is the injection point that argument was created for: resources resolve
 * against the device language, and the formatter simply spells out whatever it
 * is handed.
 *
 * Recomposes when the configuration changes, so switching the phone's language
 * changes what a `dddd` date says without reopening the document.
 */
@Composable
internal fun rememberDateNames(): DateNames {
    val configuration = LocalConfiguration.current
    val months = stringArrayResource(R.array.month_names)
    val days = stringArrayResource(R.array.day_names)
    return remember(configuration, months, days) {
        DateNames(months = months.toList(), days = days.toList())
    }
}
