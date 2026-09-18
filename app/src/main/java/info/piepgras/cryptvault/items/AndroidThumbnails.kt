package info.piepgras.cryptvault.items

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import info.piepgras.cryptvault.provider.ProxyDescriptors
import info.piepgras.cryptvault.vault.CryptomatorVault
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Thumbnails for images (respecting EXIF orientation), videos (a frame near the start) and
 * PDFs (the first page). Every byte comes through the vault; the PDF path uses a proxy
 * descriptor so the renderer can seek without a plaintext file.
 */
class AndroidThumbnails(private val context: Context) : ThumbnailMaker {

    companion object {
        const val MAX_EDGE = 256
        const val JPEG_QUALITY = 70
    }

    override fun make(
        mime: String,
        name: String,
        size: Long,
        openStream: () -> InputStream,
        openRandom: () -> CryptomatorVault.RandomAccessReader,
    ): ByteArray? {
        if (size <= 0) return null
        val bitmap = when {
            mime.startsWith("image/") && mime != "image/svg+xml" -> image(openStream)
            mime.startsWith("video/") -> video(openRandom)
            mime == "application/pdf" -> pdf(openRandom)
            else -> null
        } ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun image(openStream: () -> InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_EDGE && bounds.outHeight / (sample * 2) >= MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        val decoded = openStream().use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = runCatching { openStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return fit(rotate(decoded, orientation))
    }

    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun fit(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= MAX_EDGE && h <= MAX_EDGE) return bitmap
        val scale = MAX_EDGE.toFloat() / maxOf(w, h)
        val scaled = bitmap.scale(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun video(openRandom: () -> CryptomatorVault.RandomAccessReader): Bitmap? {
        val reader = openRandom()
        val source = object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= reader.size) return -1
                val n = reader.read(position, ByteBuffer.wrap(buffer, offset, size))
                return if (n <= 0) -1 else n
            }
            override fun getSize(): Long = reader.size
            override fun close() = reader.close()
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source)
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(-1)
                ?: return null
            fit(frame)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { source.close() }
        }
    }

    private fun pdf(openRandom: () -> CryptomatorVault.RandomAccessReader): Bitmap? {
        val pfd = try {
            ProxyDescriptors.readOnly(context, openRandom())
        } catch (e: Exception) {
            return null
        }
        return try {
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount < 1) return null
                renderer.openPage(0).use { page ->
                    val scale = MAX_EDGE.toFloat() / maxOf(page.width, page.height)
                    val w = maxOf(1, (page.width * scale).toInt())
                    val h = maxOf(1, (page.height * scale).toInt())
                    val bitmap = createBitmap(w, h)
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { pfd.close() }
        }
    }
}
