package com.meticulouscreations.homesafe

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.meticulouscreations.homesafe.di.createAppGraph

// FragmentActivity (rather than plain ComponentActivity) is required by androidx.biometric's
// BiometricPrompt, which the shared module's BiometricCredentialStore.android.kt uses for
// biometric login. FragmentActivity extends ComponentActivity, so setContent {} still works.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val appGraph = createAppGraph(platformContext = PlatformContext(this))
        setContent {
            App(appGraph)
        }
    }
}
