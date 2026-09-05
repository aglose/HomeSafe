package com.meticulouscreations.homesafe

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.meticulouscreations.homesafe.di.createAppGraph

// FragmentActivity (rather than plain ComponentActivity) is required by androidx.biometric's
// BiometricPrompt, which the shared module's BiometricCredentialStore.android.kt uses for
// biometric login. FragmentActivity extends ComponentActivity, so setContent {} still works.
class MainActivity : FragmentActivity() {
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

        val appGraph = createAppGraph(platformContext = PlatformContext(this))
        setContent {
            App(appGraph)
        }
    }
}
