package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.CameraFraming
import com.meticulouscreations.homesafe.domain.usecase.ObserveCameraLayoutsUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetCameraFramingUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The framing editor for one camera: its picture turned, straightened, zoomed and cropped.
 *
 * Opens on the camera's saved framing and follows the editor from then on: each change shows at
 * once ([change]) and is saved when the finger lets go ([save]), as Arrange mode saves as it goes,
 * so leaving the editor any way keeps what was done. [cancel] puts back the framing it opened on.
 */
@AssistedInject
class CameraFramingViewModel(
    @Assisted private val cameraName: String,
    observeCameraLayoutsUseCase: ObserveCameraLayoutsUseCase,
    private val setCameraFramingUseCase: SetCameraFramingUseCase,
    private val appScope: CoroutineScope,
) : ViewModel() {

    /** One view model per camera; the screen keys it by [cameraName]. */
    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(cameraName: String): CameraFramingViewModel
    }

    private val _framing = MutableStateFlow<CameraFraming?>(null)

    /** The framing as the editor shows it; null until the saved one has been read (a few milliseconds). */
    val framing: StateFlow<CameraFraming?> = _framing.asStateFlow()

    /** What the camera was framed as when the editor opened: what [cancel] goes back to. */
    private var opening: CameraFraming? = null

    /**
     * Saves in the order they were asked for: a quick turn after a drag mustn't land before it.
     * On the app's scope, not this one: Cancel or Done leave the editor at once, and the save
     * they ask for must outlive it.
     */
    private val saving = Mutex()

    init {
        viewModelScope.launch {
            val saved = observeCameraLayoutsUseCase().first()[cameraName]?.framing ?: CameraFraming.UNTOUCHED
            opening = saved
            // A change made in the moment before the read came back is newer than it.
            _framing.compareAndSet(null, saved)
        }
    }

    /** Shows [framing] at once, without saving it: a finger still moving. */
    fun change(framing: CameraFraming) {
        _framing.value = framing
    }

    /** Saves the framing as it is now. */
    fun save() {
        val framing = _framing.value ?: return
        // Undispatched, so each save queues for the lock in the order it was asked for.
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            saving.withLock { setCameraFramingUseCase(cameraName, framing.orientation, framing.crop) }
        }
    }

    /** [change] and [save] together: a button, which has no in-between. */
    fun set(framing: CameraFraming) {
        change(framing)
        save()
    }

    /** Back to the framing the editor opened on, saved. */
    fun cancel() {
        opening?.let(::set)
    }
}
