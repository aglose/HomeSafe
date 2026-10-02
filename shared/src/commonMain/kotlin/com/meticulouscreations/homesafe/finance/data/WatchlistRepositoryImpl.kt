package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.data.SettingsDao
import com.meticulouscreations.homesafe.data.WatchedSymbolEntity
import com.meticulouscreations.homesafe.finance.domain.InstrumentKind
import com.meticulouscreations.homesafe.finance.domain.Position
import com.meticulouscreations.homesafe.finance.domain.WatchedSymbol
import com.meticulouscreations.homesafe.finance.domain.WatchlistRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WatchlistRepositoryImpl(private val settingsDao: SettingsDao) : WatchlistRepository {

    override fun observe(): Flow<List<WatchedSymbol>> = settingsDao.observeWatchedSymbols().map { rows -> rows.map { it.toDomain() } }

    override suspend fun save(symbol: WatchedSymbol) {
        settingsDao.upsertWatchedSymbol(
            WatchedSymbolEntity(
                symbol = symbol.symbol,
                name = symbol.name,
                kind = symbol.kind.name,
                addedAtEpochSeconds = symbol.addedAtEpochSeconds,
                shares = symbol.position?.shares,
                costPerShare = symbol.position?.costPerShare,
            ),
        )
    }

    override suspend fun remove(symbol: String) = settingsDao.deleteWatchedSymbol(symbol)

    private fun WatchedSymbolEntity.toDomain() = WatchedSymbol(
        symbol = symbol,
        name = name,
        // A kind this build doesn't know (written by a newer one) is guessed again from the ticker.
        kind = InstrumentKind.entries.firstOrNull { it.name == kind }
            ?: if (symbol.endsWith("-USD")) InstrumentKind.CRYPTO else InstrumentKind.EQUITY,
        addedAtEpochSeconds = addedAtEpochSeconds,
        position = shares?.let { Position(it, costPerShare) },
    )
}
