package app.vellum.reader.librarian

data class AiEnrichmentRequest(
    val title: String,
    val author: String,
    val fileName: String,
    val format: String,
    val currentCategory: String?,
    val currentGenres: List<String>,
    val excerpt: String,
)

data class AiEnrichmentResult(
    val title: String,
    val author: String,
    val category: String,
    val genres: List<String>,
    val seriesName: String?,
    val seriesIndex: Float?,
    val confidence: Float,
    val explanation: String,
    val model: String,
    val taxonomyVersion: String,
)

sealed interface AiOrganizeOutcome {
    data object Applied : AiOrganizeOutcome
    data object NeedsReview : AiOrganizeOutcome
    data object AlreadyOrganized : AiOrganizeOutcome
    data object SignInRequired : AiOrganizeOutcome
    data class Failed(val message: String) : AiOrganizeOutcome
}

object VellumAiTaxonomy {
    const val AUTO_APPLY_CONFIDENCE = 0.88f

    val genres = listOf(
        "Biography & Memoir", "Classics", "Essays", "Fantasy", "Graphic Memoir",
        "Graphic Novel", "Historical", "History", "Literary", "Mystery & Thriller",
        "Nature", "Philosophy", "Poetry", "Romance", "Science", "Science Fiction",
        "Society & Politics",
    )
}
