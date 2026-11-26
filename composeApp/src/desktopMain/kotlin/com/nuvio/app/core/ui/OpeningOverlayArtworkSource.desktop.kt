package com.nuvio.app.core.ui

import coil3.disk.DiskCache
import java.io.File

internal actual fun openingOverlayCachedSource(url: String?): String? {
    if (url.isNullOrBlank()) return url
    // ImageDiskCacheWarmup.cache throws when the disk cache was never installed (tests, early
    // launch); a miss is not an error, so every failure falls back to the URL.
    return runCatching { openingOverlayCachedSource(url, ImageDiskCacheWarmup.cache) }
        .getOrNull() ?: url
}

/** The lookup itself, against an injectable cache so tests can drive a real one. */
internal fun openingOverlayCachedSource(url: String?, diskCache: DiskCache?): String? {
    if (url.isNullOrBlank() || diskCache == null) return url
    return runCatching {
        diskCache.openSnapshot(url)?.use { snapshot ->
            val file = File(snapshot.data.toString())
            if (file.isFile && file.length() > 0L) file.toURI().toASCIIString() else null
        }
    }.getOrNull() ?: url
}
