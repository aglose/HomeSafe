package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import com.meticulouscreations.homesafe.domain.model.HomeLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mapping either side of the placement store: what a stored row means, and what a build that
 * doesn't recognise the stored values should do with them rather than fall over.
 */
class PropertyLayoutRepositoryImplTest {

    private fun repository(dao: PropertyLayoutDao = InMemoryPropertyLayoutDao()) =
        PropertyLayoutRepositoryImpl(dao)

    @Test
    fun aPlacementSurvivesTheRoundTrip() = runTest {
        val repository = repository()

        repository.place(CameraPlacement("front_door", x = 0.25f, y = 0.75f))

        assertEquals(listOf(CameraPlacement("front_door", 0.25f, 0.75f)), repository.observePlacements().first())
    }

    @Test
    fun placingTheSameCameraAgainMovesItRatherThanAddingASecondMarker() = runTest {
        val repository = repository()

        repository.place(CameraPlacement("front_door", 0.1f, 0.1f))
        repository.place(CameraPlacement("front_door", 0.9f, 0.4f))

        assertEquals(listOf(CameraPlacement("front_door", 0.9f, 0.4f)), repository.observePlacements().first())
    }

    @Test
    fun removingAPlacementLeavesTheRest() = runTest {
        val repository = repository()
        repository.place(CameraPlacement("front_door", 0.1f, 0.1f))
        repository.place(CameraPlacement("garage", 0.8f, 0.2f))

        repository.removePlacement("front_door")

        assertEquals(listOf(CameraPlacement("garage", 0.8f, 0.2f)), repository.observePlacements().first())
    }

    @Test
    fun aRowOutsideThePlanIsClampedToItsEdgeRatherThanDropped() = runTest {
        val dao = InMemoryPropertyLayoutDao()
        // Only reachable by a row a newer build wrote, or a corrupted one — CameraPlacement's own
        // constructor would reject these, so the clamping has to happen before it is built.
        dao.upsertPlacement(CameraPlacementEntity("attic", x = 3f, y = -1f))

        assertEquals(listOf(CameraPlacement("attic", 1f, 0f)), repository(dao).observePlacements().first())
    }

    @Test
    fun noStoredLayoutMeansTheList() = runTest {
        assertEquals(HomeLayout.DEFAULT, repository().observeHomeLayout().first())
    }

    @Test
    fun theStoredLayoutComesBack() = runTest {
        val repository = repository()

        repository.setHomeLayout(HomeLayout.MAP)

        assertEquals(HomeLayout.MAP, repository.observeHomeLayout().first())
    }

    @Test
    fun aLayoutThisBuildDoesNotKnowFallsBackToTheDefault() = runTest {
        val dao = InMemoryPropertyLayoutDao()
        dao.upsertHomeLayout(HomeLayoutEntity(layout = "HOLOGRAM"))

        assertEquals(HomeLayout.DEFAULT, repository(dao).observeHomeLayout().first(), "a downgrade shows the list rather than failing to read")
    }
}
