package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import java.util.regex.Pattern
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.svg.SVGDOM

// TMDB serves title logos exported from Adobe Illustrator as an SVG whose artwork sits in a
// <switch>: an empty <foreignObject requiredExtensions="...AdobeIllustrator..."> first, then the
// real <g> fallback. Renderers without Illustrator must skip the foreignObject and draw the
// fallback, and browsers do — but the SVG parser behind the stock decoder renders the whole
// switch as nothing, so those logos decode "successfully" into a fully transparent bitmap: no
// error, no logo, and no title fallback because nothing failed (Good Will Hunting's hero drew
// neither logo nor title). This unwraps the switch, drops the foreignObject, and renders the
// fallback content directly through Skia.

private val switchOpen = Pattern.compile("<switch\\b([^>]*)>", Pattern.CASE_INSENSITIVE)
private val switchClose = Pattern.compile("</switch\\s*>", Pattern.CASE_INSENSITIVE)
private val foreignObject = Pattern.compile(
    "<foreignObject\\b[^>]*/>|<foreignObject\\b[^>]*>[\\s\\S]*?</foreignObject\\s*>",
    Pattern.CASE_INSENSITIVE,
)
private val hasSwitch = Pattern.compile("<switch\\b", Pattern.CASE_INSENSITIVE)
private val hasForeignObject = Pattern.compile("<foreignObject\\b", Pattern.CASE_INSENSITIVE)
private val hasEmbeddedImage = Regex("<image\\b", RegexOption.IGNORE_CASE)
private val rootTag = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE)
private val separators = Regex("[\\s,]+")

/** True for the Illustrator-export shape this decoder exists for: a switch with a
 *  foreignObject extension test and no embedded <image> (that one belongs to
 *  [SvgEmbeddedImageDecoder], which is offered first). */
internal fun isSwitchFallbackSvg(svg: String): Boolean =
    hasSwitch.matcher(svg).find() &&
        hasForeignObject.matcher(svg).find() &&
        !hasEmbeddedImage.containsMatchIn(svg)

internal fun unwrapIllustratorSwitch(svg: String): String =
    foreignObject.matcher(svg).replaceAll("")
        .let(switchOpen::matcher)
        // The switch's own attributes (its transform) map the artwork into the root's viewBox:
        // dropping them parks the fallback group outside the declared box and every renderer
        // clips it to nothing. Carry them over to the replacement <g>.
        .replaceAll("<g$1>")
        .let(switchClose::matcher)
        .replaceAll("</g>")

/** Renders [svg] through Skia at the size the root states, with nothing left for a parser to
 *  mishandle. Null when the SVG does not state a renderable size. */
internal fun renderUnwrappedSwitchSvg(svg: String): Bitmap? {
    val root = rootTag.find(svg)?.value ?: return null
    val viewBox = attr(root, "viewBox")
        ?.trim()
        ?.split(separators)
        ?.mapNotNull { it.toFloatOrNull() }
        ?.takeIf { it.size == 4 }
    val width = length(attr(root, "width")) ?: viewBox?.get(2) ?: return null
    val height = length(attr(root, "height")) ?: viewBox?.get(3) ?: return null
    if (width <= 0f || height <= 0f) return null

    val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1))) }
    val dom = SVGDOM(Data.makeFromBytes(svg.encodeToByteArray()))
    val canvas = Canvas(bitmap)
    try {
        dom.setContainerSize(width, height)
        dom.render(canvas)
    } finally {
        canvas.close()
        dom.close()
    }
    // A decode that "succeeds" into an empty frame is worse than a failure: the title-text
    // fallback only runs when decoding actually errors, so a blank success hides the title too.
    // Whatever this file is, the stock decoder previously handled it (or errored into the
    // fallback) — either is better than an invisible logo.
    if (!hasInk(bitmap)) return null
    return bitmap
}

/** True when [bitmap] holds any pixel more opaque than a faint hairline. */
private fun hasInk(bitmap: Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return false
    val alpha: ByteArray? = bitmap.readPixels(
        ImageInfo(width, height, ColorType.ALPHA_8, ColorAlphaType.PREMUL),
        width,
        0,
        0,
    )
    if (alpha == null) return true // cannot measure; assume it drew
    for (a in alpha) if ((a.toInt() and 0xFF) > 8) return true
    return false
}

class SvgSwitchFallbackDecoder(
    private val svg: String,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val bitmap = renderUnwrappedSwitchSvg(unwrapIllustratorSwitch(svg)) ?: return null
        return DecodeResult(image = bitmap.asImage(), isSampled = false)
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val isSvg = result.mimeType?.contains("svg", ignoreCase = true) == true ||
                result.source.fileOrNull()?.name?.endsWith(".svg", ignoreCase = true) == true
            if (!isSvg) return null
            val svg = runCatching { result.source.source().peek().readUtf8() }.getOrNull() ?: return null
            if (!isSwitchFallbackSvg(svg)) return null
            return SvgSwitchFallbackDecoder(svg, options)
        }
    }
}

private fun length(value: String?): Float? = value?.trim()?.removeSuffix("px")?.toFloatOrNull()
