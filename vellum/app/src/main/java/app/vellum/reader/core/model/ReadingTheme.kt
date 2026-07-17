package app.vellum.reader.core.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * A reading environment: page background + text ink. The four Phase 1 presets
 * from the scope doc; the time-based "evening" mode arrives in Phase 2.
 */
data class ReadingTheme(
    val id: String,
    val label: String,
    val pageColor: Color,
    val inkColor: Color,
    val isDark: Boolean,
) {
    /**
     * Evening mode: shifts the page toward candlelight amber by [warmth] (0..1).
     * Light themes warm the paper; dark themes warm the ink instead, since
     * their page color is the point (especially true black on OLED).
     */
    fun warmed(warmth: Float): ReadingTheme {
        if (warmth <= 0f) return this
        return if (isDark) {
            copy(inkColor = lerp(inkColor, Color(0xFFCDAF82), 0.45f * warmth))
        } else {
            copy(
                pageColor = lerp(pageColor, Color(0xFFF3DFB8), 0.5f * warmth),
                inkColor = lerp(inkColor, Color(0xFF4A3620), 0.3f * warmth),
            )
        }
    }

    companion object {
        val PaperWhite = ReadingTheme("paper", "Paper", Color(0xFFFBF8F1), Color(0xFF2E2A24), isDark = false)
        val Sepia = ReadingTheme("sepia", "Sepia", Color(0xFFF6ECDA), Color(0xFF5B4636), isDark = false)
        val Gray = ReadingTheme("gray", "Gray", Color(0xFF33343A), Color(0xFFC9CAD1), isDark = true)
        val TrueBlack = ReadingTheme("black", "Black", Color(0xFF000000), Color(0xFFB9B5AE), isDark = true)

        /** Accessibility: maximum contrast, no warmth. */
        val HighContrast = ReadingTheme("contrast", "Contrast", Color(0xFFFFFFFF), Color(0xFF000000), isDark = false)

        val All = listOf(PaperWhite, Sepia, Gray, TrueBlack, HighContrast)

        fun byId(id: String): ReadingTheme = All.firstOrNull { it.id == id } ?: PaperWhite
    }
}
