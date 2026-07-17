package app.vellum.reader.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.vellum.reader.epub.OpenedEpub
import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

/**
 * Natural ordering so "page2" sorts before "page10" — comic archives rely on
 * filename order, and plain lexicographic order shuffles unpadded numbers.
 */
internal val naturalOrder = Comparator<String> { a, b ->
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isDigit() && b[j].isDigit()) {
            var numA = 0L
            var numB = 0L
            while (i < a.length && a[i].isDigit()) numA = numA * 10 + (a[i++] - '0')
            while (j < b.length && b[j].isDigit()) numB = numB * 10 + (b[j++] - '0')
            if (numA != numB) return@Comparator numA.compareTo(numB)
        } else {
            val cmp = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
            if (cmp != 0) return@Comparator cmp
            i++
            j++
        }
    }
    (a.length - i).compareTo(b.length - j)
}

/** A page-image sequence, whatever container it came from. */
interface ComicSource {
    val pageCount: Int
    suspend fun pageBytes(index: Int): ByteArray?
    fun close()
}

/** CBZ: a ZIP of images in filename order. */
class CbzComicSource(file: File) : ComicSource {
    private val zip = ZipFile(file)
    private val entries = zip.entries().asSequence()
        .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
        .sortedWith(compareBy(naturalOrder) { it.name })
        .toList()

    override val pageCount: Int get() = entries.size

    override suspend fun pageBytes(index: Int): ByteArray? = withContext(Dispatchers.IO) {
        entries.getOrNull(index)?.let { zip.getInputStream(it).use { stream -> stream.readBytes() } }
    }

    override fun close() = zip.close()
}

/** CBR: a RAR of images, via the pure-Java junrar (no unrar licensing). */
class CbrComicSource(file: File) : ComicSource {
    private val archive = Archive(file)
    private val headers = archive.fileHeaders
        .filter { !it.isDirectory && it.fileName.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
        .sortedWith(compareBy(naturalOrder) { it.fileName })

    override val pageCount: Int get() = headers.size

    override suspend fun pageBytes(index: Int): ByteArray? = withContext(Dispatchers.IO) {
        headers.getOrNull(index)?.let { header ->
            ByteArrayOutputStream().use { out ->
                archive.extractFile(header, out)
                out.toByteArray()
            }
        }
    }

    override fun close() = archive.close()
}

/**
 * Fixed-layout EPUB (the common manga container): each spine page is an XHTML
 * wrapping one image; we pull that image straight from the container.
 */
class FixedEpubComicSource(private val epub: OpenedEpub) : ComicSource {

    override val pageCount: Int get() = epub.chapterCount

    override suspend fun pageBytes(index: Int): ByteArray? {
        val html = epub.chapterHtml(index) ?: return null
        val document = Jsoup.parse(html)
        val href = document.selectFirst("img")?.attr("src")
            ?: document.selectFirst("image")?.let { it.attr("xlink:href").ifBlank { it.attr("href") } }
            ?: return null
        return epub.resourceBytes(epub.chapterHref(index), href)
    }

    override fun close() = epub.close()
}

/**
 * Decode-side of every comic source: samples pages to display width and keeps
 * a small LRU so page flips feel instant; callers prefetch neighbors.
 */
class ComicPageStore(private val source: ComicSource) {

    private val cache = LruCache<String, Bitmap>(6)
    private val mutex = Mutex()

    val pageCount: Int get() = source.pageCount

    suspend fun page(index: Int, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$index@$targetWidthPx"
        cache.get(key)?.let { return@withContext it }
        mutex.withLock {
            cache.get(key)?.let { return@withLock it }
            val bytes = source.pageBytes(index) ?: return@withLock null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { cache.put(key, it) }
        }
    }

    fun close() = source.close()
}
