package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.weather.RadarLoad
import com.meticulouscreations.homesafe.weather.WeatherUiState
import com.meticulouscreations.homesafe.weather.domain.MeasureSystem
import com.meticulouscreations.homesafe.weather.domain.RadarFrame
import com.meticulouscreations.homesafe.weather.domain.RadarSource
import com.meticulouscreations.homesafe.weather.domain.RadarTimeline
import com.meticulouscreations.homesafe.weather.domain.TemperatureUnit
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeSettings
import com.meticulouscreations.homesafe.weather.domain.WeatherPreferences
import com.meticulouscreations.homesafe.weather.domain.WeatherUnits
import com.meticulouscreations.homesafe.weather.ui.LocalWeatherShaders
import com.meticulouscreations.homesafe.weather.ui.NoTiles
import com.meticulouscreations.homesafe.weather.ui.WeatherActions
import com.meticulouscreations.homesafe.weather.ui.WeatherAppContent
import com.meticulouscreations.homesafe.weather.ui.WeatherFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.minutes

/**
 * The weather app's screens, drawn from the made-up Portland forecast and driven through their
 * test tags: the tabs, the pages pushed over them, and the way each reports a choice back through
 * [WeatherActions]. The sky is held still (`stillSky`) so it is one frame of shader, not a moving
 * picture: the JVM draws shaders in software, and a moving sky would make every test seconds long.
 *
 * Every test sets `mainClock.autoAdvance = false` and steps the clock itself, as the other screen
 * tests do: the app has animations that never end, and an auto-advancing clock would wait on them.
 * A tap goes by its click action rather than a touch, so it doesn't matter whether the control is
 * inside the small window the tests use.
 */
@OptIn(ExperimentalTestApi::class)
class WeatherAppUiTest {

    private val stillSky = WeatherPreferences(stillSky = true)
    private val state = WeatherFixtures.state().copy(preferences = stillSky)

    /** What the screens asked of the app. */
    private class Calls {
        var closed = 0
        var locate = 0
        val searches = mutableListOf<String>()
        val units = mutableListOf<WeatherUnits>()
        val notices = mutableListOf<WeatherNoticeSettings>()
        val stillSky = mutableListOf<Boolean>()
        val radarVisible = mutableListOf<Boolean>()

        fun actions() = WeatherActions(
            onClose = { closed++ },
            onSearch = { searches += it },
            onUseLocation = { locate++ },
            onUnitsChange = { units += it },
            onNoticesChange = { notices += it },
            onStillSkyChange = { stillSky += it },
            onRadarVisible = { radarVisible += it },
        )
    }

    /**
     * The app on a small phone, whatever the test window is. Only a test that asks for [shaders]
     * gets the sky drawn by them: here they run on the CPU, seconds a frame, and what these tests
     * check is the same over the plain gradient that stands in.
     */
    private fun ComposeUiTest.show(state: WeatherUiState, calls: Calls, shaders: Boolean = false) {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                CompositionLocalProvider(LocalWeatherShaders provides shaders) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.requiredSize(300.dp, 600.dp)) {
                            WeatherAppContent(state, NoTiles, calls.actions())
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
        onNodeWithTag(tag).tap()
        settle()
    }

    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun ComposeUiTest.assertShown(tag: String) {
        onNodeWithTag(tag).assertExists()
    }

    private fun ComposeUiTest.assertNotShown(tag: String) {
        onAllNodesWithTag(tag).assertCountIsZero()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountIsZero() {
        assertEquals(0, fetchSemanticsNodes(atLeastOneRootRequired = false).size)
    }

    // ---- Today --------------------------------------------------------------------------------

    @Test
    fun todayOpensWithTheCurrentTemperatureInTheHero() = runComposeUiTest(testTimeout = 5.minutes) {
        // The one test here drawn through the real shaders: they compile, take their uniforms and draw.
        show(state, Calls(), shaders = true)
        assertShown("weather_app")
        assertShown("weather_today")
        assertShown("weather_hero")
        val current = state.selected!!.report!!.current
        val temperature = WeatherFormat.degrees(current.temperatureC, state.units)
        onNode(hasText(temperature) and hasAnyAncestor(hasTestTag("weather_hero"))).assertExists()
        assertShown("weather_headline")
    }

    // ---- Forecast -----------------------------------------------------------------------------

    @Test
    fun theForecastTabShowsTheDayRowsAndADayOpensOnTap() = runComposeUiTest(testTimeout = 5.minutes) {
        show(state, Calls())
        assertNotShown("weather_forecast")
        tap("weather_tab_forecast")
        assertShown("weather_forecast")
        assertShown("weather_day_0")
        assertShown("weather_day_1")

        fun stateOfDay() = onNodeWithTag("weather_day_1").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        val collapsed = stateOfDay()
        tap("weather_day_1")
        assertNotEquals(collapsed, stateOfDay(), "the day's row reports that it is open")
        tap("weather_day_1")
        assertEquals(collapsed, stateOfDay(), "and closed again")

        // And back to Today, without leaving the app.
        assertNotShown("weather_hero")
        tap("weather_tab_today")
        assertShown("weather_hero")
        assertNotShown("weather_forecast")
    }

    // ---- Radar --------------------------------------------------------------------------------

    private val timeline = RadarTimeline(
        RadarSource.US_MRMS,
        (0..2).map { RadarFrame(WeatherFixtures.NOW - (2 - it) * 600L, forecast = false, tileUrl = "https://example.test/$it/{z}/{x}/{y}.png", maxZoom = 8) },
    )

    @Test
    fun theRadarTabShowsTheRadarScreenAndItsPlayButton() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(state.copy(radar = RadarLoad(timeline = timeline)), calls)
        tap("weather_tab_radar")
        assertShown("weather_radar")
        assertShown("weather_radar_play")
        onNodeWithTag("weather_radar_play").assertIsEnabled()
        assertShown("weather_radar_scrubber")
        assertEquals(true, calls.radarVisible.lastOrNull(), "the app was told the radar is wanted")
    }

