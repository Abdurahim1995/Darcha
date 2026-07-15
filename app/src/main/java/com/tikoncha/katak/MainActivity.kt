package com.tikoncha.katak

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/**
 * Placeholder entry point for the Katak skeleton (T0).
 *
 * Renders a single "Katak" label. The real viewer UI (file picker, grid) is
 * introduced in milestone M2; this activity only proves the module graph and
 * Compose toolchain build correctly.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KatakPlaceholder()
                }
            }
        }
    }
}

@Composable
private fun KatakPlaceholder() {
    Text(
        text = "Katak",
        modifier = Modifier
            .fillMaxSize()
            .wrapContentSize(),
    )
}

@Preview(showBackground = true)
@Composable
private fun KatakPlaceholderPreview() {
    MaterialTheme {
        KatakPlaceholder()
    }
}
