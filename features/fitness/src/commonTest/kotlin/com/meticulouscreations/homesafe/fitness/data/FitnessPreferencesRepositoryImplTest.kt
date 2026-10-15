package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.data.FitnessPreferencesEntity
import com.meticulouscreations.homesafe.data.InMemoryFitnessDao
import com.meticulouscreations.homesafe.fitness.FitnessTestData.exercise
import com.meticulouscreations.homesafe.fitness.domain.FigureStyle
import com.meticulouscreations.homesafe.fitness.domain.SetDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FitnessPreferencesRepositoryImplTest {

    @Test
    fun withNothingSavedTheFigureIsTheRenderedOne() = runTest {
        assertEquals(FigureStyle.TRUE_3D, FitnessPreferencesRepositoryImpl(InMemoryFitnessDao()).observeFigureStyle().first())
    }

    @Test
    fun aChosenFigureIsReadBackByWhoeverAsksNext() = runTest {
        val dao = InMemoryFitnessDao()
        FitnessPreferencesRepositoryImpl(dao).setFigureStyle(FigureStyle.STONE)

        assertEquals(FigureStyle.STONE, FitnessPreferencesRepositoryImpl(dao).observeFigureStyle().first())

        FitnessPreferencesRepositoryImpl(dao).setFigureStyle(FigureStyle.FORGED)
        assertEquals(FigureStyle.FORGED, FitnessPreferencesRepositoryImpl(dao).observeFigureStyle().first())
    }

    @Test
    fun aStyleThisBuildDoesNotKnowFallsBackToTheDefault() = runTest {
        val dao = InMemoryFitnessDao()
        dao.upsertPreferences(FitnessPreferencesEntity(figureStyle = "HOLOGRAM"))

        assertEquals(FigureStyle.DEFAULT, FitnessPreferencesRepositoryImpl(dao).observeFigureStyle().first())
    }

    @Test
    fun theChoiceIsNoPartOfTheTrainingLogOrOfACopyOfIt() = runTest {
        val dao = InMemoryFitnessDao()
        val log = FitnessRepositoryImpl(dao)
        FitnessPreferencesRepositoryImpl(dao).setFigureStyle(FigureStyle.STONE)
        val bench = exercise()
        log.saveExercises(listOf(bench))
        log.addSets(listOf(SetDraft(bench.id, 135.0, 8, 1_000)))

        // A copy brought in to an install that had chosen nothing leaves that install's figure as it was.
        val other = InMemoryFitnessDao()
        FitnessRepositoryImpl(other).bringIn(log.logCopy())

        assertEquals(1, FitnessRepositoryImpl(other).sets.first().size)
        assertEquals(FigureStyle.DEFAULT, FitnessPreferencesRepositoryImpl(other).observeFigureStyle().first())
        assertEquals(FigureStyle.STONE, FitnessPreferencesRepositoryImpl(dao).observeFigureStyle().first())
    }
}
