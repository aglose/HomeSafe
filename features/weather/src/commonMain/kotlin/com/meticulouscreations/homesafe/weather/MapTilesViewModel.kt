package com.meticulouscreations.homesafe.weather

import androidx.lifecycle.ViewModel
import com.meticulouscreations.homesafe.weather.data.MapTileStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey

/** The app's map tiles, for a map outside the weather app (the cars', a run's route): the one tile store the radar draws from too. */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class MapTilesViewModel(val tiles: MapTileStore) : ViewModel()
