package app.vellum.reader.reader.html

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Converts one chapter's XHTML into styled [ContentBlock]s, honoring the curated
 * markup subset (headings, emphasis, quotes, lists); Vellum's own typography
 * system owns everything else, per the "publisher CSS is a guest" rule.
 */
object HtmlBlockParser {

    private val whitespace = Regex("\\s+")

    fun parse(html: String): List<ContentBlock> {
        val body = Jsoup.parse(html).body()
        val blocks = mutableListOf<ContentBlock>()
        collectBlocks(body, blocks)
        return blocks
    }

    /**
     * Walks the element tree depth-first, emitting a block for each block-level
     * element that directly carries text. Containers (div, section) recurse;
     * unknown elements fall through to their children so no prose is lost.
     */
    private fun collectBlocks(element: Element, out: MutableList<ContentBlock>) {
        for (child in element.children()) {
            when (child.tagName().lowercase()) {
                "p" -> emitBlock(child, BlockKind.BODY, out)
                "h1", "h2" -> emitBlock(child, if (child.tagName() == "h1") BlockKind.HEADING_1 else BlockKind.HEADING_2, out)
                "h3", "h4", "h5", "h6" -> emitBlock(child, BlockKind.HEADING_3, out)
                "blockquote" -> {
                    if (child.children().any { it.tagName() == "p" }) collectQuoteParagraphs(child, out)
                    else emitBlock(child, BlockKind.QUOTE, out)
                }
                "li" -> emitBlock(child, BlockKind.BODY, out, prefix = "• ")
                "img" -> emitImage(child.attr("src"), out)
                "image" -> emitImage(child.attr("xlink:href").ifBlank { child.attr("href") }, out)
                "svg" -> child.select("image").firstOrNull()?.let {
                    emitImage(it.attr("xlink:href").ifBlank { it.attr("href") }, out)
                }
                "ol", "ul", "div", "section", "article", "main", "aside", "figure", "nav", "header", "footer", "table", "tr", "td" ->
                    collectBlocks(child, out)
                "hr" -> Unit
                else -> {
                    // Inline or unknown element at block level: if it has its own
                    // block children recurse, otherwise render it as a paragraph.
                    if (child.children().isNotEmpty()) collectBlocks(child, out)
                    else emitBlock(child, BlockKind.BODY, out)
                }
            }
        }
    }

    /** Local resources only — remote image URLs would break the privacy rule. */
    private fun emitImage(src: String?, out: MutableList<ContentBlock>) {
        val cleaned = src?.trim().orEmpty()
        if (cleaned.isEmpty() || cleaned.startsWith("http://") || cleaned.startsWith("https://")) return
        out.add(ContentBlock(AnnotatedString(""), BlockKind.IMAGE, imageSrc = cleaned))
    }

    private fun collectQuoteParagraphs(quote: Element, out: MutableList<ContentBlock>) {
        for (p in quote.children()) {
            if (p.tagName() == "p") emitBlock(p, BlockKind.QUOTE, out) else collectBlocks(p, out)
        }
    }

    private fun emitBlock(element: Element, kind: BlockKind, out: MutableList<ContentBlock>, prefix: String = "") {
        val text = buildAnnotatedString {
            if (prefix.isNotEmpty()) append(prefix)
            appendInline(element, bold = false, italic = false)
        }
        if (text.text.isNotBlank()) {
            out.add(ContentBlock(trimEdges(text), kind))
        } else {
            // Image-only paragraph (<p><img/></p> is the common EPUB shape):
            // surface the images instead of dropping the block.
            element.select("img").forEach { emitImage(it.attr("src"), out) }
            element.select("image").forEach {
                emitImage(it.attr("xlink:href").ifBlank { it.attr("href") }, out)
            }
        }
    }

    /**
     * Recursively appends inline content, tracking bold/italic nesting and
     * collapsing runs of whitespace the way HTML rendering does.
     */
    private fun AnnotatedString.Builder.appendInline(node: Node, bold: Boolean, italic: Boolean) {
        for (child in node.childNodes()) {
            when (child) {
                is TextNode -> {
                    val collapsed = child.wholeText.replace(whitespace, " ")
                    if (collapsed.isEmpty()) continue
                    val style = SpanStyle(
                        fontWeight = if (bold) FontWeight.Bold else null,
                        fontStyle = if (italic) FontStyle.Italic else null,
                    )
                    if (bold || italic) {
                        pushStyle(style)
                        append(collapsed)
                        pop()
                    } else {
                        append(collapsed)
                    }
                }
                is Element -> when (child.tagName().lowercase()) {
                    "b", "strong" -> appendInline(child, bold = true, italic = italic)
                    "i", "em", "cite", "dfn" -> appendInline(child, bold = bold, italic = true)
                    "br" -> append("\n")
                    "img", "script", "style" -> Unit
                    else -> appendInline(child, bold, italic)
                }
            }
        }
    }

    /** Trims leading/trailing whitespace without losing span styling. */
    private fun trimEdges(text: AnnotatedString): AnnotatedString {
        val start = text.text.indexOfFirst { !it.isWhitespace() }
        if (start == -1) return AnnotatedString("")
        val end = text.text.indexOfLast { !it.isWhitespace() } + 1
        return text.subSequence(start, end)
    }
}
