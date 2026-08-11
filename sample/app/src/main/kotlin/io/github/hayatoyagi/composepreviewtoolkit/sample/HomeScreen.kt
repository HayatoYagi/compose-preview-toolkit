package io.github.hayatoyagi.composepreviewtoolkit.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.hayatoyagi.composepreviewtoolkit.annotations.ScreenshotPreview

@Composable
fun HomeScreen(onGoToFeatureAClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Hello from compose-preview-toolkit!",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onGoToFeatureAClick) {
                Text("Go to Feature A")
            }
        }
    }
}

/**
 * `@Preview` alone would already show this in Android Studio's Preview panel. Adding
 * `@ScreenshotPreview` next to it is enough for the compose-preview-toolkit Gradle plugin to
 * also generate a matching screenshot test in the `debugScreenshotTest` source set — nothing to
 * add under `androidTest`/`screenshotTest`.
 */
@Preview
@ScreenshotPreview
@Composable
internal fun HomeScreenPreview() {
    MaterialTheme {
        HomeScreen(onGoToFeatureAClick = {})
    }
}
