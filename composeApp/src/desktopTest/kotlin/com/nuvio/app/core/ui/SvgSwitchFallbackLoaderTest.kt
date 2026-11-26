package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.toBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * TMDB title logos exported from Adobe Illustrator wrap their artwork in a <switch>: an empty
 * <foreignObject requiredExtensions="...AdobeIllustrator..."> first, then the real <g> fallback.
 * The stock SVG decoder renders that shape as a fully transparent bitmap — no decode error, so
 * no title fallback ever ran and Good Will Hunting's hero showed neither logo nor title. The
 * [SvgSwitchFallbackDecoder] unwraps the switch and renders the fallback; this guards the whole
 * path end to end through the real artwork loader, against the real TMDB logo and a synthetic
 * minimal fixture (so the guard survives upstream logo churn and pins the unwrap rules).
 */
class SvgSwitchFallbackLoaderTest {

    private fun loader(): ImageLoader = ImageLoader.Builder(PlatformContext.INSTANCE)
        .components {
            addDesktopArtworkComponents()
        }
        .build()

    private fun opaquePixels(bitmap: coil3.Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        val alpha: ByteArray = bitmap.readPixels(
            org.jetbrains.skia.ImageInfo(width, height, org.jetbrains.skia.ColorType.ALPHA_8, org.jetbrains.skia.ColorAlphaType.PREMUL),
            width,
            0,
            0,
        ) ?: return -1
        var count = 0
        for (a in alpha) if ((a.toInt() and 0xFF) > 8) count++
        return count
    }

    @Test
    fun `illustrator switch logo renders its fallback instead of a transparent frame`() = runBlocking {
        val url = "https://image.tmdb.org/t/p/original/3rY7Kng9sjgoWodYRYB7EBQYq4E.svg"
        val result = loader().execute(
            ImageRequest.Builder(PlatformContext.INSTANCE).data(url).size(Size.ORIGINAL).build(),
        )
        assertTrue(result is SuccessResult, result.toString())
        val bitmap = result.image.toBitmap()
        assertTrue(bitmap.width > 0 && bitmap.height > 0, "decoded ${bitmap.width}x${bitmap.height}")
        val opaque = opaquePixels(bitmap)
        assertTrue(opaque > 0, "Illustrator switch SVG decoded to a fully transparent frame ($opaque opaque pixels)")
    }

    @Test
    fun `unwrap rules keep the fallback group and drop the extension test`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 4" width="10" height="4">
<switch transform="translate(-1,-2)"><foreignObject requiredExtensions="http://ns.adobe.com/AdobeIllustrator/10.0/" x="0" y="0" width="1" height="1"></foreignObject><g fill="#ffffff"><rect x="2" y="1" width="4" height="2"/></g></switch></svg>"""
        assertTrue(isSwitchFallbackSvg(svg))
        val unwrapped = unwrapIllustratorSwitch(svg)
        assertTrue(!unwrapped.contains("<switch"), unwrapped)
        assertTrue(!unwrapped.contains("foreignObject"), unwrapped)
        assertTrue(unwrapped.contains("transform=\"translate(-1,-2)\""), "the switch's transform must survive: $unwrapped")
        assertTrue(unwrapped.contains("<rect"), "fallback artwork must survive the unwrap: $unwrapped")
        val bitmap = renderUnwrappedSwitchSvg(unwrapped)!!
        // The rect sits at (2..6, 1..3) and the kept transform shifts it by (-1,-2), so canvas
        // point (3,0) lands inside the drawn rect.
        assertEquals(0xFFFFFFFF.toInt(), bitmap.getColor(3, 0), "fallback rect must be drawn")
    }

    @Test
    fun `svg without a switch is left to the stock decoder`() {
        assertTrue(!isSwitchFallbackSvg("""<svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0"/></svg>"""))
        assertTrue(!isSwitchFallbackSvg("""<svg xmlns="http://www.w3.org/2000/svg"><switch><g/></switch></svg>"""))
    }
}
