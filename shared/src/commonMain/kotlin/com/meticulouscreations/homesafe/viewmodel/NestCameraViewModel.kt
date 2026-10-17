package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.VideoFit
import com.meticulouscreations.homesafe.domain.usecase.GetLiveStreamUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetLiveWebRtcSignalingUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCurrentServerUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObservePlaybackPreferencesUseCase
import com.meticulouscreations.homesafe.domain.usecase.UpdatePlaybackPreferencesUseCase
import com.meticulouscreations.homesafe.ui.components.VideoSource
import com.meticulouscreations.homesafe.ui.components.WebRtcEndpoint
import com.meticulouscreations.homesafe.ui.components.liveAudioCodecs
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A Google Nest camera's live view. There is nothing to load: the card already had the video
 * playing, and the screen carries on with that. All this adds is sound, which the card's
 * stream doesn't ask go2rtc for.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class NestCameraViewModel(
    private val observeCurrentServerUrlUseCase: ObserveCurrentServerUrlUseCase,
    private val getLiveStreamUrlUseCase: GetLiveStreamUrlUseCase,
    private val getLiveWebRtcSignalingUrlUseCase: GetLiveWebRtcSignalingUrlUseCase,
    private val observePlaybackPreferencesUseCase: ObservePlaybackPreferencesUseCase,
    private val updatePlaybackPreferencesUseCase: UpdatePlaybackPreferencesUseCase,
) : ViewModel() {

    /** How the Nest cameras' pictures sit in their boxes: saved for every Nest camera, cards included. */
    val fit: StateFlow<VideoFit> = observePlaybackPreferencesUseCase()
        .map { it.nestFit }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoFit.Fit)

    /** Flips between the whole picture and filling the frame, and saves it. */
    fun toggleFit() {
        viewModelScope.launch {
            val stored = observePlaybackPreferencesUseCase().first()
            updatePlaybackPreferencesUseCase(stored.copy(nestFit = stored.nestFit.other))
        }
    }

    private val _soundOn = MutableStateFlow(false)

    /** Off on the way in, like every camera: a room that starts talking unasked is a jump scare. */
    val soundOn: StateFlow<Boolean> = _soundOn.asStateFlow()

    fun toggleSound() = _soundOn.update { !it }

    /**
     * [streamName] over whichever route is in use, following it when it changes; null while
     * signed out. Silent, it is exactly what the card was playing (so the hand-over doesn't
     * reconnect); [withSound] adds the audio track. Either way it is the same go2rtc stream, so
     * switching between them doesn't make go2rtc dial Google again.
     */
    fun liveSource(streamName: String, withSound: Boolean): Flow<VideoSource.Live?> =
        observeCurrentServerUrlUseCase().map { serverUrl ->
            serverUrl?.let {
                VideoSource.Live(
                    url = getLiveStreamUrlUseCase(it, streamName, audioCodecs = if (withSound) liveAudioCodecs else emptyList()),
                    webRtc = WebRtcEndpoint(getLiveWebRtcSignalingUrlUseCase(it, streamName), audio = withSound),
                )
            }
        }
}
