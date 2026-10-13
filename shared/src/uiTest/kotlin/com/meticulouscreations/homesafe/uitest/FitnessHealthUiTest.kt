package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.HealthUiState
import com.meticulouscreations.homesafe.fitness.HeartUiState
import com.meticulouscreations.homesafe.fitness.domain.HealthKind
import com.meticulouscreations.homesafe.fitness.ui.FitnessActions
import com.meticulouscreations.homesafe.fitness.ui.FitnessAppContent
import com.meticulouscreations.homesafe.fitness.ui.FitnessFixtures
import com.meticulouscreations.homesafe.fitness.ui.FitnessPage
import com.meticulouscreations.homesafe.fitness.ui.HealthFixtures
import com.meticulouscreations.homesafe.fitness.ui.HealthRationale
import com.meticulouscreations.homesafe.fitness.ui.LocalFitnessShaders
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * What the device's health store adds to the fitness app, drawn from a made-up one
 * ([HealthFixtures]) and driven through its test tags: the body card on Today in each state it
 * can be in, the door to the runs, the runs page and a run's own. Set up as `FitnessAppUiTest`
 * is: the shaders off, the clock stepped by hand, and taps made by their click action.
 */
@OptIn(ExperimentalTestApi::class)
class FitnessHealthUiTest {

    /** What the screens asked of the app. */
    private class Calls {
        var connects = 0
        var manages = 0
        var routesAsked = 0
        val opened = mutableListOf<String>()

        fun actions() = FitnessActions(
            onConnectHealth = { connects++ },
            onManageHealth = { manages++ },
            onOpenRun = { opened += it },
            onAskRunRoute = { routesAsked++ },
        )
    }

    private fun ComposeUiTest.show(health: HealthUiState, calls: Calls = Calls(), page: FitnessPage? = null, heart: HeartUiState = HeartUiState(), empty: Boolean = false) {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                CompositionLocalProvider(LocalFitnessShaders provides false) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.requiredSize(360.dp, 720.dp)) {
                            FitnessAppContent(if (empty) FitnessFixtures.empty else FitnessFixtures.state(), calls.actions(), initialPage = page, heart = heart, health = health)
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

    // ---- The body card ------------------------------------------------------------------------

    @Test
    fun whereThereIsNoHealthStoreTodayIsAsItWas() = runComposeUiTest(testTimeout = 5.minutes) {
        show(HealthUiState())
        assertShown("fitness_today")
        assertNotShown("fitness_body")
        assertNotShown("fitness_runs_door")
    }

    @Test
    fun theBodyCardShowsReadinessStepsAndSleep() = runComposeUiTest(testTimeout = 5.minutes) {
        val health = HealthFixtures.connected()
        val readiness = assertNotNull(health.body.readiness)
        show(health)
        assertShown("fitness_body")
        onNodeWithTag("fitness_body_ring").assert(hasContentDescription("Readiness ${readiness.score} out of 100", substring = true))
        onNodeWithTag("fitness_body_steps").assert(hasContentDescription("8,412 steps today", substring = true))
        onNodeWithTag("fitness_body_sleep").assert(hasContentDescription("Slept 7 h 12 min last night", substring = true))
        assertNotShown("fitness_body_connect")
        assertNotShown("fitness_body_failed")
    }

    @Test
    fun beforeLastNightHasArrivedThereIsNoScore() = runComposeUiTest(testTimeout = 5.minutes) {
        show(HealthFixtures.waiting())
        onNodeWithTag("fitness_body_score", useUnmergedTree = true).assertTextEquals("––")
        onNodeWithTag("fitness_body_sleep").assert(hasContentDescription("Nothing from last night yet"))
        // The steps are today's own, and there all the same.
        onNodeWithTag("fitness_body_steps").assert(hasContentDescription("8,412 steps today", substring = true))
    }

    @Test
    fun aStoreNotYetAllowedOffersToConnect() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(HealthFixtures.off(), calls)
        assertNotShown("fitness_body_readiness")
        assertNotShown("fitness_body_refused")
        assertNotShown("fitness_body_manage")
        // With nothing shared there are no runs to go to either.
        assertNotShown("fitness_runs_door")
        tap("fitness_body_connect")
        assertEquals(1, calls.connects)
    }

