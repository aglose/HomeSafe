package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.finance.domain.EconomyTone
import com.meticulouscreations.homesafe.finance.domain.FinancePreferencesRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FinancePreferencesRepositoryImpl(private val settingsDao: SettingsDao) : FinancePreferencesRepository {

    override fun observeEconomyTone(): Flow<EconomyTone> =
        settingsDao.observeFinancePreferences().map { EconomyTone.fromName(it?.economyTone) }.distinctUntilChanged()

    override suspend fun setEconomyTone(tone: EconomyTone) {
        settingsDao.upsertFinancePreferences(FinancePreferencesEntity(economyTone = tone.name))
    }
}
