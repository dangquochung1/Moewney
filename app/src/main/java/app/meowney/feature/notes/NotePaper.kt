package app.meowney.feature.notes

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.meowney.container
import app.meowney.core.data.NoteFull
import app.meowney.core.notes.BlockType
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** The six paper colours a note can have: the app palette (a stored index keeps its slot). */
val NOTE_PAPERS = listOf(
    Pal.Card, // white: the default sheet
    Pal.GoldenDune,
    Pal.OceanMist,
    Pal.MorningSky,
    Pal.CoralReef,
    Color(0xFFF0A48F), // Sunset Clay, lightened so dark text reads on it
)

fun paper(i: Int) = NOTE_PAPERS[i.coerceIn(0, NOTE_PAPERS.lastIndex)]

/** A sheet of paper: rounded, flat fill, a thin border; [pin] adds a small red pin at the top. */
fun Modifier.paperSheet(color: Color, u: Float, pin: Boolean = false): Modifier = drawBehind {
    val r = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())
    drawRoundRect(color, cornerRadius = r)
    drawRoundRect(Color.Black.copy(alpha = 0.08f), cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    if (pin) {
        val cx = size.width / 2
        drawCircle(Pal.Red, radius = 2.5f * u, center = Offset(cx, 3 * u))
        drawCircle(Color.White.copy(alpha = 0.6f), radius = 0.8f * u, center = Offset(cx - 0.8f * u, 2.2f * u))
    }
}

/** Decoded photos, keyed by name and size, so grids do not decode the same file twice. */
private object PhotoCache {
    val cache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun load(file: File, maxSide: Int): ImageBitmap? {
        val key = "${file.name}@$maxSide"
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bmp.asImageBitmap().also { cache.put(key, it) }
    }
}

/**
 * A photo of the notebook. Pixel photos (.png) are drawn with hard edges; [maxSide] limits the
 * decoded size (grids use a small one).
 */
@Composable
fun NotePhoto(name: String, modifier: Modifier = Modifier, maxSide: Int = 1600, maxHeight: Dp? = null) {
    val c = LocalContext.current.container
    val bmp by produceState<ImageBitmap?>(null, name, maxSide) {
        value = withContext(Dispatchers.IO) { PhotoCache.load(c.noteImages.file(name), maxSide) }
    }
    val b = bmp ?: return
    val pixel = name.endsWith(".png")
    Image(
        b, contentDescription = null,
        modifier = modifier.fillMaxWidth().let { if (maxHeight != null) it.heightIn(max = maxHeight) else it }
            .aspectRatio(b.width.toFloat() / b.height, matchHeightConstraintsFirst = maxHeight != null),
        contentScale = if (maxHeight != null) ContentScale.Crop else ContentScale.FillWidth,
        filterQuality = if (pixel) FilterQuality.None else FilterQuality.Low,
    )
}

/** A note in the grid: title, first photo, a few lines and checklist items, tags. */
@Composable
fun NoteCard(n: NoteFull, modifier: Modifier = Modifier) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    Column(
        modifier
            .paperSheet(paper(n.note.color), m.ui.toFloat(), pin = n.note.pinned)
            .padding(start = m.unit * 3, end = m.unit * 3, top = m.unit * 4, bottom = m.unit * 4),
        verticalArrangement = Arrangement.spacedBy(m.unit),
    ) {
        if (n.note.title.isNotBlank()) PixelText(n.note.title, style = type.bodyBold, color = Pal.Ink, maxLines = 2)
        n.firstImage?.let { NotePhoto(it, maxSide = 400, maxHeight = m.unit * 60) }
        var lines = 0
        for (b in n.blocks) {
            if (lines >= 6) break
            when (b.type) {
                BlockType.TEXT.name -> if (b.text.isNotBlank()) {
                    PixelText(b.text.trim(), style = type.caption, color = Pal.Ink, maxLines = 4)
                    lines += 2
                }
                BlockType.CHECK.name -> {
                    CheckLine(b.text, b.checked)
                    lines++
                }
            }
        }
        if (n.checks.size > 0 && lines >= 6) {
            PixelText("${n.checks.count { it.checked }}/${n.checks.size} đã xong", style = type.caption, color = Pal.InkSoft)
        }
        if (n.tags.isNotEmpty()) {
            PixelText(n.tags.joinToString(" ") { "#" + it.name }, style = type.caption, color = Pal.Wood, maxLines = 2)
        }
        if (n.isEmpty) PixelText("(trống)", style = type.caption, color = Pal.InkSoft)
    }
}

/** A small tick box for checklist lines ([px] UI art pixels wide). */
@Composable
fun MiniCheck(checked: Boolean, modifier: Modifier = Modifier, px: Int = 7) {
    val m = LocalPixelMetrics.current
    app.meowney.core.ui.TickBox(checked, maxOf(16.dp, m.unit * px), modifier)
}

/** One read-only checklist line: a small box and the text, struck through when done. */
@Composable
fun CheckLine(text: String, checked: Boolean, modifier: Modifier = Modifier) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        MiniCheck(checked, Modifier.padding(top = m.unit))
        PixelText(
            text, style = type.caption.copy(textDecoration = if (checked) TextDecoration.LineThrough else null),
            color = if (checked) Pal.InkSoft else Pal.Ink, maxLines = 2,
        )
    }
}