    @Test
    fun onceAndroidHasStoppedAskingTheWayIsTheStoresOwnPage() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(HealthFixtures.off(refused = true), calls)
        assertShown("fitness_body_refused")
        tap("fitness_body_manage")
        assertEquals(1, calls.manages)
    }

    @Test
    fun aMissingStoreIsToBeGot() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(HealthFixtures.missing, calls)
        tap("fitness_body_connect")
        assertEquals(1, calls.connects)
    }

    @Test
    fun withOnlyRunsSharedTheCardPointsAtWhatIsNot() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(HealthFixtures.runsOnly(), calls)
        assertNotShown("fitness_body_readiness")
        assertShown("fitness_runs_door")
        tap("fitness_body_manage")
        assertEquals(1, calls.manages)
    }

    @Test
    fun whatIsNotSharedSaysSoOnItsTile() = runComposeUiTest(testTimeout = 5.minutes) {
        val health = HealthFixtures.connected().let { it.copy(granted = it.granted - HealthKind.SLEEP, body = it.body.copy(sleep = null)) }
        show(health)
        onNodeWithTag("fitness_body_sleep").assert(hasContentDescription("Not shared"))
    }

    @Test
    fun aReadThatFailedSaysTheCardMayBeBehind() = runComposeUiTest(testTimeout = 5.minutes) {
        show(HealthFixtures.connected().copy(failed = true))
        assertShown("fitness_body_failed")
    }

    @Test
    fun theBodyAndTheRunsAreThereBeforeAnythingHasBeenLifted() = runComposeUiTest(testTimeout = 5.minutes) {
        show(HealthFixtures.connected(), empty = true)
        assertShown("fitness_today_empty")
        assertShown("fitness_body")
        assertShown("fitness_runs_door")
    }

    // ---- Runs ---------------------------------------------------------------------------------

    @Test
    fun theDoorOpensTheRunsAndARunOpensItsPage() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(HealthFixtures.connected(), calls)
        tap("fitness_runs_door")
        assertShown("fitness_runs")
        assertShown("fitness_runs_week")
        assertShown("fitness_runs_weeks")
        HealthFixtures.records.forEach { assertShown("fitness_run_${it.id}") }
        tap("fitness_run_run-2")
        assertEquals(listOf("run-2"), calls.opened)
        assertShown("fitness_run")
        // Its totals are there at once; the rest waits for its track.
        assertShown("fitness_run_stats")
        assertShown("fitness_run_reading")
        tap("fitness_back")
        assertShown("fitness_runs")
    }

    @Test
    fun aRunWithItsTrackShowsItsRouteHeartAndSplits() = runComposeUiTest(testTimeout = 5.minutes) {
        show(HealthFixtures.connected(opened = "run-1"), page = FitnessPage.Run("run-1"), heart = FitnessFixtures.heartAtRest())
        assertNotShown("fitness_run_reading")
        assertShown("fitness_run_route")
        assertShown("fitness_run_heart_chart")
        // Zones are set, so the time in each is there and the nudge to set them isn't.
        assertShown("fitness_run_heart")
        assertNotShown("fitness_run_open_heart")
        assertShown("fitness_run_splits")
        // Three miles and the tenth it ended on.
        (1..4).forEach { assertShown("fitness_run_split_$it") }
        assertNotShown("fitness_run_split_5")
    }

    @Test
    fun aRouteThatMustBeAskedForIsOfferedAndAsked() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(HealthFixtures.connected(opened = "run-2"), calls, page = FitnessPage.Run("run-2"))
        assertNotShown("fitness_run_route")
        tap("fitness_run_route_ask")
        assertEquals(1, calls.routesAsked)
    }

    @Test
    fun aTreadmillRunHasNoRouteToAskFor() = runComposeUiTest(testTimeout = 5.minutes) {
        val treadmill = HealthFixtures.records.first { it.treadmill }
        show(HealthFixtures.connected(opened = treadmill.id), page = FitnessPage.Run(treadmill.id))
        assertNotShown("fitness_run_route")
        assertNotShown("fitness_run_route_ask")
    }

    @Test
    fun withNoZonesSetARunOffersTheWayToSetThem() = runComposeUiTest(testTimeout = 5.minutes) {
        // The same run read with no zones in force, on a phone that has a sensor to set them for.
        val health = HealthFixtures.connected(opened = "run-1").let { it.copy(detail = it.detail?.copy(summary = null)) }
        show(health, page = FitnessPage.Run("run-1"), heart = FitnessFixtures.heartUnset())
        assertShown("fitness_run_heart_chart")
        tap("fitness_run_open_heart")
        assertShown("fitness_heart_page")
    }

    @Test
    fun withExerciseNotSharedTheRunsPageSaysSo() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        val health = HealthFixtures.connected().let { it.copy(granted = it.granted - HealthKind.EXERCISE) }
        show(health, calls, page = FitnessPage.Runs)
        assertShown("fitness_runs_unshared")
        assertNotShown("fitness_runs_week")
        tap("fitness_body_manage")
        assertEquals(1, calls.manages)
    }

    @Test
    fun aRunThatIsGoneLeavesItsPageEmpty() = runComposeUiTest(testTimeout = 5.minutes) {
        show(HealthFixtures.connected(), page = FitnessPage.Run("nobody"))
        assertNotShown("fitness_run")
    }

    // ---- The page Health Connect links to -----------------------------------------------------

    @Test
    fun theRationaleSaysWhatIsReadAndCloses() = runComposeUiTest(testTimeout = 5.minutes) {
        var done = 0
        mainClock.autoAdvance = false
        setContent { FrigatePreview { HealthRationale(onDone = { done++ }) } }
        settle()
        assertShown("fitness_health_rationale")
        tap("fitness_health_rationale_done")
        assertTrue(done == 1)
    }
}
