package app.vellum.reader.reader.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import app.vellum.reader.core.model.ReadingTheme
import app.vellum.reader.reader.layout.PaginatedChapter
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * One draw routine for everything the page shows — background, one or two
 * page columns, paper grain, page-edge stacks. Used by the live reader canvas
 * AND by offscreen bitmap capture for the curl shader, so the curling page is
 * pixel-identical to the resting page.
 */
object ReaderContentRenderer {

    /** 96px tileable speckle, generated once; alpha applied at draw time. */
    val paperGrain: ImageBitmap by lazy {
        val size = 96
        val bitmap = ImageBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = androidx.compose.ui.graphics.Paint()
        val random = Random(42)
        repeat(900) {
            val shade = 0.86f + random.nextFloat() * 0.14f
            paint.color = Color(shade, shade, shade, 1f)
            val x = random.nextFloat() * size
            val y = random.nextFloat() * size
            canvas.drawRect(x, y, x + 1.5f, y + 1.5f, paint)
        }
        bitmap
    }

    /** A char range to wash with color under the text (highlight or selection). */
    data class HighlightSpan(val startChar: Int, val endChar: Int, val color: Color)

    data class SpreadSpec(
        val paginated: PaginatedChapter,
        val startPage: Int,
        val columns: Int,
        val theme: ReadingTheme,
        val marginPx: Float,
        val contentWidthPx: Int,
        val paperTexture: Boolean,
        val pageEdges: Boolean,
        /** 0..1 through the whole book, drives the page-edge thickness. */
        val bookProgress: Float,
        val highlights: List<HighlightSpan> = emptyList(),
        val selection: HighlightSpan? = null,
        val selectionHandleRadiusPx: Float = 16f,
        /** Chapter images keyed by publication-relative src. */
        val images: Map<String, ImageBitmap> = emptyMap(),
    )

    fun DrawScope.drawSpread(spec: SpreadSpec) {
        drawRect(spec.theme.pageColor)

        for (column in 0 until spec.columns) {
            val page = spec.paginated.pages.getOrNull(spec.startPage + column) ?: continue
            val xOffset = spec.marginPx + column * (spec.contentWidthPx + spec.marginPx)
            page.slices.forEach { slice ->
                val block = spec.paginated.measured[slice.blockIndex]
                if (block.imageSrc != null) {
                    drawImageSlice(spec, block, slice, xOffset)
                    return@forEach
                }
                val sliceTop = block.layout.getLineTop(slice.firstLine)
                val sliceHeight = block.layout.getLineBottom(slice.lastLine) - sliceTop
                clipRect(
                    left = xOffset,
                    top = spec.marginPx + slice.y,
                    right = xOffset + spec.contentWidthPx,
                    bottom = spec.marginPx + slice.y + sliceHeight,
                ) {
                    val origin = Offset(
                        x = xOffset + block.indentPx,
                        y = spec.marginPx + slice.y - sliceTop,
                    )
                    // Color washes go under the ink, like a real highlighter.
                    spec.highlights.forEach { span ->
                        drawSpanInSlice(spec, slice, span, origin, alpha = 0.4f)
                    }
                    spec.selection?.let { span ->
                        drawSpanInSlice(spec, slice, span, origin, alpha = 0.35f)
                    }
                    drawText(
                        textLayoutResult = block.layout,
                        color = spec.theme.inkColor,
                        topLeft = origin,
                    )
                }
            }
            spec.selection?.let { drawSelectionHandles(spec, page, xOffset, it) }
        }

        // Paper grain: multiply blend so it darkens imperceptibly; pointless on
        // dark themes (and wrong on OLED true black), so light themes only.
        if (spec.paperTexture && !spec.theme.isDark) {
            drawRect(
                brush = ShaderBrush(ImageShader(paperGrain, TileMode.Repeated, TileMode.Repeated)),
                alpha = 0.05f,
                blendMode = androidx.compose.ui.graphics.BlendMode.Multiply,
            )
        }

        // Page-edge stacks: hairlines hinting at the unread block (right) and
        // the read block (left), like looking at a closed book's fore-edge.
        if (spec.pageEdges) {
            val maxBand = spec.marginPx * 0.35f
            drawEdgeStack(
                edgeX = 0f,
                bandWidth = maxBand * spec.bookProgress,
                leftEdge = true,
                ink = spec.theme.inkColor,
            )
            drawEdgeStack(
                edgeX = size.width,
                bandWidth = maxBand * (1f - spec.bookProgress),
                leftEdge = false,
                ink = spec.theme.inkColor,
            )
        }
    }

