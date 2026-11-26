package com.nuvio.app.core.ui

import coil3.disk.DiskCache
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer

/**
 * The player's loading screen shows artwork the HUD fetches by URL through WebKit's own network
 * stack — a re-download of bytes the artwork pipeline already has on disk. The resolver answers
 * with a file:// source for those bytes when they exist and the URL unchanged otherwise, so a
 * hit loads in milliseconds and a miss behaves exactly as before. Reverting the disk-cache
 * lookup inside [openingOverlayCachedSource] fails the first test.
 */
class OpeningOverlayArtworkSourceTest {

    private val fs = FileSystem.SYSTEM

    private fun diskCache(): DiskCache {
        val dir = Files.createTempDirectory("nuvio-opening-source-test")
        return DiskCache.Builder()
            .directory(dir.toOkioPath())
            .maxSizeBytes(64L * 1024 * 1024)
            .build()
    }

    private fun store(cache: DiskCache, key: String, bytes: ByteArray) {
        val editor = requireNotNull(cache.openEditor(key)) { "editor for $key" }
        fs.write(editor.data) { write(bytes) }
        editor.commitAndOpenSnapshot()?.close()
    }

    @Test
    fun `cached url resolves to a local file source serving the cached bytes`() {
        val cache = diskCache()
        val bytes = "jpeg-bytes".encodeToByteArray()
        store(cache, "https://image.tmdb.org/t/p/original/backdrop.jpg", bytes)
        val resolved = openingOverlayCachedSource(
            "https://image.tmdb.org/t/p/original/backdrop.jpg",
            cache,
        )
        assertTrue(resolved != null && resolved.startsWith("file:"), "resolved=$resolved")
        val served = java.net.URI(resolved).toURL().openConnection().getInputStream().use {
            it.readBytes()
        }
        assertEquals(bytes.decodeToString(), served.decodeToString(), "the file source must serve the cached bytes")
        cache.shutdown()
    }

    @Test
    fun `uncached url passes through unchanged`() {
        val url = "https://image.tmdb.org/t/p/original/never-seen.jpg"
        assertEquals(url, openingOverlayCachedSource(url, diskCache()))
    }

    @Test
    fun `blank and null urls pass through unchanged`() {
        assertEquals(null, openingOverlayCachedSource(null, diskCache()))
        assertEquals("", openingOverlayCachedSource("", diskCache()))
    }
}
