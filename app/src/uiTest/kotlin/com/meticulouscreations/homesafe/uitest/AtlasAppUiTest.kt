package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.atlas.AtlasUiState
import com.meticulouscreations.homesafe.atlas.domain.CivilDate
import com.meticulouscreations.homesafe.atlas.ui.AtlasActions
import com.meticulouscreations.homesafe.atlas.ui.AtlasAppContent
import com.meticulouscreations.homesafe.atlas.ui.AtlasFixtures
import com.meticulouscreations.homesafe.atlas.ui.AtlasLockScreen
import com.meticulouscreations.homesafe.atlas.ui.AtlasPage
import com.meticulouscreations.homesafe.atlas.ui.AtlasTab
import com.meticulouscreations.homesafe.atlas.ui.LocalAtlasShaders
import com.meticulouscreations.homesafe.finance.FinanceLockUiState
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.weather.ui.NoTiles
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_fingerprint
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The atlas app's screens, drawn from the made-up life in [AtlasFixtures] and driven through
 * their test tags: the tabs, the pages pushed over them, and the way out. The shaders are off: the
 * JVM draws them in software, seconds a frame, and what these tests check is the same without.
 *
 * Every test sets `mainClock.autoAdvance = false` and steps the clock itself, as the other screen
 * tests do: the app has animations that never end, and an auto-advancing clock would wait on them.
 * A tap goes by its click action rather than a touch, so it doesn't matter whether the control is
 * inside the small window the tests use.
 */
@OptIn(ExperimentalTestApi::class)
class AtlasAppUiTest {

    private class Calls {
        var closed = 0
        var checks = 0

        fun actions() = AtlasActions(onClose = { closed++ }, onCheckNow = { checks++ })
    }

