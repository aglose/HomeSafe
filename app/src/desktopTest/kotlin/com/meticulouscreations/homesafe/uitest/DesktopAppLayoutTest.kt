package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.atlas.ui.AtlasActions
import com.meticulouscreations.homesafe.atlas.ui.AtlasAppContent
import com.meticulouscreations.homesafe.atlas.ui.AtlasFixtures
import com.meticulouscreations.homesafe.atlas.ui.LocalAtlasShaders
import com.meticulouscreations.homesafe.fitness.ui.FitnessActions
import com.meticulouscreations.homesafe.fitness.ui.FitnessAppContent
import com.meticulouscreations.homesafe.fitness.ui.FitnessFixtures
import com.meticulouscreations.homesafe.fitness.ui.LocalFitnessShaders
import com.meticulouscreations.homesafe.integration.saveJourneyScreen
import com.meticulouscreations.homesafe.ui.AppSectionsHost
import com.meticulouscreations.homesafe.ui.LocalAppSections
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.weather.ui.LocalWeatherShaders
import com.meticulouscreations.homesafe.weather.ui.NoTiles
import com.meticulouscreations.homesafe.weather.ui.WeatherActions
import com.meticulouscreations.homesafe.weather.ui.WeatherAppContent
import com.meticulouscreations.homesafe.weather.ui.WeatherFixtures
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The drawer's apps as the desktop shows them: in a laptop's window, beside the shell's sidebar
 * (a strip of its width here), with their tabs handed up to it, from the made-up data their
 * previews use. Each tab is stepped through as a click on its row in the sidebar would, and drawn
 * to app/build/journeys/DesktopApps.<app>. The shaders are off: the JVM draws them in software.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopAppLayoutTest {
    @Test
    fun weatherSitsBesideTheSidebarWithItsTabsInIt() = stage("weather") {
        CompositionLocalProvider(LocalWeatherShaders provides false) {
            WeatherAppContent(WeatherFixtures.state(), NoTiles, WeatherActions())
        }
    }

    @Test
    fun fitnessSitsBesideTheSidebarWithItsTabsInIt() = stage("fitness") {
        CompositionLocalProvider(LocalFitnessShaders provides false) {
            FitnessAppContent(FitnessFixtures.state(), FitnessActions())
        }
    }

    @Test
    fun theAtlasSitsBesideTheSidebarWithItsTabsInIt() = stage("atlas") {
        CompositionLocalProvider(LocalAtlasShaders provides false) {
            AtlasAppContent(AtlasFixtures.state, NoTiles, AtlasActions())
        }
    }

    /** [app] in a 1280 × 800 window beside a sidebar's strip, through each of the tabs it lists there. */
    private fun stage(name: String, app: @Composable () -> Unit) = runSkikoComposeUiTest(size = Size(1280f, 800f), testTimeout = 5.minutes) {
        val sections = AppSectionsHost()
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(248.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLowest))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        CompositionLocalProvider(LocalAppSections provides sections) { app() }
                    }
                }
            }
        }
        settle()
        assertTrue(sections.sections.isNotEmpty(), "$name handed its tabs to the sidebar")
        for (index in sections.sections.indices) {
            runOnUiThread { sections.select(index) }
            settle()
            saveJourneyScreen(this, "DesktopApps.$name", "${index + 1} ${sections.sections[index].label.key}")
        }
    }

    private fun ComposeUiTest.settle() = mainClock.advanceTimeBy(1_500, ignoreFrameDuration = true)
}
