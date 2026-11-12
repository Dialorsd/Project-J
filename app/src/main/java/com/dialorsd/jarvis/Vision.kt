package com.dialorsd.jarvis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v4: "see things". An image from the camera, the photo picker or "Share -> Jarvis" waits here until the
 * next question (typed or spoken); it is then sent once, inline, to the Gemini text model.
 * Docs: inline images in generateContent ("inline_data" with "mime_type"/"data"; whole request <= 20 MB):
 * https://ai.google.dev/api/generate-content and https://ai.google.dev/gemini-api/docs/image-understanding
 * Images are downscaled to at most 1536 px on the long side and re-encoded as JPEG (usually 150-500 KB).
 */
object Vision {
    class Pending(val jpeg: ByteArray, val thumbPath: String)

    /** The image waiting for a question (shown above the input box). */
    val pending = mutableStateOf<Pending?>(null)

    private const val MAX_SIDE = 1536
    private const val THUMB_SIDE = 360

    /** Where the camera writes its photo (cache/camera/photo.jpg), shared through our FileProvider. */
    fun cameraUri(ctx: Context): Uri {
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "photo.jpg")
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    }

    /** Decodes, downscales and stores a thumbnail. Blocking: call off the main thread. Throws on failure. */
    fun load(ctx: Context, uri: Uri): Pending = fromBitmap(ctx, decode(ctx, uri))

    fun fromBitmap(ctx: Context, src: Bitmap): Pending {
        val big = scale(src, MAX_SIDE)
        val out = ByteArrayOutputStream()
        big.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val thumb = scale(big, THUMB_SIDE)
        val dir = File(ctx.filesDir, "images").apply { mkdirs() }
        val f = File(dir, "img_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        // Keep only the newest 40 thumbnails on the phone.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
        return Pending(out.toByteArray(), f.absolutePath)
    }

    private fun decode(ctx: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder applies the EXIF rotation of camera photos.
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width; val h = info.size.height
                val k = MAX_SIDE.toFloat() / maxOf(w, h)
                if (k < 1f) decoder.setTargetSize(maxOf(1, (w * k).toInt()), maxOf(1, (h * k).toInt()))
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("could not read the image")
    }

    private fun scale(b: Bitmap, max: Int): Bitmap {
        val k = max.toFloat() / maxOf(b.width, b.height)
        if (k >= 1f) return b
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true)
    }

    /** Diagnostics: a small generated picture (red background, white square) with a known answer. */
    fun testImage(ctx: Context): Pending {
        val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(220, 30, 30))
        c.drawRect(80f, 80f, 176f, 176f, Paint().apply { color = Color.WHITE })
        return fromBitmap(ctx, b)
    }
}
