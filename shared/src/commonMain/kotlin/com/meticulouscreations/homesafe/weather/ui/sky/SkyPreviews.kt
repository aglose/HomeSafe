package com.meticulouscreations.homesafe.weather.ui.sky

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.meticulouscreations.homesafe.weather.domain.WeatherKind

// Portland, Oregon, through one October day: 1 760 000 000 is 2025-10-09 08:53 UTC, 01:53 there.
private const val LAT = 45.52
private const val LON = -122.68
private const val MIDNIGHT = 1_759_993_200L

private fun scene(kind: WeatherKind, hour: Double, cover: Int? = null, windKmh: Double = 12.0, visibilityM: Double? = null): SkyScene = SkyScene.of(
    kind = kind,
    cloudCoverPercent = cover,
    windKmh = windKmh,
    windDirectionDeg = 250,
    visibilityM = visibilityM,
    latitude = LAT,
    longitude = LON,
    epochSeconds = MIDNIGHT + (hour * 3600).toLong(),
)

@Preview(widthDp = 220, heightDp = 480)
@Composable
private fun SkyClearNoonPreview() {
    WeatherSky(scene(WeatherKind.CLEAR, 13.0, cover = 3), Modifier.fillMaxSize())
}

@Preview(widthDp = 220, heightDp = 480)
@Composable
private fun SkyPartlyCloudyPreview() {
    WeatherSky(scene(WeatherKind.PARTLY_CLOUDY, 15.0, cover = 45), Modifier.fillMaxSize())
}

@Preview(widthDp = 220, heightDp = 480)
@Composable
private fun SkyGoldenHourPreview() {
    WeatherSky(scene(WeatherKind.PARTLY_CLOUDY, 18.2, cover = 40), Modifier.fillMaxSize())
}

@Preview(widthDp = 220, heightDp = 480)
@Composable
private fun SkyDuskPreview() {
    WeatherSky(scene(WeatherKind.MOSTLY_CLEAR, 18.95, cover = 20), Modifier.fillMaxSize())
}

@Preview(widthDp = 220, heightDp = 480)
@Composable
private fun SkyClearNightPreview() {
    WeatherSky(scene(WeatherKind.CLEAR, 23.0, cover = 4).copy(moonX = 0.7f, moonY = 0.2f, moonAltitude = 40f, moonCycle = 0.36f), Modifier.fillMaxSize())
}

@Preview(widthDp = 220, heightDp = 480)
@Composable
private fun SkyOvercastPreview() {
    WeatherSky(scene(WeatherKind.OVERCAST, 11.0, cover = 100), Modifier.fillMaxSize())
}

@Preview(widthDp = 220, heightDp = 480)
@Composable
private fun SkyRainPreview() {
    WeatherSky(scene(WeatherKind.RAIN, 14.0, cover = 100, windKmh = 24.0), Modifier.fillMaxSize())
}

@Preview(widthDp = 220, heightDp = 480)
@Composable
private fun SkyHeavyRainPreview() {
    WeatherSky(scene(WeatherKind.HEAVY_RAIN, 16.0, cover = 100, windKmh = 38.0), Modifier.fillMaxSize())
}

@Preview(widthDp = 220, heightDp = 480)
@Composable
private fun SkyThunderstormPreview() {
    WeatherSky(scene(WeatherKind.THUNDERSTORM, 20.0, cover = 100, windKmh = 40.0), Modifier.fillMaxSize(), freeze = SkyFreeze(time = 21f, boltAge = 0.03f))
}

@Preview(widthDp = 220, heightDp = 480)
@Composable
private fun SkySnowPreview() {
    WeatherSky(scene(WeatherKind.SNOW, 10.0, cover = 100, windKmh = 8.0), Modifier.fillMaxSize())
}

@Preview(widthDp = 220, heightDp = 480)
@Composable
private fun SkyFogPreview() {
    WeatherSky(scene(WeatherKind.FOG, 8.5, cover = 70, visibilityM = 400.0), Modifier.fillMaxSize())
}
