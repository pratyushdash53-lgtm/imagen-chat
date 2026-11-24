package app.imagen.chat.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.imagen.chat.data.ChatMessage
import app.imagen.chat.data.ChatStore
import app.imagen.chat.data.newId
import app.imagen.chat.net.ApiException

/** Turns the provider's bytes into a stored PNG with as little decoding and re-encoding as possible. */
object ImageProcessor {

    fun process(store: ChatStore, bytes: ByteArray, model: String, wantTransparent: Boolean): ChatMessage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ApiException("The provider's image couldn't be decoded.")
        }
        val isPng = bounds.outMimeType == "image/png"
        var width = bounds.outWidth
        var height = bounds.outHeight

        // Cheap look at a downsampled copy: is there real transparency, and is the border clear?
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= 512) sample *= 2
        val small = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: throw ApiException("The provider's image couldn't be decoded.")
        var stats = ImageUtils.alphaStats(small)
        small.recycle()

        var warning: String? = null
        var removed = false
        var output: Bitmap? = null

        if (wantTransparent && !stats.borderClear) {
            try {
                val full = decodeFull(bytes)
                val cut = ImageUtils.removeBackground(full)
                if (cut != null) {
                    removed = true
                    stats = ImageUtils.alphaStats(cut)
                    output = cut
                } else {
                    warning = "The provider didn't return a transparent background, and the backdrop wasn't " +
                        "plain enough to cut out automatically."
                    if (!isPng) output = full
                }
            } catch (e: OutOfMemoryError) {
                warning = "This image is too large to cut out on this device, so it was kept as returned."
            }
        } else if (!isPng) {
            output = decodeFull(bytes)
        }

        val name = newId() + ".png"
        val target = store.imageFile(name)
        if (output != null) {
            ImageUtils.savePng(output, target)
            width = output.width
            height = output.height
        } else {
            // Already a PNG and nothing to change: store the provider's bytes untouched.
            target.writeBytes(bytes)
        }
        return ChatMessage(
            role = "assistant",
            imageFile = name,
            model = model,
            width = width,
            height = height,
            hasAlpha = stats.hasAlpha,
            backgroundRemoved = removed,
            warning = warning,
        )
    }

    private fun decodeFull(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: throw ApiException("The provider's image couldn't be decoded.")
}
