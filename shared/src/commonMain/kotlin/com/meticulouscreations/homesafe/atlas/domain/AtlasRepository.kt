package com.meticulouscreations.homesafe.atlas.domain

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import kotlinx.coroutines.flow.StateFlow

/** Why the history couldn't be fetched, as far as the app can tell the cases apart. */
enum class AtlasProblem {
    /** Not signed in to a server, so there is nowhere to fetch it from. */
    SIGNED_OUT,

    /** The server isn't answering (Tailscale down, the box off). */
    UNREACHABLE,

    /** This account isn't one the relay shows the history to. */
    NOT_ALLOWED,

    /** The relay on the box is older than this app and has no history to give. */
    RELAY_OUTDATED,
    OTHER,
}

/** The history couldn't be fetched, for [problem]. [text] is what to tell the person. */
class AtlasUnavailableException(val problem: AtlasProblem, text: UiText, technical: String? = null) : LocalizedException(text, technical)

/** A step the box still needs, as the relay names it (`relay/relay.py`, "timeline"). */
enum class AtlasSetupProblem {
    /** The Drive (or Places) API isn't switched on for the key's Cloud project. */
    API_DISABLED,

    /** The Drive folder isn't shared with the relay's service account. */
    NOT_SHARED,

    /** The folder is shared and has no export in it yet. */
    EMPTY,

    /** The relay has no service-account key. */
    NO_KEY,

    /** The export was found and couldn't be read. */
    FAILED,
    OTHER,
    ;

    companion object {
        fun of(code: String?): AtlasSetupProblem? = when (code) {
            null -> null
            "api_disabled" -> API_DISABLED
            "not_shared", "not_found" -> NOT_SHARED
            "empty" -> EMPTY
            "no_key" -> NO_KEY
            "failed" -> FAILED
            else -> OTHER
        }
    }
}

/** How the box's side of it is going: the last export it read, the Drive folder it looks in, and the place names. */
@Immutable
data class AtlasServerStatus(
    val driveConfigured: Boolean = false,
    val driveProblem: AtlasSetupProblem? = null,
    /** The relay's own words for [driveProblem], shown as written. */
    val driveMessage: String? = null,
    /** Who the Drive folder is to be shared with. */
    val serviceAccount: String? = null,
    val activationUrl: String? = null,
    val checkedAtEpochSeconds: Long? = null,
    val nextCheckAtEpochSeconds: Long? = null,
    val fileName: String? = null,
    val fileReadAtEpochSeconds: Long? = null,
    val namingConfigured: Boolean = false,
    val namingProblem: AtlasSetupProblem? = null,
    val namingMessage: String? = null,
    val placesTotal: Int = 0,
    val placesNamed: Int = 0,
    val askedThisMonth: Int = 0,
    val monthlyCap: Int = 0,
    /** The relay is reading an export right now. */
    val reading: Boolean = false,
)

/** Where this device's copy stands against the box's. */
@Immutable
data class AtlasSyncState(
    /** A fetch is under way. */
    val syncing: Boolean = false,
    /** While years are being fetched: how many are in, of how many. */
    val yearsDone: Int = 0,
    val yearsTotal: Int = 0,
    val problem: AtlasProblem? = null,
    val message: UiText? = null,
    val server: AtlasServerStatus? = null,
    /** When the box was last heard from; null if never. */
    val checkedAtEpochSeconds: Long? = null,
)

/**
 * The location history: fetched from the relay a year at a time, kept on the device, and read
 * from there. Opening it never waits on the network: [history] is whatever the device holds,
 * and [refresh] brings it up to date behind that.
 */
interface AtlasRepository {
    /** What the device holds; null until it has been read ([load]). */
    val history: StateFlow<AtlasHistory?>

    val sync: StateFlow<AtlasSyncState>

    /** Reads what the device holds, once. */
    suspend fun load()

    /**
     * Asks the relay what it has and fetches what this device doesn't. With [askDrive] the relay
     * also looks in the Drive folder now instead of at its next check.
     */
    suspend fun refresh(askDrive: Boolean = false)

    /** Every path, decoded and ready to draw. Millions of points: read once and kept until the history changes. */
    suspend fun tracks(): List<Track>
}
