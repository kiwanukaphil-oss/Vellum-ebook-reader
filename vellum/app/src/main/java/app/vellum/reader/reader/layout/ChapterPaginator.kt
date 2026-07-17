package app.vellum.reader.reader.layout

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vellum.reader.core.fonts.VellumFonts
import app.vellum.reader.core.model.TypographySettings
import app.vellum.reader.reader.html.BlockKind
import app.vellum.reader.reader.html.ContentBlock
import kotlin.math.roundToInt

/** One measured block plus its cumulative character offset within the chapter. */
class MeasuredBlock(
    val layout: TextLayoutResult,
    val charStart: Int,
    val indentPx: Float,
)

/** A vertical slice of one block: lines [firstLine..lastLine] drawn at page offset [y]. */
data class PageSlice(
    val blockIndex: Int,
    val firstLine: Int,
    val lastLine: Int,
    val y: Float,
)

/** One ready-to-draw page and the chapter character range it covers. */
data class ReaderPage(
    val slices: List<PageSlice>,
    val startChar: Int,
    val endChar: Int,
)

/** The full result of paginating one chapter at one viewport + typography. */
class PaginatedChapter(
    val measured: List<MeasuredBlock>,
    val pages: List<ReaderPage>,
    val totalChars: Int,
) {
    /** The page containing a character offset — how positions survive reflow. */
    fun pageIndexFor(charOffset: Int): Int {
        if (pages.isEmpty()) return 0
        val index = pages.indexOfFirst { charOffset < it.endChar }
        return if (index == -1) pages.lastIndex else index
    }
}

/**
 * Lays out chapter blocks at a fixed content width and packs their lines into
 * pages of the content height, at line granularity like a printed book.
 * [columns] > 1 paginates for multi-column spreads (two-page landscape): each
 * "page" is one column; the screen composes columns side by side.
 */
class ChapterPaginator(
    private val measurer: TextMeasurer,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    private val typography: TypographySettings,
    density: Density,
    val columns: Int = 1,
) {
    private val marginPx = with(density) { typography.pageMarginDp.dp.toPx() }
    private val blockSpacingPx = with(density) { typography.paragraphSpacingDp.dp.toPx() }
    private val quoteIndentPx = with(density) { 18.dp.toPx() }
    private val fontFamily = VellumFonts.byId(typography.fontId).family

    /** Width of one column of text; the screen sizes its page boxes to match. */
    val contentWidthPx = ((viewportWidthPx - (columns + 1) * marginPx) / columns).roundToInt()
    val contentHeightPx = viewportHeightPx - 2 * marginPx
    val pageMarginPx = marginPx

    fun paginate(blocks: List<ContentBlock>): PaginatedChapter {
        val measured = measureBlocks(blocks)
        val pages = packIntoPages(measured)
        val totalChars = measured.lastOrNull()?.let { it.charStart + it.layout.layoutInput.text.length } ?: 0
        return PaginatedChapter(measured, pages, totalChars)
    }

    private fun measureBlocks(blocks: List<ContentBlock>): List<MeasuredBlock> {
        var charCursor = 0
        return blocks.map { block ->
            val indent = if (block.kind == BlockKind.QUOTE) quoteIndentPx else 0f
            val width = (contentWidthPx - 2 * indent).roundToInt().coerceAtLeast(1)
            val layout = measurer.measure(
                text = block.text,
                style = styleFor(block.kind),
                constraints = Constraints(maxWidth = width),
            )
            MeasuredBlock(layout, charCursor, indent).also { charCursor += block.text.length }
        }
    }

    /**
     * Greedy line packing with book rules: no orphans (a paragraph's first line
     * alone at a page bottom) and no widows (its last line alone at a page top).
     * Both are soft constraints — resolved by nudging lines to the next page,
     * never by reflowing text.
     */
    private fun packIntoPages(measured: List<MeasuredBlock>): List<ReaderPage> {
        val pages = mutableListOf<ReaderPage>()
        var slices = mutableListOf<PageSlice>()
        var y = 0f

        fun closePage() {
            if (slices.isEmpty()) return
            val first = slices.first()
            val last = slices.last()
            pages.add(
                ReaderPage(
                    slices = slices,
                    startChar = measured[first.blockIndex].charStart +
                        measured[first.blockIndex].layout.getLineStart(first.firstLine),
                    endChar = measured[last.blockIndex].charStart +
                        measured[last.blockIndex].layout.getLineEnd(last.lastLine),
                ),
            )
            slices = mutableListOf()
            y = 0f
        }

        measured.forEachIndexed { blockIndex, block ->
            if (slices.isNotEmpty()) y += blockSpacingPx
            val lineCount = block.layout.lineCount
            var line = 0
            while (line < lineCount) {
                val lineTop = block.layout.getLineTop(line)
                val remaining = contentHeightPx - y
                // Furthest line whose bottom still fits in the remaining space.
                var take = line - 1
                var probe = line
                while (probe < lineCount && block.layout.getLineBottom(probe) - lineTop <= remaining) {
                    take = probe
                    probe++
                }
                if (take < line) {
                    if (slices.isEmpty()) {
                        take = line // single line taller than the page — force progress
                    } else {
                        closePage()
                        continue
                    }
                }
                // Widow rule: if exactly one line would carry over, carry two.
                if (take < lineCount - 1 && lineCount - 1 - take == 1 && take > line) take--
                // Orphan rule: don't leave only the paragraph's first line behind.
                if (line == 0 && take == 0 && lineCount > 1 && slices.isNotEmpty()) {
                    closePage()
                    continue
                }
                slices.add(PageSlice(blockIndex, line, take, y))
                y += block.layout.getLineBottom(take) - lineTop
                line = take + 1
                if (line < lineCount) closePage()
            }
        }
        closePage()
        return pages
    }

    /** Vellum-owned text styles per block role, in the reader's chosen font. */
    private fun styleFor(kind: BlockKind): TextStyle {
        val bodySize = typography.fontSizeSp
        val lineHeight = (typography.fontSizeSp * typography.lineHeightMultiplier)
        return when (kind) {
            BlockKind.BODY -> TextStyle(
                fontFamily = fontFamily,
                fontSize = bodySize.sp,
                lineHeight = lineHeight.sp,
                textAlign = TextAlign.Justify,
                hyphens = Hyphens.Auto,
                lineBreak = LineBreak.Paragraph,
            )
            BlockKind.HEADING_1 -> headingStyle(bodySize * 1.55f)
            BlockKind.HEADING_2 -> headingStyle(bodySize * 1.3f)
            BlockKind.HEADING_3 -> headingStyle(bodySize * 1.12f)
            BlockKind.QUOTE -> TextStyle(
                fontFamily = fontFamily,
                fontSize = (bodySize * 0.95f).sp,
                lineHeight = (lineHeight * 0.95f).sp,
                fontStyle = FontStyle.Italic,
                lineBreak = LineBreak.Paragraph,
                hyphens = Hyphens.Auto,
            )
        }
    }

    private fun headingStyle(sizeSp: Float) = TextStyle(
        fontFamily = fontFamily,
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * 1.3f).sp,
        fontWeight = FontWeight.SemiBold,
    )
}
