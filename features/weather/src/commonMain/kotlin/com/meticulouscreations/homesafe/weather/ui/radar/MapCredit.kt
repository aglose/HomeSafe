package com.meticulouscreations.homesafe.weather.ui.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meticulouscreations.homesafe.maps.BaseMap
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import com.meticulouscreations.homesafe.weather.domain.MapCamera
import com.meticulouscreations.homesafe.weather.domain.WebMercator
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.maps_credit_google
import homesafe.shared.generated.resources.maps_provider_google
import homesafe.shared.generated.resources.weather_radar_credit_map
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor
import kotlin.math.pow

/**
 * Keeps [onCopyright] told the copyright line Google's terms want shown over the view in
 * [camera], asked of Google once the view has settled (and again each time it settles somewhere
 * else). Tells it null for any other map, which has no such line to fetch.
 */
@OptIn(FlowPreview::class)
suspend fun MapTileSource.followCopyright(map: BaseMap?, camera: () -> MapCamera, viewport: IntSize, tilePx: Float, onCopyright: (String?) -> Unit) {
    if (map !is BaseMap.Google || viewport.width <= 0 || viewport.height <= 0) {
        onCopyright(null)
        return
    }
    snapshotFlow {
        val at = camera()
        val count = 1 shl at.level
        // Quarter-view steps: the line names whose data is in view, which doesn't change with every pixel of a drag.
        Triple(at.level, floor(at.centerX * count * 4).toInt(), floor(at.centerY * count * 4).toInt())
    }
        .distinctUntilChanged()
        .debounce(COPYRIGHT_SETTLE_MILLIS)
        .collectLatest {
            val at = camera()
            val scale = 2.0.pow(at.zoom) * tilePx
            val halfWidth = viewport.width / 2.0 / scale
            val halfHeight = viewport.height / 2.0 / scale
            copyright(
                map,
                zoom = at.level,
                north = WebMercator.latitude((at.centerY - halfHeight).coerceAtLeast(0.0)),
                south = WebMercator.latitude((at.centerY + halfHeight).coerceAtMost(1.0)),
                east = wrapLongitude(WebMercator.longitude(at.centerX + halfWidth)),
                west = wrapLongitude(WebMercator.longitude(at.centerX - halfWidth)),
            )?.let(onCopyright)
        }
}

/** The words crediting [map]: OpenStreetMap's contributors, or Google Maps with Google's own [copyright] once it is known. */
@Composable
fun mapCredit(map: BaseMap?, copyright: String?): String = when (map) {
    is BaseMap.Google -> if (copyright != null) stringResource(Res.string.maps_credit_google, copyright) else stringResource(Res.string.maps_provider_google)
    else -> stringResource(Res.string.weather_radar_credit_map)
}

/** [mapCredit] in a small dark label, for a map with no line of its own to credit it in. */
@Composable
fun MapCreditLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = TextStyle(fontSize = 9.sp, lineHeight = 11.sp),
        color = Color.White.copy(alpha = 0.85f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/** A longitude brought back into -180..180, which the viewport request insists on. */
private fun wrapLongitude(longitude: Double): Double = (longitude + 180.0).mod(360.0) - 180.0

private const val COPYRIGHT_SETTLE_MILLIS = 600L