    @Test
    fun theRadarsPlayButtonIsDisabledUntilALoopArrives() = runComposeUiTest(testTimeout = 5.minutes) {
        show(state, Calls())
        tap("weather_tab_radar")
        assertShown("weather_radar")
        onNodeWithTag("weather_radar_play").assertIsNotEnabled()
    }

    // ---- Places -------------------------------------------------------------------------------

    @Test
    fun thePlacesButtonOpensPlacesAndTypingSearchesAndBackReturnsToTheTabs() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(state, calls)
        tap("weather_places_button")
        assertShown("weather_places")
        assertShown("weather_places_search")
        assertNotShown("weather_today")

        // Set straight through the field's text action, not typed: typing focuses the field and
        // opens an input session, and its keyboard hide when Back removes the page can land off the
        // main thread on an emulator. What's checked is that the text reaches the app, not the IME.
        onNodeWithTag("weather_places_search").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Sea")) }
        settle()
        assertEquals(listOf("Sea"), calls.searches)

        tap("weather_back")
        assertNotShown("weather_places")
        assertShown("weather_today")
        assertEquals(0, calls.closed, "back from a page is not closing the app")
    }

    // ---- Settings -----------------------------------------------------------------------------

    @Test
    fun theSettingsPageReportsEachChoiceWithTheChangedValue() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(state, calls)
        tap("weather_settings_button")
        assertShown("weather_settings")
        // Celsius is the second choice.
        tap("weather_units_temperature_1")
        assertEquals(listOf(WeatherUnits(TemperatureUnit.CELSIUS, MeasureSystem.IMPERIAL)), calls.units)
        tap("weather_units_measures_1")
        assertEquals(WeatherUnits(TemperatureUnit.FAHRENHEIT, MeasureSystem.METRIC), calls.units.last())
        tap("weather_notices_enabled")
        assertEquals(listOf(WeatherNoticeSettings(enabled = false)), calls.notices)
        tap("weather_notices_soon")
        assertEquals(WeatherNoticeSettings(precipitationSoon = false), calls.notices.last())
        // The sky is held still in these tests, so the switch starts on and reports off.
        tap("weather_still_sky")
        assertEquals(listOf(false), calls.stillSky)

        // Back from a page goes to the tabs, and only back from the tabs leaves the app.
        tap("weather_back")
        assertNotShown("weather_settings")
        assertShown("weather_today")
        assertEquals(0, calls.closed)
        tap("weather_back")
        assertEquals(1, calls.closed)
    }

    // ---- Nothing to show ----------------------------------------------------------------------

    private val empty = WeatherUiState(
        preferences = stillSky,
        locationSupported = true,
        locationAccess = LocationAccess.NOT_ASKED,
        nowEpochSeconds = WeatherFixtures.NOW,
        settled = true,
    )

    @Test
    fun anEmptySettledStateShowsTheWelcomePanelAndItsWays() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(empty, calls)
        assertShown("weather_welcome")
        assertShown("weather_welcome_locate")
        assertShown("weather_welcome_add")
        assertNotShown("weather_hero")

        tap("weather_welcome_locate")
        assertEquals(1, calls.locate)

        tap("weather_welcome_add")
        assertShown("weather_places")
    }

    @Test
    fun withoutLocationSupportTheWelcomeOnlyOffersToAddACity() = runComposeUiTest(testTimeout = 5.minutes) {
        show(empty.copy(locationSupported = false, locationAccess = LocationAccess.UNAVAILABLE), Calls())
        assertShown("weather_welcome")
        assertNotShown("weather_welcome_locate")
        assertShown("weather_welcome_add")
    }
}
