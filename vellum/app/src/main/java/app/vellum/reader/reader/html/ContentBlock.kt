package app.vellum.reader.reader.html

import androidx.compose.ui.text.AnnotatedString

/** Block-level roles the Phase 1 renderer distinguishes. */
enum class BlockKind { BODY, HEADING_1, HEADING_2, HEADING_3, QUOTE }

/** One block-level run of styled text — the unit the paginator measures. */
data class ContentBlock(
    val text: AnnotatedString,
    val kind: BlockKind,
)