    /** Draws one image block centered in its column at the packed offset. */
    private fun DrawScope.drawImageSlice(
        spec: SpreadSpec,
        block: app.vellum.reader.reader.layout.MeasuredBlock,
        slice: app.vellum.reader.reader.layout.PageSlice,
        xOffset: Float,
    ) {
        val drawn = block.imageSize ?: return
        val bitmap = spec.images[block.imageSrc] ?: return
        val dstWidth = drawn.width.roundToInt().coerceAtLeast(1)
        val dstHeight = drawn.height.roundToInt().coerceAtLeast(1)
        drawImage(
            image = bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset(
                x = (xOffset + (spec.contentWidthPx - dstWidth) / 2f).roundToInt(),
                y = (spec.marginPx + slice.y).roundToInt(),
            ),
            dstSize = IntSize(dstWidth, dstHeight),
            filterQuality = FilterQuality.Medium,
        )
    }

    /** Draws the part of [span] that falls inside one slice, block-locally. */
    private fun DrawScope.drawSpanInSlice(
        spec: SpreadSpec,
        slice: app.vellum.reader.reader.layout.PageSlice,
        span: HighlightSpan,
        origin: Offset,
        alpha: Float,
    ) {
        val block = spec.paginated.measured[slice.blockIndex]
        val sliceStart = block.layout.getLineStart(slice.firstLine)
        val sliceEnd = block.layout.getLineEnd(slice.lastLine)
        val localStart = (span.startChar - block.charStart).coerceAtLeast(sliceStart)
        val localEnd = (span.endChar - block.charStart).coerceAtMost(sliceEnd)
        if (localStart >= localEnd) return
        val path = block.layout.getPathForRange(localStart, localEnd)
        path.translate(origin)
        drawPath(path, color = span.color, alpha = alpha)
    }

    /** Round drag handles at the selection's visible start and end. */
    private fun DrawScope.drawSelectionHandles(
        spec: SpreadSpec,
        page: app.vellum.reader.reader.layout.ReaderPage,
        xOffset: Float,
        selection: HighlightSpan,
    ) {
        listOf(selection.startChar, selection.endChar).forEach { edge ->
            page.slices.forEach { slice ->
                val block = spec.paginated.measured[slice.blockIndex]
                val sliceStart = block.charStart + block.layout.getLineStart(slice.firstLine)
                val sliceEnd = block.charStart + block.layout.getLineEnd(slice.lastLine)
                if (edge in sliceStart..sliceEnd) {
                    val local = (edge - block.charStart).coerceIn(0, block.layout.layoutInput.text.length)
                    val cursor = block.layout.getCursorRect(local)
                    val sliceTop = block.layout.getLineTop(slice.firstLine)
                    drawCircle(
                        color = selection.color,
                        radius = spec.selectionHandleRadiusPx,
                        center = Offset(
                            x = xOffset + block.indentPx + cursor.left,
                            y = spec.marginPx + slice.y - sliceTop + cursor.bottom + spec.selectionHandleRadiusPx * 0.6f,
                        ),
                    )
                }
            }
        }
    }

    private fun DrawScope.drawEdgeStack(edgeX: Float, bandWidth: Float, leftEdge: Boolean, ink: Color) {
        val lines = min(5, (bandWidth / 3f).toInt())
        for (i in 1..lines) {
            val x = if (leftEdge) edgeX + i * 3f else edgeX - i * 3f
            drawLine(
                color = ink.copy(alpha = 0.05f + 0.02f * (lines - i)),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.2f,
            )
        }
    }

    /** Renders a spread offscreen — the curl shader's page textures. */
    fun renderToBitmap(
        widthPx: Int,
        heightPx: Int,
        density: Density,
        layoutDirection: LayoutDirection,
        spec: SpreadSpec,
    ): ImageBitmap {
        val bitmap = ImageBitmap(widthPx, heightPx)
        CanvasDrawScope().draw(
            density,
            layoutDirection,
            Canvas(bitmap),
            Size(widthPx.toFloat(), heightPx.toFloat()),
        ) {
            drawSpread(spec)
        }
        return bitmap
    }
}
