package com.meticulouscreations.homesafe.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.net.toUri
import com.meticulouscreations.homesafe.appicon.appLaunchIntent
import com.meticulouscreations.homesafe.data.BackgroundGraph
import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.model.doorGlance
import com.meticulouscreations.homesafe.navigation.DoorDeepLink
import com.meticulouscreations.homesafe.shared.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The "Doors" tile in Quick Settings: lit while a door is open, saying which ("Back door open", or
 * "2 open"), "All closed" otherwise. Asked afresh each time the shade comes down, from the relay
 * as this install ([com.meticulouscreations.homesafe.data.BackgroundDoors]), since the app may not
 * be running and has no session if it isn't. Greyed out, "Can't reach the server", when the relay
 * didn't answer in [LOOKUP_TIMEOUT_MS] or can't be asked at all (never signed in, or no device
 * secret yet). A tap opens the Doors page, unlocking first.
 */
class DoorsTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also {
            it.launch {
                val doors = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { withContext(Dispatchers.IO) { lookUp() } }
                ensureActive()
                show(doors)
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { openDoors() } else openDoors()
    }

    /** The doors, or null for anything that went wrong: the tile only ever says it couldn't reach the server. */
    private suspend fun lookUp(): List<Door>? = try {
        BackgroundGraph.get(applicationContext).backgroundDoors.doors()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun show(doors: List<Door>?) {
        val tile = qsTile ?: return
        // Open (and heard from) first, in the relay's order, as Home's glance row has them.
        val open = doors?.let { doorGlance(it).shown.filter { door -> door.open == true && door.online != false } }.orEmpty()
        tile.label = getString(R.string.tile_doors_label)
        tile.state = when {
            doors == null -> Tile.STATE_UNAVAILABLE
            open.isNotEmpty() -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when {
            doors == null -> getString(R.string.tile_doors_unreachable)

            open.size == 1 -> getString(R.string.tile_doors_one_open, open.single().name)

            open.isNotEmpty() -> resources.getQuantityString(R.plurals.tile_doors_open, open.size, open.size)

            doors.isNotEmpty() && doors.all { it.open == false && it.online != false } -> getString(R.string.tile_doors_all_closed)

            // No sensors yet, or one offline or not yet read: nothing the tile can say in a word.
            else -> null
        }
        tile.updateTile()
    }

    /** The app on the Doors page, through MainActivity itself (not the icon alias, which another icon may have disabled). */
    private fun openDoors() {
        val launch = appLaunchIntent() ?: return
        val intent = Intent(Intent.ACTION_VIEW, DoorDeepLink().toUri().toUri())
            .setComponent(launch.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        /** Long enough for the remote address after the local one failed; the shade isn't left waiting on it. */
        const val LOOKUP_TIMEOUT_MS = 8_000L
    }
}
