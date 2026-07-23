package app.vellum.reader.shared

data class SharedAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val userId: String,
    val email: String,
)

data class SharedLibrarySummary(
    val uuid: String,
    val name: String,
    val description: String?,
    val role: SharedLibraryRole,
    val memberCount: Int,
    val publicationCount: Int,
)

enum class SharedLibraryRole(val wireName: String) {
    OWNER("owner"),
    LIBRARIAN("librarian"),
    READER("reader");

    val canPublish: Boolean get() = this != READER

    companion object {
        fun fromWire(value: String): SharedLibraryRole =
            entries.firstOrNull { it.wireName == value } ?: READER
    }
}

data class SharedPublication(
    val uuid: String,
    val libraryUuid: String,
    val title: String,
    val author: String,
    val format: String,
    val category: String?,
    val genres: List<String>,
    val seriesName: String?,
    val seriesIndex: Float?,
    val sha256: String,
    val sizeBytes: Long,
    val createdAt: String,
    val status: String,
)

data class SharedInvitation(
    val code: String,
    val libraryName: String,
    val inviteeEmail: String,
    val role: SharedLibraryRole,
    val expiresAt: String,
) {
    val deepLink: String get() = "vellum://shared/join?code=$code"
}

data class SharedTransferProgress(
    val publicationUuid: String,
    val title: String,
    val fraction: Float?,
    val message: String,
)

sealed interface SharedAccountState {
    data object Loading : SharedAccountState
    data object SignedOut : SharedAccountState
    data class SignedIn(val session: SharedAuthSession) : SharedAccountState
}
