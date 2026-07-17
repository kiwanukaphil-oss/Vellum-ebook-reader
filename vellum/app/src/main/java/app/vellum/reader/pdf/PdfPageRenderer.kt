package app.vellum.reader.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Page-bitmap source over Android's built-in PdfRenderer, behind Vellum's
 * reader-engine boundary so Pdfium can replace it if rendering quality ever
 * disappoints. PdfRenderer allows one open page at a time — all rendering is
 * serialized behind a mutex — and recent pages are cached at display width.
 */
class PdfPageRenderer(file: File) {

    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val mutex = Mutex()

    /** ~5 full-resolution pages ≈ 40–60MB worst case; evicts by count. */
    private val cache = LruCache<String, Bitmap>(5)

    val pageCount: Int get() = renderer.pageCount

    /** Width/height ratio of a page, for layout before the bitmap arrives. */
    suspend fun pageAspectRatio(pageIndex: Int): Float = mutex.withLock {
        renderer.openPage(pageIndex).use { page ->
            page.width.toFloat() / page.height.toFloat()
        }
    }

    /** Renders (or returns cached) page bitmap at [targetWidthPx]. */
    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap = withContext(Dispatchers.IO) {
        val key = "$pageIndex@$targetWidthPx"
        cache.get(key)?.let { return@withContext it }
        mutex.withLock {
            cache.get(key)?.let { return@withLock it }
            renderer.openPage(pageIndex).use { page ->
                val height = (targetWidthPx * page.height.toFloat() / page.width).roundToInt()
                val bitmap = Bitmap.createBitmap(targetWidthPx, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, bitmap)
                bitmap
            }
        }
    }

    fun close() {
        try {
            renderer.close()
            descriptor.close()
        } catch (e: Exception) {
            // Already closed — nothing to release.
        }
    }
}
