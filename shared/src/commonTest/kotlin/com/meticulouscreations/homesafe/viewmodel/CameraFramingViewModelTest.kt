package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.domain.model.CameraCrop
import com.meticulouscreations.homesafe.domain.model.CameraFraming
import com.meticulouscreations.homesafe.domain.model.CameraLayout
import com.meticulouscreations.homesafe.domain.model.CameraOrientation
import com.meticulouscreations.homesafe.domain.repository.CameraLayoutRepository
import com.meticulouscreations.homesafe.domain.usecase.ObserveCameraLayoutsUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetCameraFramingUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class CameraFramingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Layouts in memory, with every framing saved kept in order. */
    private class FakeLayouts(initial: Map<String, CameraLayout> = emptyMap()) : CameraLayoutRepository {
        val layouts = MutableStateFlow(initial)
        val saves = mutableListOf<CameraFraming>()
        override fun observeLayouts(): Flow<Map<String, CameraLayout>> = layouts
        override suspend fun saveOrder(cameraNames: List<String>) = Unit
        override suspend fun setOrientation(cameraName: String, orientation: CameraOrientation) = Unit
        override suspend fun setFraming(cameraName: String, orientation: CameraOrientation, crop: CameraCrop) {
            saves += CameraFraming(orientation, crop)
            layouts.update { it + (cameraName to (it[cameraName] ?: CameraLayout()).copy(orientation = orientation, crop = crop)) }
        }
    }

    private val saved = CameraFraming(CameraOrientation(quarterTurns = 1, tiltDegrees = -2.5f), CameraCrop(zoom = 2f, centerX = 0.4f))

    private fun TestScope.viewModel(layouts: FakeLayouts) =
        CameraFramingViewModel("kitchen", ObserveCameraLayoutsUseCase(layouts), SetCameraFramingUseCase(layouts), backgroundScope)

    @Test
    fun opensOnTheSavedFraming() = runTest(dispatcher) {
        val viewModel = viewModel(FakeLayouts(mapOf("kitchen" to CameraLayout(position = 3, orientation = saved.orientation, crop = saved.crop))))
        assertNull(viewModel.framing.value, "nothing to show until the saved framing is read")
        advanceUntilIdle()
        assertEquals(saved, viewModel.framing.value)
    }

    @Test
    fun anUnframedCameraOpensUntouched() = runTest(dispatcher) {
        val viewModel = viewModel(FakeLayouts())
        advanceUntilIdle()
        assertEquals(CameraFraming.UNTOUCHED, viewModel.framing.value)
    }

    @Test
    fun aMovingFingerIsShownAndSavedOnlyWhenLetGo() = runTest(dispatcher) {
        val layouts = FakeLayouts()
        val viewModel = viewModel(layouts)
        advanceUntilIdle()

        val moved = CameraFraming(crop = CameraCrop(zoom = 1.5f))
        viewModel.change(moved)
        advanceUntilIdle()
        assertEquals(moved, viewModel.framing.value)
        assertEquals(emptyList(), layouts.saves, "nothing saved mid-gesture")

        viewModel.save()
        advanceUntilIdle()
        assertEquals(listOf(moved), layouts.saves)
    }

    @Test
    fun savesLandInTheOrderTheyWereMade() = runTest(dispatcher) {
        val layouts = FakeLayouts()
        val viewModel = viewModel(layouts)
        advanceUntilIdle()

        val first = CameraFraming(orientation = CameraOrientation(tiltDegrees = 1f))
        val second = first.copy(orientation = first.orientation.turned(clockwise = true))
        viewModel.set(first)
        viewModel.set(second)
        advanceUntilIdle()
        assertEquals(listOf(first, second), layouts.saves)
        assertEquals(second.orientation, layouts.layouts.value["kitchen"]?.orientation)
    }

    @Test
    fun cancelPutsBackWhatTheEditorOpenedOn() = runTest(dispatcher) {
        val layouts = FakeLayouts(mapOf("kitchen" to CameraLayout(orientation = saved.orientation, crop = saved.crop)))
        val viewModel = viewModel(layouts)
        advanceUntilIdle()

        viewModel.set(CameraFraming.UNTOUCHED)
        viewModel.cancel()
        advanceUntilIdle()
        assertEquals(saved, viewModel.framing.value)
        assertEquals(saved, layouts.layouts.value["kitchen"]?.framing)
    }
}
