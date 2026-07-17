package app.vellum.reader.core.model

/**
 * User-adjustable typography. All of it feeds the paginator, so any change
 * triggers a re-layout that preserves the reading position by character offset.
 */
data class TypographySettings(
    val fontId: String = "literata",
    val fontSizeSp: Float = 19f,
    val lineHeightMultiplier: Float = 1.55f,
    val pageMarginDp: Float = 26f,
    val paragraphSpacingDp: Float = 7f,
)
