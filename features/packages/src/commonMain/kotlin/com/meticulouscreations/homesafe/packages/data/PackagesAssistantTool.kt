package com.meticulouscreations.homesafe.packages.data

import com.meticulouscreations.homesafe.assistant.AssistantTool
import com.meticulouscreations.homesafe.assistant.listing
import com.meticulouscreations.homesafe.assistant.noInput
import com.meticulouscreations.homesafe.packages.domain.PackagesRepository
import com.meticulouscreations.homesafe.packages.domain.Parcel
import com.meticulouscreations.homesafe.packages.domain.ParcelDays
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.assistant_tool_packages
import kotlinx.serialization.json.JsonObject

/**
 * The packages app's list for the assistant: what's on its way, what has come, what the door
 * camera made of each. It goes through the relay as the app's own list does, so a surprise kept
 * from this phone is kept from what the assistant is told as well.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class PackagesAssistantTool(private val packages: PackagesRepository) : AssistantTool {
    override val name = "packages"
    override val description = "The household's packages from the Packages app: what is on its way and when it is promised, what has been delivered in the " +
        "last weeks and what the front door camera saw of each (confirmed: a parcel seen appearing; seen: someone came to the door at the right time; " +
        "not_seen: the camera saw nobody), whether it is still on the porch, and any problem or return. Days are YYYY-MM-DD, times are Unix seconds."
    override val inputSchema = noInput
    override val activity = Res.string.assistant_tool_packages

    override suspend fun run(input: JsonObject): String {
        packages.refresh()
        val snapshot = packages.snapshot.value ?: return "The package list isn't available right now: the home server isn't answering, or the app isn't signed in."
        // Gone quiet or cancelled: kept for the record, and not on its way.
        val coming = snapshot.parcels.filter { !it.delivered && !it.archived }
        val recent = snapshot.parcels.filter { it.delivered && (it.deliveredAt ?: 0) > snapshot.nowEpochSeconds - RECENT_SECONDS }
        return buildString {
            // An old list read as today's would be worse than none: it says "on its way" of what may have come.
            if (packages.sync.value.problem != null) {
                appendLine("The home server isn't answering right now, so this is the list as last heard, from Unix second ${snapshot.nowEpochSeconds}, and may be out of date. Say so.")
            }
            appendLine("Today is ${ParcelDays.format(snapshot.today)}. Nobody home at the last word: ${snapshot.away}.")
            appendLine("On the way:")
            appendLine(listing(coming, limit = 40, describe = ::describe))
            appendLine("Delivered in the last three weeks:")
            append(listing(recent, limit = 40, describe = ::describe))
        }
    }

    private fun describe(parcel: Parcel): String {
        if (parcel.masked) return "A surprise for someone in the household (kept from this phone), due ${parcel.expectedToDay?.let(ParcelDays::format) ?: "unknown"}, ${parcel.stage}"
        return listOfNotNull(
            parcel.nickname ?: parcel.title ?: "A parcel",
            parcel.shop?.let { "from $it" },
            parcel.owner.takeIf { it.isNotBlank() }?.let { "for $it" },
            parcel.carrier.label.ifEmpty { parcel.carrierName },
            parcel.stage.name.lowercase(),
            parcel.expectedFromDay?.let { "due ${ParcelDays.format(it)}${parcel.expectedToDay?.takeIf { to -> to != it }?.let { to -> " to ${ParcelDays.format(to)}" }.orEmpty()}" },
            parcel.deliveredAt?.let { "delivered at $it" },
            parcel.verdict?.let { "camera: ${it.name.lowercase()}" },
            parcel.porch?.let { "porch: ${it.name.lowercase()}" },
            parcel.problem?.let { "problem: ${it.name.lowercase()}" },
            "late".takeIf { parcel.late },
            "needs a signature".takeIf { parcel.signature && !parcel.delivered },
            parcel.returnByDay?.let { "returnable until ${ParcelDays.format(it)}" },
            parcel.returnState.takeIf { it.name != "NONE" }?.let { "return: ${it.name.lowercase()}" },
        ).joinToString(", ")
    }

    private companion object {
        const val RECENT_SECONDS = 21 * 86_400L
    }
}
