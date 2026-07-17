package app.vellum.reader.core.model

import androidx.compose.ui.graphics.Color

/** The four highlight inks; drawn under text at partial alpha. */
object HighlightColors {
    data class HighlightColor(val id: String, val color: Color)

    val All = listOf(
        HighlightColor("amber", Color(0xFFF0D264)),
        HighlightColor("sage", Color(0xFF9FC98F)),
        HighlightColor("sky", Color(0xFF8FB8DE)),
        HighlightColor("rose", Color(0xFFE39EB1)),
    )

    fun byId(id: String): HighlightColor = All.firstOrNull { it.id == id } ?: All.first()
}
