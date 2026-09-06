package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.FaceLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FaceRepositoryImplTest {

    @Test
    fun splitsFrigatesFoldersIntoPeopleAndAttempts() {
        val library = mapOf(
            "train" to listOf(
                "1788661300.1-abc123-1788661305.2-unknown-0.55.webp",
                "1788661400.7-def456-1788661409.9-andrew-0.93.webp",
            ),
            "sarah" to emptyList(),
            "andrew" to listOf("andrew_1788661000.12.webp", "andrew_1788661200.5.webp"),
        ).toLibrary()

        assertEquals(listOf("andrew", "sarah"), library.people.map { it.name }, "people sort by name, the attempts folder is not a person")
        assertEquals(listOf("andrew_1788661200.5.webp", "andrew_1788661000.12.webp"), library.people[0].imageFiles, "newest registered image first")
        assertEquals(setOf("andrew"), library.knownNames, "someone with no faces filed yet isn't familiar")

        assertEquals(listOf("1788661400.7-def456", "1788661300.1-abc123"), library.attempts.map { it.eventId }, "newest attempt first")
        val unknown = library.attempts[1]
        assertEquals("unknown", unknown.guessedCategory)
        assertEquals(0.55, unknown.guessedScore)
        assertEquals("andrew", library.attempts[0].guessedCategory)
    }

    @Test
    fun anEmptyServerAnswerIsAnEmptyLibrary() {
        val library = emptyMap<String, List<String>>().toLibrary()
        assertEquals(FaceLibrary.EMPTY, library)
        assertNull(library.people.firstOrNull())
    }

    @Test
    fun personKeysAreFrigateSafe() {
        assertEquals("andrew", FaceLibrary.personKey("Andrew"))
        assertEquals("ron_judy", FaceLibrary.personKey("Ron & Judy"))
        assertEquals("sarahs_mom", FaceLibrary.personKey("Sarah's Mom"))
        assertEquals("person", FaceLibrary.personKey("!!!"))
    }
}
