package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateState
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Frigate's face library, reached from the Settings page's Faces row: who the server knows, the
 * face it's waiting for a name for, and every decision about it going to `/api/faces/...` and
 * showing up on the server. The household scenario knows Alice (two faces) and Bob (one), and
 * has one unfiled face that Frigate couldn't place.
 */
class FaceLibraryJourneyTest {

    @Test
    fun theFacesRowOpensTheLibraryWithItsPeopleAndTheFaceWaitingForAName() = runAppJourney {
        val library = FaceLibraryRobot(this)
        signIn.signInAs()
        library.open()

        library.awaitPerson("Alice", faces = 2)
        library.awaitPerson("Bob", faces = 1)
        library.awaitWaiting(1)
        // One chip per person on the waiting face, to file it under them.
        library.revealWaitingFace(library.personChip("Alice"))
        awaitText("Frigate doesn't recognise this face")
        awaitNode(library.personChip("Bob"), "Bob's chip on the waiting face")
        assertTrue(server.received { it.method == "GET" && it.path == "/api/faces" }, "${server.requests}")

        library.back()
        SettingsRobot(this).awaitPage()
    }

    @Test
    fun filingTheWaitingFaceUnderSomeoneRegistersItWithThemOnTheServer() = runAppJourney {
        val attempt = state.edit { this.faces.getValue(FakeFrigateState.TRAIN_FOLDER).single() }
        val library = FaceLibraryRobot(this)
        signIn.signInAs()
        library.open()
        library.awaitWaiting(1)

        library.fileWaitingFaceUnder("Bob")

        val request = server.awaitRequest(description = "the face filed under Bob") { it.method == "POST" && it.path == "/api/faces/train/Bob/classify" }
        assertEquals(attempt, SettingsRobot.jsonBodyOf(request)?.get("training_file")?.jsonPrimitive?.content, "the waiting face was the one filed: $request")
        library.awaitWaiting(0)
        library.awaitPerson("Bob", faces = 2)
        library.awaitPerson("Alice", faces = 2)
        assertTrue(state.edit { attempt in this.faces.getValue("Bob") }, "the server moved it into Bob's folder")
        assertTrue(state.edit { this.faces.getValue(FakeFrigateState.TRAIN_FOLDER).isEmpty() }, "and out of the unfiled ones")
    }

    @Test
    fun notOneOfUsThrowsTheWaitingFaceAway() = runAppJourney {
        val attempt = state.edit { this.faces.getValue(FakeFrigateState.TRAIN_FOLDER).single() }
        val library = FaceLibraryRobot(this)
        signIn.signInAs()
        library.open()
        library.awaitWaiting(1)

        library.discardWaitingFace()

        val request = server.awaitRequest(description = "the unfiled face deleted") { it.method == "POST" && it.path == "/api/faces/train/delete" }
        val ids = SettingsRobot.jsonBodyOf(request)?.get("ids")?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf(attempt), ids, "just the waiting face: $request")
        library.awaitWaiting(0)
        library.awaitPerson("Alice", faces = 2)
        library.awaitPerson("Bob", faces = 1)
        assertTrue(state.edit { this.faces.getValue(FakeFrigateState.TRAIN_FOLDER).isEmpty() }, "gone from the server")
        assertFalse(server.received { it.path.endsWith("/classify") }, "nobody was given it: ${server.requests}")
    }

    @Test
    fun aNewPersonIsCreatedOnTheServerAndTheWaitingFaceCanBeFiledUnderThem() = runAppJourney {
        val library = FaceLibraryRobot(this)
        signIn.signInAs()
        library.open()
        library.awaitLoaded()

        library.addPerson("Carol")

        // Folder names double as sub-labels, so "Carol" is kept on the server as `carol`.
        server.awaitRequest(description = "Carol's folder created") { it.method == "POST" && it.path == "/api/faces/carol/create" }
        awaitText("Added Carol. File a face under them to start recognising.")
        library.awaitPerson("Carol", faces = 0)
        assertTrue(state.edit { "carol" in this.faces }, "the server has an (empty) folder for her")

        library.fileWaitingFaceUnder("Carol")

        server.awaitRequest(description = "the face filed under Carol") { it.method == "POST" && it.path == "/api/faces/train/carol/classify" }
        library.awaitPerson("Carol", faces = 1)
        library.awaitWaiting(0)
    }

    @Test
    fun aViewerAccountIsToldTheLibraryCouldNotBeLoaded() = runAppJourney {
        val library = FaceLibraryRobot(this)
        signIn.signInAs(FakeFrigateState.VIEWER)
        library.open()

        // Frigate keeps the face library to admins; the viewer's session gets a 401.
        awaitText("Couldn't load the face library: Couldn't load faces: 401", substring = true)
        awaitNode(hasText("Retry") and hasClickAction(), "the Retry button")
        assertFalse(exists(hasText("People")), "no library behind the error")
    }
}
