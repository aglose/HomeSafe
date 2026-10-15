package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.data.ChartPreferencesEntity
import com.meticulouscreations.homesafe.data.SettingsDao
import com.meticulouscreations.homesafe.finance.domain.ChartHaptics
import com.meticulouscreations.homesafe.finance.domain.ChartShader
import com.meticulouscreations.homesafe.finance.domain.ChartStyle
import com.meticulouscreations.homesafe.finance.domain.ChartStyleRepository
import com.meticulouscreations.homesafe.finance.domain.LineSharpness
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChartStyleRepositoryImpl(private val settingsDao: SettingsDao) : ChartStyleRepository {

    override fun observe(): Flow<ChartStyle> = settingsDao.observeChartPreferences().map { entity ->
        if (entity == null) {
            ChartStyle.DEFAULT
        } else {
            // A name this build doesn't know (a downgrade after a newer one added a style) falls
            // back to that field's default rather than crashing.
            ChartStyle(
                shader = ChartShader.entries.firstOrNull { it.name == entity.shader } ?: ChartStyle.DEFAULT.shader,
                sharpness = LineSharpness.entries.firstOrNull { it.name == entity.sharpness } ?: ChartStyle.DEFAULT.sharpness,
                haptics = ChartHaptics.entries.firstOrNull { it.name == entity.haptics } ?: ChartStyle.DEFAULT.haptics,
            )
        }
    }

    override suspend fun update(style: ChartStyle) {
        settingsDao.upsertChartPreferences(ChartPreferencesEntity(shader = style.shader.name, sharpness = style.sharpness.name, haptics = style.haptics.name))
    }
}
