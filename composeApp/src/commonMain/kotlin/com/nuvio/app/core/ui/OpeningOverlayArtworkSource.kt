package com.nuvio.app.core.ui

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest

/**
 * fork:opening-artwork-from-disk-cache - the player's loading screen hands the HUD plain URLs for
 * the backdrop and logo, and the HUD fetches them through its own network stack: even when Home
 * or Details has just downloaded and decoded the very same artwork, the loading screen
 * re-downloaded it over the network and the screen sat black with a spinner for seconds.
 *
 * [openingOverlayCachedSource] answers with a `file://` source for the bytes the artwork
 * pipeline already holds on disk whenever it has them, so WebKit loads the image and logo from
 * local disk in milliseconds — ideally before the first video frame is due. Anything not on
 * disk, or any lookup failure, returns the URL unchanged: behaviour only ever gets faster,
 * never different.
 */
internal expect fun openingOverlayCachedSource(url: String?): String?

/**
 * fork:opening-artwork-prefetch - the player's loading screen only shows artwork the artwork
 * pipeline has already stored. Firing the backdrop and logo requests the moment playback is
 * clicked (the stream picker takes a second or two to resolve) usually completes the download
 * before the player even attaches, so a first-time title opens with its artwork too. Fire and
 * forget: results are only ever a warmed cache.
 */
internal fun prefetchOpeningArtwork(logo: String?, poster: String?, background: String?) {
    val urls = listOfNotNull(background, poster, logo)
        .filter { it.isNotBlank() }
        .distinct()
    if (urls.isEmpty()) return
    val context = PlatformContext.INSTANCE
    val loader = runCatching { SingletonImageLoader.get(context) }.getOrNull() ?: return
    urls.forEach { url ->
        runCatching {
            loader.enqueue(ImageRequest.Builder(context).data(url).build())
        }
    }
}
