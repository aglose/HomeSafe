package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.assistant.AssistantTool
import com.meticulouscreations.homesafe.assistant.current
import com.meticulouscreations.homesafe.assistant.inputOf
import com.meticulouscreations.homesafe.assistant.stringInput
import com.meticulouscreations.homesafe.assistant.stringProperty
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.WeatherPlacesRepository
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import com.meticulouscreations.homesafe.weather.domain.WeatherRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.assistant_tool_weather
import kotlinx.serialization.json.JsonObject

/**
 * The weather app's forecast for the assistant: the places saved there, or one asked for by name.
 * A report is eleven days of hours, so it gives now, the alerts, the next day by the hour and the
 * days ahead, which is what a question about the weather is about.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class WeatherAssistantTool(private val weather: WeatherRepository, private val places: WeatherPlacesRepository) : AssistantTool {
    override val name = "weather"
    override val description = "The forecast from the Weather app: current conditions, any weather alerts, the next 24 hours by the " +
        "hour and the next ten days, with air quality. Temperatures are Celsius, times Unix seconds. Covers the places saved in the " +
        "Weather app (the first is usually home), or any place by name."
    override val inputSchema = inputOf("place" to stringProperty("A place's name. Leave out for the places saved in the Weather app."))
    override val activity = Res.string.assistant_tool_weather

    override suspend fun run(input: JsonObject): String {
        val saved = places.observe().current().orEmpty()
        val asked = input.stringInput("place")
        val chosen = when {
            asked == null -> saved.take(MAX_PLACES)

            else -> saved.filter { it.name.contains(asked, ignoreCase = true) }.takeIf { it.isNotEmpty() }
                ?: weather.searchPlaces(asked).getOrNull().orEmpty().take(1)
        }
        if (chosen.isEmpty()) return if (asked == null) "No places are saved in the Weather app." else "Couldn't find a place called $asked."
        return chosen.map { describe(it) }.joinToString("\n\n")
    }

    private suspend fun describe(place: Place): String {
        val report = weather.report(place).getOrNull() ?: weather.lastReport(place)
            ?: return "${place.name}: no forecast available."
        return "${place.name}${place.region.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""}:\n" + summary(report)
    }

    private fun summary(report: WeatherReport): String = buildString {
        appendLine("Fetched at ${report.fetchedAtEpochSeconds}, UTC offset ${report.utcOffsetSeconds} s (${report.timeZoneId})")
        appendLine("Now: ${report.current}")
        report.air?.let { appendLine("Air: $it") }
        if (report.alerts.isNotEmpty()) appendLine("Alerts: ${report.alerts}")
        val now = report.current.epochSeconds
        appendLine("Next 24 hours:")
        report.hourly.filter { it.epochSeconds >= now - HOUR_SECONDS }.take(24).forEach { appendLine("- $it") }
        appendLine("Days:")
        report.daily.filter { it.epochSeconds >= now - DAY_SECONDS }.take(10).forEach { appendLine("- $it") }
    }

    private companion object {
        const val MAX_PLACES = 3
        const val HOUR_SECONDS = 3_600L
        const val DAY_SECONDS = 86_400L
    }
}
