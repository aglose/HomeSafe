package com.meticulouscreations.homesafe.maps

import androidx.compose.runtime.Immutable
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.maps_provider_google
import homesafe.shared.generated.resources.maps_provider_google_blurb
import homesafe.shared.generated.resources.maps_provider_osm
import homesafe.shared.generated.resources.maps_provider_osm_blurb
import org.jetbrains.compose.resources.StringResource

/** Whose map the app's maps (the weather radar, the atlas, a parked car's street) are drawn on. Picked in Settings. */
enum class MapProvider(val label: StringResource, val blurb: StringResource) {
    OPEN_STREET_MAP(Res.string.maps_provider_osm, Res.string.maps_provider_osm_blurb),
    GOOGLE(Res.string.maps_provider_google, Res.string.maps_provider_google_blurb),
    ;

    companion object {
        val DEFAULT = OPEN_STREET_MAP

        fun fromName(name: String?): MapProvider = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Where a map's base tiles come from at the moment: web-mercator tiles by [url], `z`/`x`/`y` as
 * every slippy map numbers them, so the maps draw either the same way. Google's come under a
 * session ([Google.session]) that runs out, so a [BaseMap] is a moment's answer, not a setting.
 */
@Immutable
sealed interface BaseMap {
    val provider: MapProvider

    /**
     * Whether the tiles may be saved on the device and drawn from there next time.
     * OpenStreetMap asks for that; Google's terms forbid it (only HTTP's own caching).
     */
    val keep: Boolean

    fun url(z: Int, x: Int, y: Int): String

    /** OpenStreetMap's standard tiles: free to use with attribution and a cache. */
    data object OpenStreetMap : BaseMap {
        override val provider = MapProvider.OPEN_STREET_MAP
        override val keep = true

        override fun url(z: Int, x: Int, y: Int): String = "https://tile.openstreetmap.org/$z/$x/$y.png"
    }

    /** Google's roadmap from the Map Tiles API, under [session] and paid for by the person's own [key]. */
    class Google(val session: String, internal val key: String) : BaseMap {
        override val provider = MapProvider.GOOGLE
        override val keep = false

        override fun url(z: Int, x: Int, y: Int): String = "${GoogleMapTiles.TILES}$z/$x/$y?session=$session&key=$key"

        override fun equals(other: Any?): Boolean = other is Google && other.session == session && other.key == key

        override fun hashCode(): Int = 31 * session.hashCode() + key.hashCode()

        // Never the key: this ends up in logs and crash reports.
        override fun toString(): String = "Google(session=${session.take(8)}…)"
    }
}
