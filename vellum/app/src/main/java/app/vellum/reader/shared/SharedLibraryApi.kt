package app.vellum.reader.shared

import android.net.Uri
import android.util.Base64
import app.vellum.reader.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SharedLibraryException(message: String) : Exception(message)

/**
 * Small provider-neutral client around Supabase Auth/PostgREST and the private
 * object Worker. Only the publishable key ships in the app; database RLS and
 * server-side membership checks remain the authority.
 */
class SharedLibraryApi(
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/'),
    private val publishableKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
    private val booksApiUrl: String = BuildConfig.SHARED_BOOKS_API_URL.trimEnd('/'),
) {
    val configured: Boolean
        get() = supabaseUrl.startsWith("https://") &&
            publishableKey.isNotBlank() &&
            booksApiUrl.startsWith("https://")

    suspend fun sendMagicLink(email: String) = withContext(Dispatchers.IO) {
        requireConfigured()
        val redirect = URLEncoder.encode(AUTH_REDIRECT, StandardCharsets.UTF_8.name())
        requestJson(
            url = "$supabaseUrl/auth/v1/otp?redirect_to=$redirect",
            method = "POST",
            body = JSONObject()
                .put("email", email.trim().lowercase())
                .put("create_user", true),
        )
    }

    fun sessionFromCallback(uri: Uri): SharedAuthSession {
        val fragment = uri.fragment.orEmpty()
        val fragmentUri = Uri.parse("https://vellum.invalid/?$fragment")
        val accessToken = fragmentUri.getQueryParameter("access_token")
            ?: uri.getQueryParameter("access_token")
            ?: throw SharedLibraryException(
                uri.getQueryParameter("error_description")
                    ?: fragmentUri.getQueryParameter("error_description")
                    ?: "That sign-in link is invalid or has expired.",
            )
        val refreshToken = fragmentUri.getQueryParameter("refresh_token")
            ?: uri.getQueryParameter("refresh_token")
            ?: throw SharedLibraryException("The sign-in response did not include a refresh token.")
        val expiresIn = fragmentUri.getQueryParameter("expires_in")?.toLongOrNull() ?: 3600L
        val claims = jwtClaims(accessToken)
        return SharedAuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000L + expiresIn,
            userId = claims.optString("sub"),
            email = claims.optString("email"),
        )
    }

    suspend fun refreshSession(session: SharedAuthSession): SharedAuthSession =
        withContext(Dispatchers.IO) {
            requireConfigured()
            val response = requestJson(
                url = "$supabaseUrl/auth/v1/token?grant_type=refresh_token",
                method = "POST",
                body = JSONObject().put("refresh_token", session.refreshToken),
            )
            parseSession(response)
        }

    suspend fun signOut(accessToken: String) = withContext(Dispatchers.IO) {
        if (!configured) return@withContext
        val connection = openConnection("$supabaseUrl/auth/v1/logout", "POST", accessToken)
        try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    suspend fun libraries(accessToken: String): List<SharedLibrarySummary> =
        rpcArray("list_my_libraries", JSONObject(), accessToken).map { row ->
            SharedLibrarySummary(
                uuid = row.getString("library_uuid"),
                name = row.getString("library_name"),
                description = row.optNullableString("library_description"),
                role = SharedLibraryRole.fromWire(row.getString("member_role")),
                memberCount = row.optInt("member_count"),
                publicationCount = row.optInt("publication_count"),
            )
        }

    suspend fun publications(
        libraryUuid: String,
        query: String,
        accessToken: String,
    ): List<SharedPublication> =
        rpcArray(
            "list_library_publications",
            JSONObject()
                .put("p_library_id", libraryUuid)
                .put("p_query", query.trim()),
            accessToken,
        ).map(::publicationFromJson)

    suspend fun createLibrary(
        name: String,
        description: String?,
        accessToken: String,
    ): SharedLibrarySummary {
        val row = rpcArray(
            "create_shared_library",
            JSONObject()
                .put("p_name", name.trim())
                .put("p_description", description?.trim()?.ifBlank { null }),
            accessToken,
        ).firstOrNull() ?: throw SharedLibraryException("The library could not be created.")
        return SharedLibrarySummary(
            uuid = row.getString("library_uuid"),
            name = row.getString("library_name"),
            description = row.optNullableString("library_description"),
            role = SharedLibraryRole.OWNER,
            memberCount = 1,
            publicationCount = 0,
        )
    }

    suspend fun createInvitation(
        libraryUuid: String,
        inviteeEmail: String,
        role: SharedLibraryRole,
        accessToken: String,
    ): SharedInvitation {
        val row = rpcArray(
            "create_library_invitation",
            JSONObject()
                .put("p_library_id", libraryUuid)
                .put("p_invitee_email", inviteeEmail.trim().lowercase())
                .put("p_role", role.wireName),
            accessToken,
        ).firstOrNull() ?: throw SharedLibraryException("The invitation could not be created.")
        return SharedInvitation(
            code = row.getString("invitation_code"),
            libraryName = row.getString("library_name"),
            inviteeEmail = row.getString("invitee_email"),
            role = SharedLibraryRole.fromWire(row.getString("invited_role")),
            expiresAt = row.getString("expires_at"),
        )
    }

    suspend fun acceptInvitation(code: String, accessToken: String): SharedLibrarySummary {
        val row = rpcArray(
            "accept_library_invitation",
            JSONObject().put("p_invitation_code", code.trim()),
            accessToken,
        ).firstOrNull() ?: throw SharedLibraryException("That invitation is no longer available.")
        return SharedLibrarySummary(
            uuid = row.getString("library_uuid"),
            name = row.getString("library_name"),
            description = row.optNullableString("library_description"),
            role = SharedLibraryRole.fromWire(row.getString("member_role")),
            memberCount = row.optInt("member_count"),
            publicationCount = row.optInt("publication_count"),
        )
    }

    suspend fun beginPublication(
        libraryUuid: String,
        title: String,
        author: String,
        format: String,
        category: String?,
        genres: List<String>,
        seriesName: String?,
        seriesIndex: Float?,
        originalFileName: String,
        sha256: String,
        sizeBytes: Long,
        accessToken: String,
    ): String {
        val row = rpcArray(
            "begin_library_publication",
            JSONObject()
                .put("p_library_id", libraryUuid)
                .put("p_title", title)
                .put("p_author", author)
                .put("p_format", format)
                .put("p_category", category)
                .put("p_genres", JSONArray(genres))
                .put("p_series_name", seriesName)
                .put("p_series_index", seriesIndex)
                .put("p_original_file_name", originalFileName)
                .put("p_sha256", sha256)
                .put("p_size_bytes", sizeBytes),
            accessToken,
        ).firstOrNull() ?: throw SharedLibraryException("The upload could not be prepared.")
        return row.getString("publication_uuid")
    }

    suspend fun uploadPublication(
        libraryUuid: String,
        publicationUuid: String,
        file: File,
        sha256: String,
        cover: File?,
        accessToken: String,
    ) = withContext(Dispatchers.IO) {
        requireConfigured()
        if (cover?.isFile == true) {
            upload(
                path = "/v1/libraries/$libraryUuid/publications/$publicationUuid/cover",
                file = cover,
                contentType = "image/webp",
                sha256 = sha256(cover),
                accessToken = accessToken,
            )
        }
        upload(
            path = "/v1/libraries/$libraryUuid/publications/$publicationUuid/file",
            file = file,
            contentType = contentTypeFor(file.extension),
            sha256 = sha256,
            accessToken = accessToken,
        )
    }

    suspend fun downloadPublication(
        publication: SharedPublication,
        destination: File,
        accessToken: String,
    ) = withContext(Dispatchers.IO) {
        requireConfigured()
        val connection = openConnection(
            "$booksApiUrl/v1/libraries/${publication.libraryUuid}/publications/${publication.uuid}/file",
            "GET",
            accessToken,
        )
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw responseException(connection, status)
            destination.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            val actual = sha256(destination)
            if (!actual.equals(publication.sha256, ignoreCase = true)) {
                destination.delete()
                throw SharedLibraryException("The downloaded file failed its integrity check. Please try again.")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun coverUrl(libraryUuid: String, publicationUuid: String): String =
        "$booksApiUrl/v1/libraries/$libraryUuid/publications/$publicationUuid/cover"

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun rpcArray(
        function: String,
        body: JSONObject,
        accessToken: String,
    ): List<JSONObject> = withContext(Dispatchers.IO) {
        val response = requestJson(
            "$supabaseUrl/rest/v1/rpc/$function",
            "POST",
            body,
            accessToken,
        )
        when (response) {
            is JSONArray -> (0 until response.length()).map { response.getJSONObject(it) }
            is JSONObject -> listOf(response)
            else -> emptyList()
        }
    }

    private fun requestJson(
        url: String,
        method: String,
        body: JSONObject,
        accessToken: String? = null,
    ): Any {
        val connection = openConnection(url, method, accessToken)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(body.toString()) }
        return try {
            val status = connection.responseCode
            val text = if (status in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                throw responseException(connection, status)
            }
            if (text.isBlank()) JSONObject() else runCatching { JSONArray(text) }
                .getOrElse { JSONObject(text) }
        } finally {
            connection.disconnect()
        }
    }

    private fun upload(
        path: String,
        file: File,
        contentType: String,
        sha256: String,
        accessToken: String,
    ) {
        val connection = openConnection("$booksApiUrl$path", "PUT", accessToken)
        connection.setRequestProperty("Content-Type", contentType)
        connection.setRequestProperty("X-Vellum-Sha256", sha256)
        connection.setRequestProperty("X-Vellum-File-Name", Uri.encode(file.name))
        connection.setFixedLengthStreamingMode(file.length())
        connection.doOutput = true
        try {
            file.inputStream().buffered().use { input ->
                connection.outputStream.buffered().use { output -> input.copyTo(output) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw responseException(connection, status)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        url: String,
        method: String,
        accessToken: String?,
    ): HttpURLConnection = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 20_000
        readTimeout = 120_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("apikey", publishableKey)
        if (accessToken != null) setRequestProperty("Authorization", "Bearer $accessToken")
    }

    private fun responseException(connection: HttpURLConnection, status: Int): SharedLibraryException {
        val text = runCatching {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrDefault("")
        val message = runCatching {
            val json = JSONObject(text)
            json.optString("message").ifBlank { json.optString("error_description") }
                .ifBlank { json.optString("error") }
        }.getOrDefault("")
        return SharedLibraryException(
            message.ifBlank {
                when (status) {
                    401 -> "Please sign in again."
                    403 -> "You do not have permission to do that."
                    404 -> "That shared item is no longer available."
                    409 -> "That book is already in this shared library."
                    413 -> "That file is too large for the household library."
                    else -> "The shared library could not be reached ($status)."
                }
            },
        )
    }

    private fun parseSession(response: Any): SharedAuthSession {
        val json = response as? JSONObject
            ?: throw SharedLibraryException("The refreshed session was invalid.")
        val access = json.getString("access_token")
        val claims = jwtClaims(access)
        return SharedAuthSession(
            accessToken = access,
            refreshToken = json.optString("refresh_token").ifBlank {
                throw SharedLibraryException("The refreshed session did not include a refresh token.")
            },
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000L + json.optLong("expires_in", 3600L),
            userId = claims.optString("sub"),
            email = claims.optString("email"),
        )
    }

    private fun jwtClaims(token: String): JSONObject {
        val payload = token.split('.').getOrNull(1)
            ?: throw SharedLibraryException("The sign-in response was malformed.")
        val decoded = Base64.decode(
            payload,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return JSONObject(String(decoded, StandardCharsets.UTF_8))
    }

    private fun publicationFromJson(row: JSONObject): SharedPublication {
        val genres = row.optJSONArray("genres") ?: JSONArray()
        return SharedPublication(
            uuid = row.getString("publication_uuid"),
            libraryUuid = row.getString("library_uuid"),
            title = row.getString("title"),
            author = row.getString("author"),
            format = row.getString("format"),
            category = row.optNullableString("category"),
            genres = (0 until genres.length()).mapNotNull { genres.optString(it).takeIf(String::isNotBlank) },
            seriesName = row.optNullableString("series_name"),
            seriesIndex = if (row.isNull("series_index")) null else row.optDouble("series_index").toFloat(),
            sha256 = row.getString("sha256"),
            sizeBytes = row.optLong("size_bytes"),
            createdAt = row.optString("created_at"),
            status = row.optString("status"),
        )
    }

    private fun contentTypeFor(extension: String): String = when (extension.lowercase()) {
        "epub" -> "application/epub+zip"
        "pdf" -> "application/pdf"
        "cbz" -> "application/vnd.comicbook+zip"
        "cbr" -> "application/vnd.comicbook-rar"
        else -> "application/octet-stream"
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf(String::isNotBlank) else null

    private fun requireConfigured() {
        if (!configured) {
            throw SharedLibraryException("Shared Libraries are not configured in this build yet.")
        }
    }

    private companion object {
        const val AUTH_REDIRECT = "vellum://auth/callback"
    }
}
