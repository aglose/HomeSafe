package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.CameraFraming
import com.meticulouscreations.homesafe.domain.model.VideoFit
import com.meticulouscreations.homesafe.domain.usecase.GetLiveStreamUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetLiveWebRtcSignalingUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCameraLayoutsUseCase
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A Google Nest camera's live view. There is nothing to load: the card already had the video
 * playing, and the screen carries on with that, moving up to the camera's own stream (the card
 * may play a smaller one) and adding sound, which the card's stream doesn't ask go2rtc for.
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
    private val observeCameraLayoutsUseCase: ObserveCameraLayoutsUseCase,
) : ViewModel() {

    /** How [streamName]'s picture is framed: as turned and cropped from the Home page's Arrange mode. */
    fun framing(streamName: String): Flow<CameraFraming> = observeCameraLayoutsUseCase().map { it[streamName]?.framing ?: CameraFraming.UNTOUCHED }

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

    /**
     * The speaker: the same saved choice as a Frigate camera's page, so sound stays on (or off)
     * from one camera to the next and from one visit to the next. Off until it has been turned on
     * once: a room that starts talking unasked is a jump scare.
     */
    val soundOn: StateFlow<Boolean> = observePlaybackPreferencesUseCase()
        .map { it.soundOn }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Flips the speaker and saves it, keeping the other saved choices as they are. */
    fun toggleSound() {
        viewModelScope.launch {
            val stored = observePlaybackPreferencesUseCase().first()
            updatePlaybackPreferencesUseCase(stored.copy(soundOn = !stored.soundOn))
        }
    }

    /**
     * [streamName] over whichever route is in use, following it when it changes; null while
     * signed out. [withSound] adds the audio track; with or without, it is the same go2rtc
     * stream, so switching between them doesn't make go2rtc dial Google again. [stillUrl] is the
     * card's last-seen still (see `LastSeenStill`), shown until the video has a frame.
     */
    fun liveSource(streamName: String, withSound: Boolean, stillUrl: String? = null): Flow<VideoSource.Live?> =
        observeCurrentServerUrlUseCase().map { serverUrl ->
            serverUrl?.let {
                VideoSource.Live(
                    url = getLiveStreamUrlUseCase(it, streamName, audioCodecs = if (withSound) liveAudioCodecs else emptyList()),
                    posterUrl = stillUrl,
                    webRtc = WebRtcEndpoint(getLiveWebRtcSignalingUrlUseCase(it, streamName), audio = withSound),
                    lastSeenPoster = stillUrl != null,
                )
            }
        }
}
