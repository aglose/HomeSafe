package com.meticulouscreations.homesafe.di

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import kotlin.reflect.KClass

/**
 * The one [androidx.lifecycle.ViewModelProvider.Factory] for the whole app. Every view model is
 * contributed into these maps by its own annotations — `@ViewModelKey @ContributesIntoMap` on a
 * plain `@Inject` view model, or `@ManualViewModelAssistedFactoryKey @ContributesIntoMap` on the
 * nested factory of one that takes runtime arguments (a camera name) — so adding a screen never
 * touches this file or [AppGraph]. Composables reach it through `LocalMetroViewModelFactory`,
 * installed once in [com.meticulouscreations.homesafe.App].
 */
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class HomeSafeViewModelFactory(
    override val viewModelProviders: Map<KClass<out ViewModel>, () -> ViewModel>,
    override val assistedFactoryProviders: Map<KClass<out ViewModel>, () -> ViewModelAssistedFactory>,
    override val manualAssistedFactoryProviders: Map<KClass<out ManualViewModelAssistedFactory>, () -> ManualViewModelAssistedFactory>,
) : MetroViewModelFactory()
