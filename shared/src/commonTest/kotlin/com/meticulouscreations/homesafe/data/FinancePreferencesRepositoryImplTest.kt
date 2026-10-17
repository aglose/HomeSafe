package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.finance.domain.EconomyTone
import com.meticulouscreations.homesafe.finance.domain.FinanceDrawerItem
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

    @Test
    fun theDrawerCardShowsTheSp500AndNetWorthUntilSomethingIsSaved() = runTest {
        assertEquals(listOf(FinanceDrawerItem.SP500, FinanceDrawerItem.NET_WORTH), FinancePreferencesRepositoryImpl(InMemorySettingsDao()).observeDrawerItems().first())
    }

    @Test
    fun theDrawerItemsAndTheToneAreKeptSideBySide() = runTest {
        val repository = FinancePreferencesRepositoryImpl(InMemorySettingsDao())

        repository.setEconomyTone(EconomyTone.BRIGHT_SIDE)
        repository.setDrawerItems(listOf(FinanceDrawerItem.NET_WORTH, FinanceDrawerItem.DOW, FinanceDrawerItem.CASH))
        repository.setEconomyTone(EconomyTone.STRAIGHT)

        // Shown markets first, in the card's order, whatever order they were picked in.
        assertEquals(listOf(FinanceDrawerItem.DOW, FinanceDrawerItem.NET_WORTH, FinanceDrawerItem.CASH), repository.observeDrawerItems().first())
        assertEquals(EconomyTone.STRAIGHT, repository.observeEconomyTone().first())
    }

    @Test
    fun aCardChosenToShowNothingStaysEmpty() = runTest {
        val repository = FinancePreferencesRepositoryImpl(InMemorySettingsDao())

        repository.setDrawerItems(emptyList())

        assertEquals(emptyList(), repository.observeDrawerItems().first())
    }

    @Test
    fun unknownDrawerItemsAreDroppedAndNoMoreThanFourAreShown() = runTest {
        val dao = InMemorySettingsDao()
        dao.upsertFinancePreferences(FinancePreferencesEntity(drawerItems = "LITECOIN,SP500,DOW,NASDAQ,GOLD,CASH"))

        assertEquals(
            listOf(FinanceDrawerItem.SP500, FinanceDrawerItem.DOW, FinanceDrawerItem.NASDAQ, FinanceDrawerItem.GOLD),
            FinancePreferencesRepositoryImpl(dao).observeDrawerItems().first(),
        )
    }
}
