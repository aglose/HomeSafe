package com.meticulouscreations.homesafe.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.runtime.remember

/*
 * Every @Preview in :shared, the features and :app, drawn by Layoutlib — Android's own renderer,
 * the one Android Studio's preview pane uses — through Compose Preview Screenshot Testing, as an
 * AGP test suite (screenshotTests in androidApp/build.gradle.kts):
 *
 *     ./gradlew :androidApp:updateDebugScreenshotTestDefaultTestSuite  # draw them under src/screenshotTestDefaultDebug/reference/
 *     PREVIEWS=finance ./gradlew :androidApp:updateDebugScreenshotTestDefaultTestSuite  # only the matching ones
 *
 * The tool only looks for @PreviewTest functions in an Android module's screenshotTest source set,
 * so :androidApp:generatePreviewTests writes one for each preview (build/generated/previewTests),
 * with the preview's own @Preview arguments and a call to DrawPreview. See docs/ui-previews.md.
 */

/**
 * Draws the preview [functionName], a top-level composable of the JVM class [className]. By
 * reflection, because previews are private to their module, as compose-rules has them.
 */
@Composable
fun DrawPreview(className: String, functionName: String) {
    val preview = remember(className, functionName) {
        Class.forName(className).getDeclaredComposableMethod(functionName).also { it.asMethod().isAccessible = true }
    }
    preview.invoke(currentComposer, null)
}
