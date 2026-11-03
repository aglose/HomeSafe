package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.domain.Recording
import com.meticulouscreations.homesafe.fitness.domain.RoutePoint
import com.meticulouscreations.homesafe.fitness.ui.FitnessActions
import com.meticulouscreations.homesafe.fitness.ui.FitnessAppContent
import com.meticulouscreations.homesafe.fitness.ui.FitnessFixtures
import com.meticulouscreations.homesafe.fitness.ui.FitnessPage
import com.meticulouscreations.homesafe.fitness.ui.HealthFixtures
import com.meticulouscreations.homesafe.fitness.ui.LocalFitnessShaders
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The map a run's route is drawn on, read from where it puts the route's two ends: that it fits
 * the whole route and not only the ends, keeps a recording's growing route in view, and that a
 * finished run's map answers a finger while a recording's leaves it to the page. Set up as
 * [FitnessHealthUiTest] is, with no tiles: the ends are placed from the map's camera all the same.
 */
@OptIn(ExperimentalTestApi::class)
class RunRouteMapUiTest {

    /** Out and back along a street running north, 2 km up: the turn is far from either end. */
    private val outAndBack = (0..20).map { RoutePoint(45.0 + 0.001 * it, -93.0) } +
        (19 downTo 0).map { RoutePoint(45.0 + 0.001 * it, -93.0005) }

    private fun ComposeUiTest.showRun(route: List<RoutePoint>) {
        val health = HealthFixtures.connected(opened = "run-1").let { it.copy(detail = it.detail?.copy(route = route)) }
        show { FitnessAppContent(FitnessFixtures.state(), FitnessActions(), initialPage = FitnessPage.Run("run-1"), health = health) }
    }

    private fun ComposeUiTest.showRecording(recording: () -> Recording) {
        show { FitnessAppContent(FitnessFixtures.state(), FitnessActions(), initialPage = FitnessPage.Recording, health = HealthFixtures.connected(), recording = recording()) }
    }

    private fun ComposeUiTest.show(content: @Composable () -> Unit) {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                CompositionLocalProvider(LocalFitnessShaders provides false) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.requiredSize(360.dp, 720.dp)) { content() }
                    }
                }
            }
        }
        settle()
    }

    private fun ComposeUiTest.settle() = mainClock.advanceTimeBy(1_000, ignoreFrameDuration = true)

    /** Where the map has drawn one of the route's ends ("start" or "finish"), in dp from its top left corner. */
    private fun ComposeUiTest.end(which: String): Offset {
        val map = onNodeWithTag("fitness_route_map", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val end = onNodeWithTag("fitness_route_$which", useUnmergedTree = true).getUnclippedBoundsInRoot()
        return Offset(((end.left + end.right) / 2 - map.left).value, ((end.top + end.bottom) / 2 - map.top).value)
    }

    private fun ComposeUiTest.mapHeight(): Float = onNodeWithTag("fitness_route_map", useUnmergedTree = true).getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }

    private fun ComposeUiTest.dragMapDown() {
        onNodeWithTag("fitness_route_map", useUnmergedTree = true).performTouchInput { swipe(center, center + Offset(0f, height / 3f), durationMillis = 300) }
        settle()
    }

    private fun assertNear(expected: Float, actual: Float, what: String) {
        assertTrue(abs(expected - actual) <= 2f, "$what: expected about $expected dp, was $actual dp")
    }

    @Test
    fun aRunThatEndsWhereItStartedIsFittedToItsFarthestPoint() = runComposeUiTest(testTimeout = 5.minutes) {
        showRun(outAndBack)
        // Fitted to the two ends alone the map would sit on them, close in, in its middle. Fitted
        // to the whole run, they are down at its bottom edge, the margin in, with the turn at the top.
        val height = mapHeight()
        assertNear(height - 20f, end("start").y, "the start")
        assertNear(height - 20f, end("finish").y, "the finish")
    }

    @Test
    fun aRecordingStaysInViewAsItsRouteGrows() = runComposeUiTest(testTimeout = 5.minutes) {
        var recording by mutableStateOf(HealthFixtures.recording().copy(route = outAndBack.take(2)))
        showRecording { recording }
        // Only just begun: both ends on the map.
        val height = mapHeight()
        listOf("start", "finish").forEach { assertTrue(end(it).y in 0f..height, "$it on the map at the beginning") }

        recording = recording.copy(route = outAndBack.take(21))
        settle()
        // Up to the turn: the map has moved out to show it all, start at the bottom, the runner at the top.
        assertNear(height - 20f, end("start").y, "the start")
        assertNear(20f, end("finish").y, "the runner")
    }

    @Test
    fun aFinishedRunsMapIsDraggedByAFinger() = runComposeUiTest(testTimeout = 5.minutes) {
        showRun(outAndBack)
        val before = end("start").y
        dragMapDown()
        assertTrue(end("start").y > before + 40f, "the map moved under the finger: the start was at $before dp, is at ${end("start").y} dp")
    }

    @Test
    fun aRecordingsMapStaysPutUnderAFinger() = runComposeUiTest(testTimeout = 5.minutes) {
        showRecording { HealthFixtures.recording().copy(route = outAndBack) }
        val before = end("start")
        dragMapDown()
        assertNear(before.y, end("start").y, "the start, after a drag")
        assertNear(before.x, end("start").x, "the start across, after a drag")
    }
}
