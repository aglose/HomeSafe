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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
import com.meticulouscreations.homesafe.weather.ski.ui.SkiFixtures
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

    /** The Ski tab and the pages it opens, with what a mouse needs, and a board that's where it was left when a page comes back. */
    @Test
    fun skiWalksItsPagesBesideTheSidebar() = runSkikoComposeUiTest(size = Size(1280f, 800f), testTimeout = 5.minutes) {
        val sections = AppSectionsHost()
        onStage(sections) {
            CompositionLocalProvider(LocalWeatherShaders provides false) {
                WeatherAppContent(WeatherFixtures.state(), NoTiles, WeatherActions(), ski = SkiFixtures.state(SkiFixtures.state().plan.copy(regions = listOf("northern-california"))))
            }
        }
        fun save(step: String) {
            settle()
            saveJourneyScreen(this, "DesktopApps.ski", step)
        }
        runOnUiThread { sections.select(sections.sections.lastIndex) }
        save("1 board")
        onNodeWithTag("ski_refresh_button").assertExists()

        onNodeWithTag("ski_best_bet").performClick()
        save("2 resort")
        // By index: looking for a node scrolls a page at a time and waits for a clock that isn't advancing.
        onNodeWithTag("ski_resort_page").performScrollToIndex(SKI_RESORT_TICKETS_ITEM)
        save("3 tickets")
        onNodeWithTag("weather_back").performClick()
        settle()

        onNodeWithTag("ski_setup_button").performClick()
        save("4 setup")
        onNodeWithTag("weather_back").performClick()
        settle()

        onNodeWithTag("ski_find_button").performClick()
        save("5 find")
        onNodeWithTag("ski_region_northern-california").performClick()
        save("6 region")
        onNodeWithTag("weather_back").performClick()
        settle()
        onNodeWithTag("weather_back").performClick()
        settle()

        onNodeWithTag("ski_screen").performScrollToIndex(SKI_BOARD_REGIONS_ITEM)
        settle()
        onNodeWithTag("ski_regions").assertIsDisplayed()
        onNodeWithTag("ski_regions").performClick()
        save("7 region from further down")
        onNodeWithTag("weather_back").performClick()
        save("8 board as it was left")
        onNodeWithTag("ski_regions").assertIsDisplayed()
    }

    /** [app] in a 1280 × 800 window beside a sidebar's strip, through each of the tabs it lists there. */
    private fun stage(name: String, app: @Composable () -> Unit) = runSkikoComposeUiTest(size = Size(1280f, 800f), testTimeout = 5.minutes) {
        val sections = AppSectionsHost()
        onStage(sections, app)
        assertTrue(sections.sections.isNotEmpty(), "$name handed its tabs to the sidebar")
        for (index in sections.sections.indices) {
            runOnUiThread { sections.select(index) }
            settle()
            saveJourneyScreen(this, "DesktopApps.$name", "${index + 1} ${sections.sections[index].label.key}")
        }
    }

    private fun ComposeUiTest.onStage(sections: AppSectionsHost, app: @Composable () -> Unit) {
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
    }

    private fun ComposeUiTest.settle() = mainClock.advanceTimeBy(1_500, ignoreFrameDuration = true)

    private companion object {
        /** The head, the day, the report, the hours, the ten days and the drive come first. */
        const val SKI_RESORT_TICKETS_ITEM = 6

        /** The day strip, the best bet, a label and the other three resorts come first. */
        const val SKI_BOARD_REGIONS_ITEM = 6
    }
}
