package app.vellum.reader.reader.html

import androidx.compose.ui.text.AnnotatedString

/** Block-level roles the renderer distinguishes. */
enum class BlockKind { BODY, HEADING_1, HEADING_2, HEADING_3, QUOTE, IMAGE }

/**
 * One block-level run of styled text — the unit the paginator measures.
 * IMAGE blocks carry an empty text (so character offsets, FTS bodies, and
 * annotations are unaffected) plus the publication-relative [imageSrc].
 */
data class ContentBlock(
    val text: AnnotatedString,
    val kind: BlockKind,
    val imageSrc: String? = null,
)
