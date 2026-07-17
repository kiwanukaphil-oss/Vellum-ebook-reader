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

    /** Set under the mutex; renders that lose the race bail out with null. */
    private var closed = false

    /** Byte-sized eviction: zoom re-renders vary widths, counts would lie. */
    private val cache = object : LruCache<String, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    val pageCount: Int get() = renderer.pageCount

    /** Width/height ratio of a page, for layout before the bitmap arrives. */
    suspend fun pageAspectRatio(pageIndex: Int): Float = mutex.withLock {
        if (closed) return@withLock 1f
        renderer.openPage(pageIndex).use { page ->
            page.width.toFloat() / page.height.toFloat()
        }
    }

    /** Renders (or returns cached) page bitmap; null once the renderer closed. */
    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$pageIndex@$targetWidthPx"
        cache.get(key)?.let { return@withContext it }
        mutex.withLock {
            if (closed) return@withLock null
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

    /** Waits for any in-flight render — closing PdfRenderer mid-render crashes. */
    fun close() {
        kotlinx.coroutines.runBlocking {
            mutex.withLock {
                closed = true
                try {
                    renderer.close()
                    descriptor.close()
                } catch (e: Exception) {
                    // Already closed — nothing to release.
                }
            }
        }
    }
}
