package app.vellum.reader.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import app.vellum.reader.reader.layout.PaginatedChapter
import app.vellum.reader.reader.layout.ReaderPage

/**
 * REMOVAL CANDIDATE (Phase 3): superseded by ReaderContentRenderer.drawSpread,
 * which unifies live rendering and offscreen capture for the curl shader.
 * Kept one phase for reference; delete if nothing reclaims it by Phase 4.
 */
@Composable
fun PageCanvas(
    chapter: PaginatedChapter,
    page: ReaderPage,
    inkColor: Color,
    horizontalMarginPx: Float,
    verticalMarginPx: Float,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        page.slices.forEach { slice ->
            val block = chapter.measured[slice.blockIndex]
            val sliceTop = block.layout.getLineTop(slice.firstLine)
            val sliceHeight = block.layout.getLineBottom(slice.lastLine) - sliceTop
            clipRect(
                left = 0f,
                top = verticalMarginPx + slice.y,
                right = size.width,
                bottom = verticalMarginPx + slice.y + sliceHeight,
            ) {
                drawText(
                    textLayoutResult = block.layout,
                    color = inkColor,
                    topLeft = Offset(
                        x = horizontalMarginPx + block.indentPx,
                        y = verticalMarginPx + slice.y - sliceTop,
                    ),
                )
            }
        }
    }
}
