package com.meticulouscreations.homesafe.ui.components

import coil3.PlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest

/**
 * The image request for a detection's thumbnail at [url], cached by the detection rather than by
 * its address. The address names the route (LAN or Tailscale), so caching by it fetched every
 * thumbnail again on each switch; and it never changes for a detection, so a frame fetched while
 * the detection was still going on (Frigate's early guess) stuck for good after Frigate had kept
 * its best one.
 *
 * So while [inProgress] the frame is held in memory under a key of its own and kept off disk,
 * and once the detection has ended it is fetched afresh under the detection's own key, with the
 * early frame standing in until it arrives.
 */
internal fun eventThumbnailRequest(context: PlatformContext, url: String, eventId: String, inProgress: Boolean): ImageRequest {
    val endedKey = "event-thumb:$eventId"
    val openKey = "$endedKey:open"
    val builder = ImageRequest.Builder(context).data(url)
    return if (inProgress) {
        builder
            .memoryCacheKey(openKey)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    } else {
        builder
            .memoryCacheKey(endedKey)
            .diskCacheKey(endedKey)
            .placeholderMemoryCacheKey(openKey)
            .build()
    }
}
