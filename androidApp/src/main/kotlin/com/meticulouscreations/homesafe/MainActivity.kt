package com.meticulouscreations.homesafe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.meticulouscreations.homesafe.di.createAppGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val appGraph = createAppGraph(platformContext = PlatformContext(this))
        setContent {
            App(appGraph)
        }
    }
}
