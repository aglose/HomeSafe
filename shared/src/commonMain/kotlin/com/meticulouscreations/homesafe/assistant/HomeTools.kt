package com.meticulouscreations.homesafe.assistant

import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.repository.CameraRepository
import com.meticulouscreations.homesafe.domain.repository.DoorRepository
import com.meticulouscreations.homesafe.domain.repository.MomentsRepository
import com.meticulouscreations.homesafe.domain.repository.PresenceRepository
import com.meticulouscreations.homesafe.domain.repository.ServerStatusRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.assistant_tool_cameras
import homesafe.shared.generated.resources.assistant_tool_doors
import homesafe.shared.generated.resources.assistant_tool_moments
import homesafe.shared.generated.resources.assistant_tool_presence
import homesafe.shared.generated.resources.assistant_tool_server
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.json.JsonObject

// What the assistant can read from the house itself: the cameras, what they saw, the doors, who
// is home, and the server. Each is a tool Claude calls when a question needs it (see AssistantTool).

@Inject
@ContributesIntoSet(AppScope::class)
class CamerasTool(private val cameras: CameraRepository) : AssistantTool {
    override val name = "list_cameras"
    override val description = "The house's cameras: each one's id (as the other tools take it), its name, whether it is enabled, " +
        "whether it is outdoor or indoor, and whether it is a Frigate or a Nest camera."
    override val inputSchema = noInput
    override val activity = Res.string.assistant_tool_cameras

    override suspend fun run(input: JsonObject): String {
        val list = cameras.observeCameras().current() ?: return NOT_CONNECTED
        return listing(list, limit = 100) { "${it.name} (\"${it.displayName}\"): ${if (it.enabled) "enabled" else "disabled"}, ${it.placement}, ${it.source}" }
    }
}

@Inject
@ContributesIntoSet(AppScope::class)
class MomentsTool(private val moments: MomentsRepository) : AssistantTool {
    override val name = "recent_moments"
    override val description = "What the cameras detected recently, newest first: who or what (Frigate's label, and the familiar " +
        "face or known car's name when recognised), which camera, the zones it was in, when it started and ended (Unix seconds; " +
        "no end means still in view), and how many times it was seen. Give a camera id to look at one camera, further back."
    override val inputSchema = inputOf(
        "camera" to stringProperty("A camera id from list_cameras, to see only that camera's moments."),
        "limit" to integerProperty("How many moments, newest first. Default 40, at most 200."),
    )
    override val activity = Res.string.assistant_tool_moments

    override suspend fun run(input: JsonObject): String {
        val limit = (input.intInput("limit") ?: 40).coerceIn(1, 200)
        val camera = input.stringInput("camera")
        val found = if (camera == null) {
            moments.observeMoments().current()
        } else {
            moments.observeRecentMoments(camera, limit, lookbackSeconds = WEEK_SECONDS).current(timeoutMillis = 15_000)
        } ?: return NOT_CONNECTED
        return listing(found.sortedByDescending { it.startEpochSeconds }, limit, ::describe)
    }

    private fun describe(moment: MomentEvent): String = buildString {
        append(moment.label)
        moment.subLabel?.let { append(" \"").append(it).append('"') }
        append(" on ").append(moment.cameraName)
        if (moment.zones.isNotEmpty()) append(" in ").append(moment.zones.joinToString())
        append(", from ").append(moment.startEpochSeconds.toLong())
        append(moment.endEpochSeconds?.let { " to ${it.toLong()}" } ?: " (still in view)")
        if (moment.sightings > 1) append(", seen ").append(moment.sightings).append(" times")
        moment.topScore?.let { append(", score ").append((it * 100).toInt()).append('%') }
        append(" [id ").append(moment.id).append(']')
    }

    private companion object {
        const val WEEK_SECONDS = 7 * 86_400.0
    }
}

@Inject
@ContributesIntoSet(AppScope::class)
class DoorsTool(private val doors: DoorRepository) : AssistantTool {
    override val name = "doors"
    override val description = "The door sensors: each door's name, whether it is open (null means unknown), when it last changed " +
        "(Unix milliseconds), its battery and signal, and the latest openings and closings."
    override val inputSchema = noInput
    override val activity = Res.string.assistant_tool_doors

    override suspend fun run(input: JsonObject): String {
        doors.refresh()
        val snapshot = doors.doors.value
        if (!snapshot.supported) return "This server has no door sensors."
        val names = snapshot.doors.associate { it.id to it.name }
        return "Doors:\n" + listing(snapshot.doors, limit = 50) { door ->
            "${door.name}: open=${door.open}, changed at ${door.changedAtMillis}, battery ${door.battery?.let { "$it%" }}${if (door.needsBattery) " (low)" else ""}, online=${door.online}"
        } + "\n\nRecent events, newest first:\n" + listing(snapshot.events.sortedByDescending { it.atMillis }, limit = 40) { event ->
            "${names[event.doorId] ?: event.doorId} ${if (event.open) "opened" else "closed"} at ${event.atMillis}"
        }
    }
}

@Inject
@ContributesIntoSet(AppScope::class)
class PresenceTool(private val presence: PresenceRepository) : AssistantTool {
    override val name = "who_is_home"
    override val description = "Away mode: the household's tracked phones, whether each is home or away and when it last changed " +
        "(Unix seconds), and whether everyone is away (when every person a camera sees is alerted on loudly)."
    override val inputSchema = noInput
    override val activity = Res.string.assistant_tool_presence

    override suspend fun run(input: JsonObject): String {
        presence.refresh()
        val household = presence.presence.value
        return "Everyone away: ${household.everyoneAway}\n" + listing(household.devices, limit = 10) { device ->
            "${device.name} (${device.platform})${if (device.isThisDevice) " — this phone" else ""}: ${if (device.away) "away" else "home"}, " +
                "updated ${device.updatedEpochSeconds?.toLong()}, last seen ${device.lastSeenEpochSeconds?.toLong()}"
        }
    }
}

@Inject
@ContributesIntoSet(AppScope::class)
class ServerTool(private val server: ServerStatusRepository) : AssistantTool {
    override val name = "server_status"
    override val description = "The Frigate server: its version and whether an update is out, uptime, CPU and memory, recordings " +
        "storage, the detector and GPUs, retention, which recognition features are on, and each camera's pipeline (fps, detection)."
    override val inputSchema = noInput
    override val activity = Res.string.assistant_tool_server

    override suspend fun run(input: JsonObject): String = server.observeOverview().filterNotNull().current(timeoutMillis = 10_000)?.toString() ?: NOT_CONNECTED
}

private const val NOT_CONNECTED = "Not available: the app isn't connected to the home server right now."
