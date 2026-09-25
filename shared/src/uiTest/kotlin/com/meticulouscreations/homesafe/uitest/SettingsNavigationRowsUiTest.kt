package com.meticulouscreations.homesafe.uitest

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.RetentionPolicy
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.model.StorageUsage
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.RecognitionSection
import com.meticulouscreations.homesafe.ui.screens.ServerSummaryRow
import com.meticulouscreations.homesafe.ui.screens.serverSummary
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Settings rows that open a page of their own: Recognition's face library and custom
 * classifiers, and the Server row last on the page. Each row is one tap target, so the tests tap
 * it by its title and check which page it asked for. The Server row's wording is pinned by
 * ServerSummaryTest; this checks the line is actually on the row.
 *
 * `mainClock.autoAdvance = false` as in the other Settings suites. Nothing here animates for
 * long, but nothing needs time to pass either: every check is on the first frame or a callback.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsNavigationRowsUiTest {

    private val knownCars = ClassifierModel(name = "known_cars", objects = listOf("car"))

    private val deliveryVans = ClassifierModel(name = "delivery_vans", objects = listOf("car", "truck"))

    @Test
    fun recognitionIsLeftOutWhenThereIsNothingToTeach() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                // Face recognition not known yet, and no classifiers: no heading over nothing.
                RecognitionSection(models = emptyList(), faceRecognitionEnabled = null, onOpen = {}, onOpenFaces = {})
                RecognitionSection(models = emptyList(), faceRecognitionEnabled = false, onOpen = {}, onOpenFaces = {})
            }
        }

        onAllNodesWithText("Recognition").assertCountEquals(0)
        onAllNodesWithText("Faces").assertCountEquals(0)
    }

    @Test
    fun theFacesRowAppearsWhenFrigateRecognisesFacesAndOpensTheLibrary() {
        var opened = 0
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                FrigatePreview {
                    RecognitionSection(models = emptyList(), faceRecognitionEnabled = true, onOpen = {}, onOpenFaces = { opened++ })
                }
            }

            onNodeWithText("Recognition").assertIsDisplayed()
            onNodeWithText("Name the faces Frigate saw so it can tell family from strangers").assertIsDisplayed()
            onNodeWithText("Faces").performClick()

            assertEquals(1, opened)
        }
    }

    @Test
    fun eachClassifierGetsARowThatOpensItByName() {
        val opened = mutableListOf<String>()
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                FrigatePreview {
                    RecognitionSection(
                        models = listOf(knownCars, deliveryVans),
                        faceRecognitionEnabled = true,
                        onOpen = { opened += it },
                        onOpenFaces = {},
                    )
                }
            }

            onNodeWithText("Known Cars").assertIsDisplayed()
            onNodeWithText("Label what the car classifier saw, and retrain it").assertIsDisplayed()
            onNodeWithText("Delivery Vans").assertIsDisplayed()
            onNodeWithText("Label what the car and truck classifier saw, and retrain it").assertIsDisplayed()

            onNodeWithText("Delivery Vans").performClick()
            onNodeWithText("Known Cars").performClick()

            // The page is opened by the classifier's config key, not the name on the row.
            assertEquals(listOf("delivery_vans", "known_cars"), opened)
        }
    }

    @Test
    fun classifiersAloneStillGetTheSectionButNoFacesRow() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                RecognitionSection(models = listOf(knownCars), faceRecognitionEnabled = false, onOpen = {}, onOpenFaces = {})
            }
        }

        onNodeWithText("Recognition").assertIsDisplayed()
        onNodeWithText("Known Cars").assertIsDisplayed()
        onAllNodesWithText("Faces").assertCountEquals(0)
    }

    @Test
    fun theServerRowCarriesItsSummaryAndOpensTheDiagnostics() {
        var opened = 0
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                FrigatePreview {
                    ServerSummaryRow(summary = "Tailscale · 55% storage used · healthy", onOpen = { opened++ })
                }
            }

            onNodeWithText("Server").assertIsDisplayed()
            onNodeWithText("Tailscale · 55% storage used · healthy").assertIsDisplayed()
            onNodeWithText("Server").performClick()

            assertEquals(1, opened)
        }
    }

    @Test
    fun theServerRowSaysWhenTheDiskIsNearlyFull() = runComposeUiTest {
        val overview = ServerOverview(
            version = "0.16.1",
            latestVersion = "0.16.1",
            uptimeSeconds = 86_400,
            cpuPercent = 9.0,
            memoryPercent = 40.0,
            recordingsStorage = StorageUsage("/media/frigate/recordings", usedMb = 930_000.0, totalMb = 1_000_000.0),
            detector = null,
            gpus = emptyList(),
            retention = RetentionPolicy(7.0, 7.0, null, null),
            faceRecognitionEnabled = false,
            licensePlateRecognitionEnabled = false,
            semanticSearchEnabled = false,
            cameras = emptyList(),
            canEditConfig = true,
        )
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                ServerSummaryRow(summary = serverSummary(ConnectionRoute.LOCAL_NETWORK, overview, overviewError = null), onOpen = {})
            }
        }

        onNodeWithText("Local network · 93% storage used · disk nearly full").assertIsDisplayed()
    }
}
