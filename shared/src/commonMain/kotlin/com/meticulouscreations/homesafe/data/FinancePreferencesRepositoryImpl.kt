package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.finance.domain.EconomyTone
import com.meticulouscreations.homesafe.finance.domain.FinanceDrawerItem
import com.meticulouscreations.homesafe.finance.domain.FinancePreferencesRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FinancePreferencesRepositoryImpl(private val settingsDao: SettingsDao) : FinancePreferencesRepository {

    /** Each preference is written onto the row as it stands, one change at a time, so neither undoes the other. */
    private val writing = Mutex()

    override fun observeEconomyTone(): Flow<EconomyTone> =
        settingsDao.observeFinancePreferences().map { EconomyTone.fromName(it?.economyTone) }.distinctUntilChanged()

    override suspend fun setEconomyTone(tone: EconomyTone) = edit { it.copy(economyTone = tone.name) }

    override fun observeDrawerItems(): Flow<List<FinanceDrawerItem>> =
        settingsDao.observeFinancePreferences().map { FinanceDrawerItem.parse(it?.drawerItems) }.distinctUntilChanged()

    override suspend fun setDrawerItems(items: List<FinanceDrawerItem>) = edit { it.copy(drawerItems = FinanceDrawerItem.store(items)) }

    private suspend fun edit(change: (FinancePreferencesEntity) -> FinancePreferencesEntity) = writing.withLock {
        val current = settingsDao.observeFinancePreferences().first() ?: FinancePreferencesEntity()
        settingsDao.upsertFinancePreferences(change(current))
    }
}
