package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.finance.domain.EconomyTone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FinancePreferencesRepositoryImplTest {

    @Test
    fun straightTalkUntilSomethingIsSaved() = runTest {
        assertEquals(EconomyTone.STRAIGHT, FinancePreferencesRepositoryImpl(InMemorySettingsDao()).observeEconomyTone().first())
    }

    @Test
    fun roundTripsTheTone() = runTest {
        val repository = FinancePreferencesRepositoryImpl(InMemorySettingsDao())

        repository.setEconomyTone(EconomyTone.BRIGHT_SIDE)

        assertEquals(EconomyTone.BRIGHT_SIDE, repository.observeEconomyTone().first())
    }

    @Test
    fun anUnknownSavedToneFallsBackToTheDefault() = runTest {
        val dao = InMemorySettingsDao()
        dao.upsertFinancePreferences(FinancePreferencesEntity(economyTone = "SNARKY"))

        assertEquals(EconomyTone.DEFAULT, FinancePreferencesRepositoryImpl(dao).observeEconomyTone().first())
    }
}
