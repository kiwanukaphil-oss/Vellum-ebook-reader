package app.vellum.reader.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.vellum.reader.R

/**
 * "Ink & Linen" — the app-chrome design system. Cool near-white linen
 * surfaces with a deep ink-blue accent for interactive controls; rust is
 * reserved for reading signals (progress, chapter marks) so the two never
 * compete. Book pages keep their own warm ReadingTheme palettes — chrome
 * stays cool precisely so the page feels like the warm object at the center.
 */
object VellumPalette {
    val InkBlue = Color(0xFF1F3A5F)
    val Rust = Color(0xFFB85C38)
    val Gold = Color(0xFFB08D4B)
    val Linen = Color(0xFFFCFCFB)
    val Ink = Color(0xFF232326)
}

private val LightColors = lightColorScheme(
    primary = VellumPalette.InkBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6F0),
    onPrimaryContainer = Color(0xFF14263F),
    secondary = VellumPalette.Rust,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6E4DA),
    onSecondaryContainer = Color(0xFF7E3D24),
    tertiary = VellumPalette.Gold,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2E9D2),
    onTertiaryContainer = Color(0xFF5A4620),
    background = VellumPalette.Linen,
    onBackground = VellumPalette.Ink,
    surface = VellumPalette.Linen,
    onSurface = VellumPalette.Ink,
    surfaceVariant = Color(0xFFF4F4F2),
    onSurfaceVariant = Color(0xFF5B5B62),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7F5),
    surfaceContainer = Color(0xFFF4F4F2),
    surfaceContainerHigh = Color(0xFFF0F0EE),
    surfaceContainerHighest = Color(0xFFEAEAE8),
    outline = Color(0xFF9A9AA1),
    outlineVariant = Color(0xFFEAEAE8),
    inverseSurface = Color(0xFF2E2E32),
    inverseOnSurface = Color(0xFFF4F4F2),
    inversePrimary = Color(0xFF9DB8D6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB8D6),
    onPrimary = Color(0xFF102338),
    primaryContainer = Color(0xFF2C4462),
    onPrimaryContainer = Color(0xFFDCE6F0),
    secondary = Color(0xFFD98B66),
    onSecondary = Color(0xFF3F1E10),
    secondaryContainer = Color(0xFF6C3520),
    onSecondaryContainer = Color(0xFFF6E4DA),
    tertiary = Color(0xFFCFAE6E),
    onTertiary = Color(0xFF3B2D10),
    tertiaryContainer = Color(0xFF564322),
    onTertiaryContainer = Color(0xFFF2E9D2),
    background = Color(0xFF17171A),
    onBackground = Color(0xFFE2E1DD),
    surface = Color(0xFF17171A),
    onSurface = Color(0xFFE2E1DD),
    surfaceVariant = Color(0xFF26262A),
    onSurfaceVariant = Color(0xFFA5A5AC),
    surfaceContainerLowest = Color(0xFF101013),
    surfaceContainerLow = Color(0xFF1C1C20),
    surfaceContainer = Color(0xFF212125),
    surfaceContainerHigh = Color(0xFF26262A),
    surfaceContainerHighest = Color(0xFF2C2C31),
    outline = Color(0xFF6E6E76),
    outlineVariant = Color(0xFF2E2E33),
    inverseSurface = Color(0xFFE2E1DD),
    inverseOnSurface = VellumPalette.Ink,
    inversePrimary = VellumPalette.InkBlue,
)

/** Display serif for screen titles and big numerals. */
val Fraunces = FontFamily(
    Font(R.font.fraunces, FontWeight.Normal),
    Font(R.font.fraunces, FontWeight.Medium),
    Font(R.font.fraunces, FontWeight.SemiBold),
    Font(R.font.fraunces, FontWeight.Bold),
)

/** Mono for metadata, labels, and buttons — the editorial microtext voice. */
val PlexMono = FontFamily(
    Font(R.font.ibmplexmono, FontWeight.Normal),
    Font(R.font.ibmplexmono_medium, FontWeight.Medium),
)

/** Body serif for chrome prose; the same face readers see on the page. */
private val LiterataChrome = FontFamily(
    Font(R.font.literata, FontWeight.Normal),
    Font(R.font.literata, FontWeight.Medium),
    Font(R.font.literata, FontWeight.SemiBold),
    Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
)

/**
 * Three-voice type scale: Fraunces for display/headline/title, Literata for
 * body prose, IBM Plex Mono for labels (section headers, captions, buttons).
 */
private val VellumTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        displayMedium = base.displayMedium.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        displaySmall = base.displaySmall.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = PlexMono, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp),
        bodyLarge = base.bodyLarge.copy(fontFamily = LiterataChrome),
        bodyMedium = base.bodyMedium.copy(fontFamily = LiterataChrome),
        bodySmall = base.bodySmall.copy(fontFamily = LiterataChrome),
        labelLarge = base.labelLarge.copy(fontFamily = PlexMono, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
        labelMedium = base.labelMedium.copy(fontFamily = PlexMono, letterSpacing = 0.6.sp),
        labelSmall = base.labelSmall.copy(fontFamily = PlexMono, letterSpacing = 0.8.sp),
    )
}

@Composable
fun VellumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = VellumTypography,
        content = content,
    )
}
