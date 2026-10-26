package com.meticulouscreations.homesafe.atlas.data

import com.meticulouscreations.homesafe.assistant.AssistantTool
import com.meticulouscreations.homesafe.assistant.inputOf
import com.meticulouscreations.homesafe.assistant.listing
import com.meticulouscreations.homesafe.assistant.stringInput
import com.meticulouscreations.homesafe.assistant.stringProperty
import com.meticulouscreations.homesafe.atlas.domain.AtlasAnalyzer
import com.meticulouscreations.homesafe.atlas.domain.AtlasHistory
import com.meticulouscreations.homesafe.atlas.domain.AtlasRepository
import com.meticulouscreations.homesafe.atlas.domain.CivilDate
import com.meticulouscreations.homesafe.atlas.domain.Place
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.assistant_tool_atlas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * The atlas app's location history for the assistant: one day's visits and journeys when asked
 * about a date, and otherwise what the Atlas app works out from all of it (where they lived, the
 * places that matter, the trips, the years). Read from the copy the phone keeps.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class AtlasAssistantTool(private val atlas: AtlasRepository) : AssistantTool {
    override val name = "location_history"
    override val description = "The Atlas app's location history (from Google Timeline). With a date: that day's visits (place, " +
        "arrival and departure in Unix seconds) and journeys (mode, distance). Without: the homes lived in and when, the most " +
        "visited places, the latest trips away, each year's totals, records and routine. Days are counted from 1970-01-01."
    override val inputSchema = inputOf("date" to stringProperty("A day to look at, as YYYY-MM-DD. Leave out for the overview."))
    override val activity = Res.string.assistant_tool_atlas

    override suspend fun run(input: JsonObject): String {
        if (atlas.history.value == null) atlas.load()
        val history = atlas.history.value?.takeUnless { it.isEmpty } ?: return "There is no location history on this phone yet."
        val date = input.stringInput("date")
        return if (date != null) day(history, date) else withContext(Dispatchers.Default) { overview(history) }
    }

    private fun day(history: AtlasHistory, date: String): String {
        val parts = date.split('-').mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return "Give the date as YYYY-MM-DD."
        val day = CivilDate.dayOf(parts[0], parts[1], parts[2])
        val visits = history.visits.filter { day in it.startDay..it.endDay }
        val moves = history.moves.filter { it.startDay == day }
        return "Visits on $date:\n" + listing(visits, limit = 60) { visit ->
            "${history.places[visit.placeKey]?.let(::describe) ?: visit.placeKey} (${visit.kind}) from ${visit.start} to ${visit.end}"
        } + "\nJourneys on $date:\n" + listing(moves, limit = 60) { move ->
            "${move.mode}, ${move.meters.toLong()} m, from ${move.start} to ${move.end}"
        }
    }

    private fun overview(history: AtlasHistory): String {
        val analysis = AtlasAnalyzer.analyze(history)
        return buildString {
            appendLine("History covers days ${history.firstDay} to ${history.lastDay}, ${history.visits.size} visits.")
            appendLine("Homes, oldest first:")
            appendLine(listing(analysis.eras, limit = 20) { "${describe(it.home)} from day ${it.startDay} to ${it.endDay}" })
            appendLine("Most visited places:")
            appendLine(listing(analysis.places, limit = 30) { "${describe(it.place)}: ${it.visits} visits on ${it.days} days, last on day ${it.lastDay}" })
            appendLine("Latest trips away, newest first:")
            appendLine(
                listing(analysis.trips.sortedByDescending { it.startDay }, limit = 20) { trip ->
                    "${describe(trip.mainPlace)}${trip.locality?.let { " (${listOfNotNull(it.city, it.region, it.country).joinToString()})" } ?: ""}: " +
                        "days ${trip.startDay}–${trip.endDay}, ${trip.farthestKm.toLong()} km from home${if (trip.flew) ", flew" else ""}"
                },
            )
            appendLine("Years:")
            appendLine(listing(analysis.years.sortedByDescending { it.year }, limit = 15))
            appendLine("Records: ${analysis.records}")
            appendLine("Countries: ${analysis.countries.size}, regions: ${analysis.regions.size}, cities: ${analysis.cities}")
            analysis.commute?.let { appendLine("Commute: $it") }
        }
    }

    private fun describe(place: Place): String = listOfNotNull(place.name, place.city ?: place.address).joinToString(", ").ifEmpty { place.key }
}
