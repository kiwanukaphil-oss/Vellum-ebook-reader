package app.vellum.reader.core.fonts

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import app.vellum.reader.R

/**
 * The curated Phase 2 font set — five OFL book faces bundled as variable TTFs
 * (Compose derives the weight axis from FontWeight automatically), plus the
 * platform serif as a zero-cost fallback.
 */
object VellumFonts {

    data class BookFont(val id: String, val label: String, val family: FontFamily)

    private fun variableFamily(regularRes: Int, italicRes: Int): FontFamily = FontFamily(
        Font(regularRes, FontWeight.Normal),
        Font(regularRes, FontWeight.Medium),
        Font(regularRes, FontWeight.SemiBold),
        Font(regularRes, FontWeight.Bold),
        Font(italicRes, FontWeight.Normal, FontStyle.Italic),
        Font(italicRes, FontWeight.Bold, FontStyle.Italic),
    )

    val All: List<BookFont> by lazy {
        listOf(
            BookFont("literata", "Literata", variableFamily(R.font.literata, R.font.literata_italic)),
            BookFont("crimsonpro", "Crimson Pro", variableFamily(R.font.crimsonpro, R.font.crimsonpro_italic)),
            BookFont("vollkorn", "Vollkorn", variableFamily(R.font.vollkorn, R.font.vollkorn_italic)),
            BookFont("alegreya", "Alegreya", variableFamily(R.font.alegreya, R.font.alegreya_italic)),
            BookFont("bitter", "Bitter", variableFamily(R.font.bitter, R.font.bitter_italic)),
            // Accessibility face: Braille Institute's high-legibility design.
            BookFont("atkinson", "Atkinson Hyperlegible", FontFamily(Font(R.font.atkinson_hyperlegible))),
            BookFont("system", "System serif", FontFamily.Serif),
        )
    }

    fun byId(id: String): BookFont = All.firstOrNull { it.id == id } ?: All.first()
}
