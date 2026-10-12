package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.data.FitnessDao
import com.meticulouscreations.homesafe.data.FitnessPreferencesEntity
import com.meticulouscreations.homesafe.fitness.domain.FigureStyle
import com.meticulouscreations.homesafe.fitness.domain.FitnessPreferencesRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The fitness app's preferences in the app's database: one row of [FitnessDao]'s, apart from the training log. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FitnessPreferencesRepositoryImpl(private val dao: FitnessDao) : FitnessPreferencesRepository {

    override fun observeFigureStyle(): Flow<FigureStyle> = dao.observePreferences().map { FigureStyle.fromName(it?.figureStyle) }.distinctUntilChanged()

    override suspend fun setFigureStyle(style: FigureStyle) {
        dao.upsertPreferences(FitnessPreferencesEntity(figureStyle = style.name))
    }
}
