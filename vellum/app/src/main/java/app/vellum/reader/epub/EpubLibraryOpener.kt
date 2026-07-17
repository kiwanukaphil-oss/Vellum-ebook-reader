package app.vellum.reader.epub

import android.content.Context
import android.graphics.Bitmap
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/**
 * Thin wrapper around Readium's streamer: it handles container unzipping,
 * OPF/spine parsing and metadata; layout and drawing stay ours (build plan §2).
 */
class EpubLibraryOpener(context: Context) {

    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    private val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory = null),
    )

    suspend fun open(file: File): OpenedEpub? {
        val asset = assetRetriever.retrieve(file).getOrElse { return null }
        val publication = publicationOpener.open(asset, allowUserInteraction = false)
            .getOrElse {
                asset.close()
                return null
            }
        return OpenedEpub(publication)
    }
}

/** One nav-doc entry mapped onto a spine index, for the reader's TOC sheet. */
data class TocEntry(val title: String, val chapterIndex: Int, val depth: Int)

/** An opened publication exposing exactly what the Phase 1 reader needs. */
class OpenedEpub(private val publication: Publication) {

    val title: String
        get() = publication.metadata.title ?: "Untitled"

    val author: String
        get() = publication.metadata.authors.joinToString(", ") { it.name }.ifBlank { "Unknown author" }

    /** Spine order — the chapters, as the author sequenced them. */
    val readingOrder: List<Link>
        get() = publication.readingOrder

    val chapterCount: Int
        get() = publication.readingOrder.size

    fun chapterHref(index: Int): String = publication.readingOrder[index].href.toString()

    /** Reads one spine item's XHTML source, or null if the resource is unreadable. */
    suspend fun chapterHtml(index: Int): String? {
        val link = publication.readingOrder.getOrNull(index) ?: return null
        val resource = publication.get(link) ?: return null
        val bytes = resource.read().getOrElse { return null }
        return bytes.toString(Charsets.UTF_8)
    }

    /**
     * The nav-doc TOC flattened onto spine indices (fragment-only differences
     * collapse to the chapter). Books without a usable nav doc fall back to
     * spine-link titles, then to plain "Chapter N" labels.
     */
    fun tableOfContents(): List<TocEntry> {
        val hrefToIndex = publication.readingOrder.mapIndexed { index, link ->
            link.href.toString().trimStart('/').substringBefore('#') to index
        }.toMap()
        val entries = mutableListOf<TocEntry>()
        fun walk(links: List<Link>, depth: Int) {
            links.forEach { link ->
                val href = link.href.toString().trimStart('/').substringBefore('#')
                val title = link.title?.takeIf { it.isNotBlank() }
                val index = hrefToIndex[href]
                if (index != null && title != null) entries.add(TocEntry(title, index, depth))
                walk(link.children, depth + 1)
            }
        }
        walk(publication.tableOfContents, 0)
        if (entries.isNotEmpty()) return entries
        return publication.readingOrder.mapIndexed { index, link ->
            TocEntry(link.title?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}", index, 0)
        }
    }

    /** The publication's embedded cover art, if any. */
    suspend fun coverBitmap(): Bitmap? = try {
        publication.cover()
    } catch (e: Exception) {
        null
    }

    /**
     * Fixed-layout EPUBs (locked page geometry) route to the comic reader.
     * RWPM dropped the typed presentation accessor; the EPUB-extensibility
     * metadata still carries {"presentation": {"layout": "fixed"}}.
     */
    val isFixedLayout: Boolean
        get() = try {
            val presentation = publication.metadata.otherMetadata["presentation"] as? Map<*, *>
            presentation?.get("layout")?.toString()?.equals("fixed", ignoreCase = true) == true
        } catch (e: Exception) {
            false
        }

    /** Reads a resource referenced relative to a chapter (e.g. a page image). */
    suspend fun resourceBytes(chapterHref: String, relativeHref: String): ByteArray? {
        val resolved = resolveHref(chapterHref, relativeHref)
        val link = (publication.readingOrder + publication.resources).firstOrNull {
            it.href.toString().trimStart('/') == resolved
        } ?: return null
        val resource = publication.get(link) ?: return null
        return resource.read().getOrElse { return null }
    }

    /** Resolves "../images/p1.jpg" against the chapter's own path. */
    private fun resolveHref(base: String, relative: String): String {
        val baseDir = base.trimStart('/').substringBeforeLast('/', "")
        val combined = (if (baseDir.isEmpty()) "" else "$baseDir/") + relative
        val segments = ArrayDeque<String>()
        combined.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> segments.removeLastOrNull()
                else -> segments.addLast(segment)
            }
        }
        return segments.joinToString("/")
    }

    fun close() {
        publication.close()
    }
}
