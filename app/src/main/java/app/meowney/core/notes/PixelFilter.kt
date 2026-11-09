package app.meowney.core.notes

import kotlin.math.max
import kotlin.math.roundToInt

/** How a photo is kept: as is (resized), pixelated in its own colours, or pixelated in the app's warm palette. */
enum class PhotoStyle(val label: String) { ORIGINAL("Gốc"), PIXEL("Pixel"), WARM("Pixel ấm") }

/** Size of the pixels: the long side of the result in art pixels. */
enum class PixelSize(val label: String, val cells: Int) { BIG("Ô to", 64), MID("Vừa", 96), SMALL("Ô nhỏ", 128) }

/**
 * Pure pixel-art filter on ARGB int arrays (so it runs in plain JVM tests). The Android side
 * decodes and encodes the bitmaps (core/notes/NoteImages.kt).
 */
object PixelFilter {
    /** ~24 warm colours drawn from the app's wood, cream and jar colours plus a few greens and blues. */
    val WARM = intArrayOf(
        0xFF2A2027.toInt(), 0xFF3B2A24.toInt(), 0xFF6B5446.toInt(), 0xFF8A5536.toInt(),
        0xFFB0703F.toInt(), 0xFFC98A4B.toInt(), 0xFFE8B77A.toInt(), 0xFFEAD7B0.toInt(),
        0xFFF6E7C8.toInt(), 0xFFFFF8EA.toInt(), 0xFFF2C14E.toInt(), 0xFFE86A33.toInt(),
        0xFFD9534F.toInt(), 0xFFA33A3A.toInt(), 0xFFF08DB3.toInt(), 0xFFA77BCA.toInt(),
        0xFF5E4A7A.toInt(), 0xFF6FA8DC.toInt(), 0xFF3F6E9C.toInt(), 0xFF8FB3CC.toInt(),
        0xFF5CBF6A.toInt(), 0xFF3A7D44.toInt(), 0xFF9CC07A.toInt(), 0xFF7A7068.toInt(),
    )

    /** Target size whose long side is [cells]; never bigger than the source. */
    fun targetSize(w: Int, h: Int, cells: Int): Pair<Int, Int> {
        val long = max(w, h)
        if (long <= cells) return w to h
        val k = cells.toFloat() / long
        return max(1, (w * k).roundToInt()) to max(1, (h * k).roundToInt())
    }

    /** Box average down to [tw] x [th]. */
    fun downscale(src: IntArray, w: Int, h: Int, tw: Int, th: Int): IntArray {
        val out = IntArray(tw * th)
        for (ty in 0 until th) {
            val y0 = ty * h / th
            val y1 = max(y0 + 1, (ty + 1) * h / th)
            for (tx in 0 until tw) {
                val x0 = tx * w / tw
                val x1 = max(x0 + 1, (tx + 1) * w / tw)
                var r = 0L; var g = 0L; var b = 0L; var n = 0L
                for (y in y0 until y1) for (x in x0 until x1) {
                    val c = src[y * w + x]
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                out[ty * tw + tx] = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            }
        }
        return out
    }

    /** The [max] most used colours after rounding each channel to 16 levels. */
    fun popularPalette(px: IntArray, max: Int = 32): IntArray {
        val counts = HashMap<Int, Int>()
        for (c in px) {
            val k = c and 0xF0F0F0
            counts[k] = (counts[k] ?: 0) + 1
        }
        return counts.entries.sortedByDescending { it.value }.take(max)
            .map { (0xFF shl 24) or it.key or ((it.key shr 4) and 0x0F0F0F) }.toIntArray()
    }

    /** Each pixel replaced by the nearest palette colour (weighted RGB distance). */
    fun quantize(px: IntArray, palette: IntArray): IntArray {
        val cache = HashMap<Int, Int>()
        return IntArray(px.size) { i ->
            val c = px[i] or (0xFF shl 24)
            cache.getOrPut(c) { nearest(c, palette) }
        }
    }

    private fun nearest(c: Int, palette: IntArray): Int {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        var best = palette[0]; var bestD = Int.MAX_VALUE
        for (p in palette) {
            val dr = r - ((p shr 16) and 0xFF); val dg = g - ((p shr 8) and 0xFF); val db = b - (p and 0xFF)
            val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
            if (d < bestD) { bestD = d; best = p }
        }
        return best
    }

    /** Pixelates [src] to a long side of [cells]; returns the small image and its size. */
    fun apply(src: IntArray, w: Int, h: Int, style: PhotoStyle, cells: Int): Triple<IntArray, Int, Int> {
        val (tw, th) = targetSize(w, h, cells)
        val small = downscale(src, w, h, tw, th)
        val out = when (style) {
            PhotoStyle.ORIGINAL -> small
            PhotoStyle.PIXEL -> quantize(small, popularPalette(small))
            PhotoStyle.WARM -> quantize(small, WARM)
        }
        return Triple(out, tw, th)
    }
}