    private fun ComposeUiTest.show(state: AtlasUiState, calls: Calls, tab: AtlasTab = AtlasTab.MAP, page: AtlasPage? = null) {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                CompositionLocalProvider(LocalAtlasShaders provides false) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.requiredSize(360.dp, 720.dp)) {
                            AtlasAppContent(state, NoTiles, calls.actions(), initialTab = tab, initialPage = page)
                        }
                    }
                }
            }
        }
        settle()
    }

    /** Past the page transitions and the nav sliding in or out. */
    private fun ComposeUiTest.settle() = mainClock.advanceTimeBy(1_000, ignoreFrameDuration = true)

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private fun ComposeUiTest.assertShown(tag: String) {
        onNodeWithTag(tag).assertExists()
    }

    private fun ComposeUiTest.assertNotShown(tag: String) {
        assertEquals(0, onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).size)
    }

    @Test
    fun itOpensOnTheMapAndEachTabShowsItsScreen() = runComposeUiTest {
        show(AtlasFixtures.state, Calls())
        assertShown("atlas_map")
        // What the map adds up to: every place in the made-up life.
        onAllNodesWithText("13").assertCountEquals(1)
        for ((tab, screen) in listOf("days" to "atlas_days", "places" to "atlas_places", "trips" to "atlas_trips", "patterns" to "atlas_patterns", "map" to "atlas_map")) {
            tap("atlas_tab_$tab")
            assertShown(screen)
        }
    }

    @Test
    fun theYearsAlongTheMapPickHowMuchOfItShows() = runComposeUiTest {
        show(AtlasFixtures.state, Calls())
        fun year(label: String) = onNode(hasText(label))
        fun pick(label: String) {
            year(label).performSemanticsAction(SemanticsActions.OnClick)
            settle()
        }
        // Newest first along the bar, after All.
        val lefts = listOf("All", "2025", "2024", "2023").map { year(it).fetchSemanticsNode().boundsInRoot.left }
        assertEquals(lefts.sorted(), lefts)
        year("All").assertIsOn()

        // 2023 alone: the first house's places, before the lake or the desert.
        pick("2023")
        onAllNodesWithText("13").assertCountEquals(0)
        onNode(hasText("1")).assertExists()
        year("All").assertIsOff()

        // 2023 and 2025 together, without the lake's year between.
        pick("2025")
        year("2023").assertIsOn()
        year("2024").assertIsOff()
        year("2025").assertIsOn()
        // Illinois and Nevada.
        onNode(hasText("2")).assertExists()

        // The last year picked taken away is all of them again, and so is every one picked.
        pick("2023")
        pick("2025")
        year("All").assertIsOn()
        listOf("2023", "2024", "2025").forEach { pick(it) }
        year("All").assertIsOn()
        year("2024").assertIsOff()
        onAllNodesWithText("13").assertCountEquals(1)
    }

    @Test
    fun aPlaceOpensFromTheListAndBackGoesBackThenOut() = runComposeUiTest {
        val calls = Calls()
        show(AtlasFixtures.state, calls, tab = AtlasTab.PLACES)
        tap("atlas_place_row_${AtlasFixtures.work.key}")
        assertShown("atlas_place")
        onAllNodesWithText("Northfield Works").assertCountEquals(1)
        assertNotShown("atlas_tab_map")

        tap("atlas_back")
        assertShown("atlas_places")
        assertEquals(0, calls.closed)
        tap("atlas_back")
        assertEquals(1, calls.closed)
    }

    @Test
    fun theSearchNarrowsThePlaces() = runComposeUiTest {
        show(AtlasFixtures.state, Calls(), tab = AtlasTab.PLACES)
        onNodeWithTag("atlas_places_search").performTextInput("lakemont")
        settle()
        assertShown("atlas_place_row_${AtlasFixtures.lake.key}")
        assertNotShown("atlas_place_row_${AtlasFixtures.work.key}")
        onNodeWithTag("atlas_places_search").performTextInput("zzz")
        settle()
        onNode(hasText("No places match")).assertExists()
    }

    @Test
    fun aTripOpensAndItsDaysOpenFromIt() = runComposeUiTest {
        show(AtlasFixtures.state, Calls(), tab = AtlasTab.TRIPS)
        onAllNodesWithText("Sunridge, NV").assertCountEquals(1)
        tap("atlas_trip_1")
        assertShown("atlas_trip")
        onNode(hasText("Lakemont, WI")).assertExists()
        onNode(hasText("5 nights")).assertExists()
    }

    @Test
    fun steppingThroughDaysIsOnePageNotAStackOfThem() = runComposeUiTest {
        val calls = Calls()
        show(AtlasFixtures.state, calls, tab = AtlasTab.DAYS, page = AtlasPage.Day(CivilDate.dayOf(2025, 5, 13)))
        assertShown("atlas_day")
        onNode(hasText("Tue, May 13, 2025")).assertExists()
        assertShown("atlas_day_ribbon")
        tap("atlas_day_previous")
        onNode(hasText("Mon, May 12, 2025")).assertExists()
        tap("atlas_day_previous")
        onNode(hasText("Sun, May 11, 2025")).assertExists()
        // Back from three days of stepping is the calendar, not the day before.
        tap("atlas_back")
        assertShown("atlas_days")
        assertEquals(0, calls.closed)
    }

    @Test
    fun theLastDayOnTheClockHasNoTomorrow() = runComposeUiTest {
        show(AtlasFixtures.state, Calls(), page = AtlasPage.Day(AtlasFixtures.state.today))
        onNode(hasText("Sat, Jun 28, 2025")).assertExists()
        onNode(hasText("Nothing on record for this day")).assertExists()
        // Off, and said to be: not a button that takes a tap and does nothing.
        onNodeWithTag("atlas_day_next").assertIsNotEnabled()
        onNodeWithTag("atlas_day_previous").assertIsEnabled()
    }

    @Test
    fun aYearsGridCanBeSteppedAlongAndOpenedWithoutPointingAtASquare() = runComposeUiTest {
        show(AtlasFixtures.state, Calls(), tab = AtlasTab.DAYS)
        // It begins on the latest day on record, and says what that day held.
        onNodeWithTag("atlas_year_2025").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Fri, Jun 27, 2025: 7.4 mi · 2 places"))
        onNodeWithTag("atlas_year_2025").performSemanticsAction(SemanticsActions.SetProgress) { it(36f) }
        settle()
        // The thirty-seventh day of the year, the day of the flight to the desert.
        onNodeWithTag("atlas_year_2025").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        tap("atlas_year_2025")
        assertShown("atlas_day")
        onNode(hasText("Thu, Feb 6, 2025")).assertExists()
    }

    @Test
    fun theDaysRibbonSaysWhereItsPlayheadIsAndCanBeMoved() = runComposeUiTest {
        show(AtlasFixtures.state, Calls(), page = AtlasPage.Day(CivilDate.dayOf(2025, 5, 13)))
        onNodeWithTag("atlas_day_ribbon").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "The whole day"))
        // Halfway through a day that runs midnight to midnight.
        onNodeWithTag("atlas_day_ribbon").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        settle()
        onNodeWithTag("atlas_day_ribbon").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "12:00 PM"))
    }

    @Test
    fun withNoHistoryItSaysHowToGetOne() = runComposeUiTest {
        val calls = Calls()
        show(AtlasUiState(loaded = true), calls)
        assertShown("atlas_empty")
        assertNotShown("atlas_tab_map")
        tap("atlas_empty_action")
        assertShown("atlas_sync")
        tap("atlas_sync_now")
        assertEquals(1, calls.checks)
        tap("atlas_back")
        assertShown("atlas_empty")
    }

    @Test
    fun theSyncPageSaysWhereTheHistoryComesFrom() = runComposeUiTest {
        show(AtlasFixtures.state, Calls())
        tap("atlas_sync_open")
        assertShown("atlas_sync")
        onNode(hasText("13 of 13 places named")).assertExists()
        // Read the evening before the morning the made-up clock stands at.
        onNode(hasText("Last export read: Timeline.json, Yesterday")).assertExists()
    }

    @Test
    fun untilTheDeviceHasBeenReadItSaysSo() = runComposeUiTest {
        show(AtlasUiState(), Calls())
        onNode(hasText("Reading your history…")).assertExists()
        assertNotShown("atlas_empty")
    }

    @Test
    fun theLockAsksForTheFingerprint() = runComposeUiTest {
        var asked = 0
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                CompositionLocalProvider(LocalAtlasShaders provides false) {
                    AtlasLockScreen(FinanceLockUiState(locked = true, offline = true, canUnlock = true, method = Res.string.biometric_name_fingerprint), onUnlock = { asked++ })
                }
            }
        }
        settle()
        tap("atlas_unlock")
        assertEquals(1, asked)
    }
}
