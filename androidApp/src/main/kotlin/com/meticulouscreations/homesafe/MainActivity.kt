package com.meticulouscreations.homesafe

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.meticulouscreations.homesafe.di.AppGraph
import com.meticulouscreations.homesafe.di.createAppGraph
import com.meticulouscreations.homesafe.ui.screens.DebugAutofillCredentials

// FragmentActivity (rather than plain ComponentActivity) is required by androidx.biometric's
// BiometricPrompt, which the shared module's BiometricCredentialStore.android.kt uses for
// biometric login. FragmentActivity extends ComponentActivity, so setContent {} still works.
class MainActivity : FragmentActivity() {
    private lateinit var appGraph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark-only (see FrigateTheme), so the system bar icons must be light whatever
        // the OS theme is — the default enableEdgeToEdge() follows the OS and would draw dark icons
        // over the app's near-black background on a light-themed phone.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // No translucent scrim behind 3-button nav: the floating nav pill already floats above it.
            window.isNavigationBarContrastEnforced = false
        }

        appGraph = createAppGraph(platformContext = PlatformContext(this))
        // BuildConfig.TEST_USERNAME etc. are always empty in release builds (see
        // androidApp/build.gradle.kts), so this is null there. Gated on the value rather than
        // BuildConfig.DEBUG so the benchmarkRelease variant — release bytecode, not debuggable —
        // can still sign itself in for the Macrobenchmark journeys.
        val debugAutofillCredentials = if (BuildConfig.TEST_USERNAME.isNotBlank()) {
            DebugAutofillCredentials(
                serverUrl = BuildConfig.TEST_SERVER_URL,
                username = BuildConfig.TEST_USERNAME,
                password = BuildConfig.TEST_PASSWORD,
            )
        } else {
            null
        }
        setContent {
            // testTagsAsResourceId lets UiAutomator (the :baselineprofile journeys) find nodes by
            // Modifier.testTag via By.res(...). Zero cost outside accessibility/test traversal.
            Box(modifier = Modifier.semantics { testTagsAsResourceId = true }) {
                App(appGraph, debugAutofillCredentials = debugAutofillCredentials)
            }
        }
    }

    override fun onDestroy() {
        // The graph is per Activity but its coroutine scope isn't cancelled; a recreated Activity
        // builds a new graph, so this one's detection poller must not keep running beside it.
        if (::appGraph.isInitialized) appGraph.detectionAlertService.stop()
        super.onDestroy()
    }
}
