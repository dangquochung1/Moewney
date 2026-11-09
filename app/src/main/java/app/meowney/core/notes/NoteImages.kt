package app.meowney.core.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Photos of the notebook, copied into the app's own storage (filesDir/notes). */
class NoteImages(private val context: Context) {
    val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    fun file(name: String) = File(dir, name)

    /** Reads [uri] with its long side at most [maxSide], turned upright. */
    suspend fun decode(uri: Uri, maxSide: Int): Bitmap = withContext(Dispatchers.IO) {
        val r = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0) { "Không đọc được ảnh này." }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = r.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("Không đọc được ảnh này.")
        val degrees = runCatching {
            r.openInputStream(uri)!!.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrDefault(0f)
        val upright = if (degrees == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        scaleDown(upright, maxSide)
    }

    /** The photo as it will be kept ([PhotoStyle.ORIGINAL] is returned unchanged). */
    fun render(src: Bitmap, style: PhotoStyle, size: PixelSize): Bitmap {
        if (style == PhotoStyle.ORIGINAL) return src
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        val (out, w, h) = PixelFilter.apply(px, src.width, src.height, style, size.cells)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Copies the picked photo in, filtered; returns the file name to put in the block. */
    suspend fun import(uri: Uri, style: PhotoStyle, size: PixelSize): String = withContext(Dispatchers.IO) {
        val src = decode(uri, if (style == PhotoStyle.ORIGINAL) ORIGINAL_SIDE else WORK_SIDE)
        save(render(src, style, size), style)
    }

    suspend fun save(bmp: Bitmap, style: PhotoStyle): String = withContext(Dispatchers.IO) {
        val name = UUID.randomUUID().toString() + if (style == PhotoStyle.ORIGINAL) ".jpg" else ".png"
        file(name).outputStream().use {
            if (style == PhotoStyle.ORIGINAL) bmp.compress(Bitmap.CompressFormat.JPEG, 85, it)
            else bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        name
    }

    fun delete(name: String) {
        if (isSafeName(name)) file(name).delete()
    }

    /** Deletes files that no block points to any more. */
    fun sweep(used: Set<String>) {
        dir.listFiles()?.filter { it.isFile && it.name !in used }?.forEach { it.delete() }
    }

    private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val long = max(b.width, b.height)
        if (long <= maxSide) return b
        val k = maxSide.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * k).roundToInt().coerceAtLeast(1), (b.height * k).roundToInt().coerceAtLeast(1), true)
    }

    companion object {
        const val DIR = "notes"
        const val ORIGINAL_SIDE = 1600
        const val WORK_SIDE = 768

        /** Only plain names we made ourselves (used when restoring a backup too). */
        fun isSafeName(name: String) = name.matches(Regex("[A-Za-z0-9-]{1,64}\\.(jpg|png)"))
    }
}
