package app.imagen.chat.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

object ImageUtils {

    data class AlphaStats(
        /** Share of pixels that are not fully opaque. */
        val transparentFraction: Float,
        /** True when (almost) every border pixel is transparent, i.e. a clean cut-out. */
        val borderClear: Boolean,
    ) {
        val hasAlpha: Boolean get() = transparentFraction > 0.002f
    }

    /** Alpha statistics, measured on a copy no larger than 1024 px so huge images don't spike memory. */
    fun alphaStats(bitmap: Bitmap): AlphaStats {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= 1024) return exactAlphaStats(bitmap)
        val scale = 1024f / longest
        val small = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            false,
        )
        return try {
            exactAlphaStats(small)
        } finally {
            if (small !== bitmap) small.recycle()
        }
    }

    private fun exactAlphaStats(bitmap: Bitmap): AlphaStats {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        var clear = 0
        for (p in px) if ((p ushr 24) < 250) clear++
        var border = 0
        var borderClear = 0
        fun check(x: Int, y: Int) {
            border++
            if ((px[y * w + x] ushr 24) < 16) borderClear++
        }
        for (x in 0 until w) {
            check(x, 0)
            check(x, h - 1)
        }
        for (y in 1 until h - 1) {
            check(0, y)
            check(w - 1, y)
        }
        return AlphaStats(clear.toFloat() / px.size, borderClear.toFloat() / border >= 0.97f)
    }

    /**
     * Local fallback for providers that ignore transparency: flood-fills the
     * background colour inward from the edges and softens the cut line.
     * Returns null when the background isn't a clean, uniform colour.
     */
    fun removeBackground(src: Bitmap, tolerance: Int = 40): Bitmap? {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val corners = intArrayOf(px[0], px[w - 1], px[(h - 1) * w], px[w * h - 1])
        if (corners.any { diff(it, corners[0]) > 30 }) return null
        val bg = intArrayOf(
            corners.sumOf { (it shr 16) and 0xFF } / 4,
            corners.sumOf { (it shr 8) and 0xFF } / 4,
            corners.sumOf { it and 0xFF } / 4,
        )
        val bgColor = (0xFF shl 24) or (bg[0] shl 16) or (bg[1] shl 8) or bg[2]

        val removed = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        fun seed(i: Int) {
            if (!removed[i] && diff(px[i], bgColor) <= tolerance) {
                removed[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) {
            seed(x)
            seed((h - 1) * w + x)
        }
        for (y in 0 until h) {
            seed(y * w)
            seed(y * w + w - 1)
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }
        val removedFraction = tail.toFloat() / (w * h)
        if (removedFraction < 0.05f || removedFraction > 0.98f) return null

        val out = px.copyOf()
        for (i in out.indices) {
            if (removed[i]) {
                out[i] = out[i] and 0x00FFFFFF
                continue
            }
            val x = i % w
            val y = i / w
            val nearRemoved = (x > 0 && removed[i - 1]) || (x < w - 1 && removed[i + 1]) ||
                (y > 0 && removed[i - w]) || (y < h - 1 && removed[i + w])
            if (nearRemoved) {
                val d = diff(px[i], bgColor)
                val keep = ((d - tolerance) / (tolerance * 1.5f)).coerceIn(0f, 1f)
                val alpha = ((px[i] ushr 24) * keep).toInt()
                out[i] = (alpha shl 24) or (px[i] and 0x00FFFFFF)
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun diff(a: Int, b: Int): Int = maxOf(
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
        abs((a and 0xFF) - (b and 0xFF)),
    )

    /** Copies a picked image into [dest] as an upright PNG no larger than [maxEdge] px. */
    fun importReference(context: Context, uri: Uri, dest: File, maxEdge: Int = 2048): Boolean = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxEdge) {
                val scale = maxEdge.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
        savePng(bitmap, dest)
        true
    }.getOrDefault(false)

    fun savePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Copies the PNG into Pictures/ImagenChat via MediaStore. Returns true on success. */
    fun saveToGallery(context: Context, file: File): Boolean = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "imagen_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ImagenChat")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
