package com.meticulouscreations.homesafe.fitness

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.meticulouscreations.homesafe.fitness.ui.HealthRationale
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme

/**
 * The page Health Connect opens when someone asks why this app wants their health data: from the
 * permission sheet's link, and from the app's entry in Health Connect's settings. Android won't
 * show the sheet for an app that doesn't declare one (see the manifest). It is opened by the
 * system and not from inside the app, so it has no app graph and needs none: it is a page of
 * words.
 */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark bars over a dark page, whatever the system's theme: the app is dark-only (see MainActivity).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { FrigateTheme { HealthRationale(onDone = ::finish) } }
    }
}
