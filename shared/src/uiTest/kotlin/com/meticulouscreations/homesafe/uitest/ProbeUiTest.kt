package com.meticulouscreations.homesafe.uitest

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

/**
 * Proves the Compose UI test stack is wired on every target this source set reaches: the JVM
 * (skiko) and the iOS simulator. Kept deliberately trivial — when this fails, the problem is the
 * build wiring, not the app.
 */
class ProbeUiTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rendersText() = runComposeUiTest {
        setContent { Text("probe") }
        onNodeWithText("probe").assertIsDisplayed()
    }
}
