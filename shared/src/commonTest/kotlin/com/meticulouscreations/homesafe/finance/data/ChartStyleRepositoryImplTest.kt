package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.data.ChartPreferencesEntity
import com.meticulouscreations.homesafe.data.InMemorySettingsDao
import com.meticulouscreations.homesafe.finance.domain.ChartHaptics
import com.meticulouscreations.homesafe.finance.domain.ChartShader
import com.meticulouscreations.homesafe.finance.domain.ChartStyle
import com.meticulouscreations.homesafe.finance.domain.LineSharpness
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChartStyleRepositoryImplTest {

    @Test
    fun defaultsUntilSomethingIsSaved() = runTest {
        assertEquals(ChartStyle.DEFAULT, ChartStyleRepositoryImpl(InMemorySettingsDao()).observe().first())
    }

    @Test
    fun roundTripsEveryChoice() = runTest {
        val repository = ChartStyleRepositoryImpl(InMemorySettingsDao())
        val style = ChartStyle(shader = ChartShader.HALFTONE, sharpness = LineSharpness.POINTS, haptics = ChartHaptics.STRONG)

        repository.update(style)

        assertEquals(style, repository.observe().first())
    }

    @Test
    fun anUnknownSavedChoiceFallsBackToItsDefaultAlone() = runTest {
        val dao = InMemorySettingsDao()
        dao.upsertChartPreferences(ChartPreferencesEntity(shader = "HOLOGRAM", sharpness = "SHARP", haptics = "OFF"))

        assertEquals(
            ChartStyle(shader = ChartStyle.DEFAULT.shader, sharpness = LineSharpness.SHARP, haptics = ChartHaptics.OFF),
            ChartStyleRepositoryImpl(dao).observe().first(),
        )
    }
}
