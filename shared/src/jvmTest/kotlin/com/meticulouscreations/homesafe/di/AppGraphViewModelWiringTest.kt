package com.meticulouscreations.homesafe.di

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.viewmodel.AppShellViewModel
import com.meticulouscreations.homesafe.viewmodel.CameraDetailViewModel
import com.meticulouscreations.homesafe.viewmodel.ClassifierLabelingViewModel
import com.meticulouscreations.homesafe.viewmodel.DetectionZonesViewModel
import com.meticulouscreations.homesafe.viewmodel.FaceLibraryViewModel
import com.meticulouscreations.homesafe.viewmodel.HomeViewModel
import com.meticulouscreations.homesafe.viewmodel.MomentsViewModel
import com.meticulouscreations.homesafe.viewmodel.PropertyMapViewModel
import com.meticulouscreations.homesafe.viewmodel.SecureConnectionViewModel
import com.meticulouscreations.homesafe.viewmodel.SettingsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Metro validates every binding at compile time, so what's left to check at runtime is that each
 * screen's view model actually landed in the factory's maps — a view model that forgets its
 * `@ContributesIntoMap` compiles fine and then fails the first time its screen opens.
 */
class AppGraphViewModelWiringTest {

    @Test
    fun everyScreenViewModelIsRegisteredWithTheFactory() {
        val graph = createAppGraph(PlatformContext())

        assertEquals(
            setOf(
                HomeViewModel::class,
                PropertyMapViewModel::class,
                MomentsViewModel::class,
                SettingsViewModel::class,
                SecureConnectionViewModel::class,
                AppShellViewModel::class,
                FaceLibraryViewModel::class,
            ),
            graph.viewModelProviders.keys,
        )
        assertEquals(
            setOf(CameraDetailViewModel.Factory::class, DetectionZonesViewModel.Factory::class, ClassifierLabelingViewModel.Factory::class),
            graph.manualAssistedFactoryProviders.keys,
        )
        assertIs<HomeSafeViewModelFactory>(graph.metroViewModelFactory)
    }
}
