package com.meticulouscreations.homesafe.packages

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.packages.domain.PackagesBoard
import com.meticulouscreations.homesafe.packages.domain.PackagesRepository
import com.meticulouscreations.homesafe.packages.domain.PackagesSnapshot
import com.meticulouscreations.homesafe.packages.domain.PackagesSync
import com.meticulouscreations.homesafe.packages.domain.ParcelChange
import com.meticulouscreations.homesafe.packages.domain.ParcelZone
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.packages_error_unreachable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

@Immutable
data class PackagesUiState(
    val loaded: Boolean = false,
    val snapshot: PackagesSnapshot = PackagesSnapshot(),
    val board: PackagesBoard = PackagesBoard(),
    val sync: PackagesSync = PackagesSync(),
    /** The household's clock, as near as this phone can tell. */
    val zone: ParcelZone = ParcelZone.fixed(0),
    /** Something just tried didn't work, until someone has seen it. */
    val notice: UiText? = null,
    /** A tracking number was just accepted. */
    val added: Boolean = false,
)

/**
 * The packages app: what is on its way to the house and what the door camera saw arrive, all of it
 * from the relay. [setActive] asks while the drawer's card or the app is up; the list then comes
 * again every so often while the app itself is on screen, since a parcel's whole point is that
 * something happens to it while you are looking the other way.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class PackagesViewModel(private val repository: PackagesRepository, private val clock: Clock) : ViewModel() {

    private val _uiState = MutableStateFlow(PackagesUiState(zone = ParcelZone.of(TimeZone.currentSystemDefault())))
    val uiState: StateFlow<PackagesUiState> = _uiState.asStateFlow()

    private var following: Job? = null
    private var polling: Job? = null
    private var refreshedAt = 0L

    fun setActive(active: Boolean, full: Boolean = true) {
        if (!active) {
            polling?.cancel()
            polling = null
            return
        }
        repository.forgetIfSignedOut()
        if (following == null) {
            following = viewModelScope.launch {
                launch { repository.sync.collect { sync -> _uiState.update { it.copy(sync = sync) } } }
                repository.snapshot.collect { snapshot ->
                    _uiState.update {
                        if (snapshot == null) {
                            it.copy(loaded = false, snapshot = PackagesSnapshot(), board = PackagesBoard())
                        } else {
                            it.copy(loaded = true, snapshot = snapshot, board = PackagesBoard.of(snapshot.parcels, snapshot.today, snapshot.nowEpochSeconds))
                        }
                    }
                }
            }
        }
        val now = clock.now().epochSeconds
        if (now - refreshedAt > (if (full) FULL_REFRESH_SECONDS else DRAWER_REFRESH_SECONDS)) refresh()
        if (full && polling?.isActive != true) {
            polling = viewModelScope.launch {
                while (true) {
                    delay(POLL_MILLIS)
                    refresh()
                }
            }
        } else if (!full) {
            polling?.cancel()
            polling = null
        }
    }

    fun refresh(readMail: Boolean = false) {
        refreshedAt = clock.now().epochSeconds
        viewModelScope.launch { repository.refresh(readMail) }
    }

    private fun attempt(after: () -> Unit = {}, call: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            call().onSuccess { after() }.onFailure { failure ->
                // What the relay said, when it said anything; otherwise, that it couldn't be reached.
                _uiState.update { it.copy(notice = (failure as? LocalizedException)?.text ?: UiText.of(Res.string.packages_error_unreachable)) }
            }
        }
    }

    fun change(id: String, change: ParcelChange) = attempt { repository.change(id, change) }

    fun forget(id: String) = attempt { repository.forget(id) }

    fun add(tracking: String, nickname: String?, owner: String?, surprise: Boolean) =
        attempt(after = { _uiState.update { it.copy(added = true) } }) { repository.add(tracking, nickname, owner, surprise) }

    fun addSeen() = _uiState.update { it.copy(added = false) }

    fun setPhoneOwner(person: String?) = attempt { repository.setPhoneOwner(person) }

    fun setNotify(kind: String, on: Boolean) = attempt { repository.setNotify(kind, on) }

    fun setHideNew(person: String, on: Boolean) = attempt { repository.setHideNew(person, on) }

    fun noticeSeen() = _uiState.update { it.copy(notice = null) }

    /** The address of a kept picture, or the landing right now. */
    suspend fun pictureUrl(parcelId: String?, name: String): String? = repository.pictureUrl(parcelId, name)

    private companion object {
        const val FULL_REFRESH_SECONDS = 20L
        const val DRAWER_REFRESH_SECONDS = 300L
        const val POLL_MILLIS = 60_000L
    }
}
