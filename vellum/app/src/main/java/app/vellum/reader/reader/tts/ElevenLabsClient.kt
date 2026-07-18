package app.vellum.reader.reader.tts

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class ElevenLabsVoice(
    val id: String,
    val name: String,
    val category: String,
    val description: String?,
    val accent: String?,
    val gender: String?,
    val useCase: String?,
)

data class ElevenLabsSubscription(
    val tier: String,
    val creditsUsed: Int,
    val creditLimit: Int,
) {
    val creditsRemaining: Int get() = (creditLimit - creditsUsed).coerceAtLeast(0)
}

data class ElevenLabsGenerationRequest(
    val voiceId: String,
    val modelId: String,
    val text: String,
    val previousText: String?,
    val nextText: String?,
)

data class ElevenLabsGeneration(
    val audioMp3: ByteArray,
    val characterStartSeconds: List<Double>,
)

class ElevenLabsApiException(
    val statusCode: Int,
    message: String,
) : Exception(message)

/** Small dependency-free client for the exact ElevenLabs surfaces Vellum uses. */
class ElevenLabsClient {

    fun getVoices(apiKey: String): List<ElevenLabsVoice> {
        val response = request(
            method = "GET",
            path = "/v2/voices?page_size=100&include_total_count=false",
            apiKey = apiKey,
        )
        val voices = JSONObject(response).getJSONArray("voices")
        return buildList {
            for (index in 0 until voices.length()) {
                val voice = voices.getJSONObject(index)
                val labels = voice.optJSONObject("labels") ?: JSONObject()
                add(
                    ElevenLabsVoice(
                        id = voice.getString("voice_id"),
                        name = voice.optString("name", "Unnamed voice"),
                        category = voice.optString("category", "voice"),
                        description = voice.optNullableString("description"),
                        accent = labels.optNullableString("accent"),
                        gender = labels.optNullableString("gender"),
                        useCase = labels.optNullableString("use_case"),
                    ),
                )
            }
        }.sortedBy { it.name.lowercase() }
    }

    fun getSubscription(apiKey: String): ElevenLabsSubscription {
        val json = JSONObject(request("GET", "/v1/user/subscription", apiKey))
        return ElevenLabsSubscription(
            tier = json.optString("tier", "unknown"),
            creditsUsed = json.optInt("character_count", 0),
            creditLimit = json.optInt("character_limit", 0),
        )
    }

    fun generate(apiKey: String, generation: ElevenLabsGenerationRequest): ElevenLabsGeneration {
        val voiceId = URI(null, null, generation.voiceId, null).rawPath
        val payload = JSONObject()
            .put("text", generation.text)
            .put("model_id", generation.modelId)
        generation.previousText?.takeIf { it.isNotBlank() }?.let { payload.put("previous_text", it) }
        generation.nextText?.takeIf { it.isNotBlank() }?.let { payload.put("next_text", it) }
        val json = JSONObject(
            request(
                method = "POST",
                path = "/v1/text-to-speech/$voiceId/with-timestamps?output_format=mp3_44100_128",
                apiKey = apiKey,
                body = payload.toString(),
            ),
        )
        val alignment = json.optJSONObject("alignment") ?: json.optJSONObject("normalized_alignment")
        val startsJson = alignment?.optJSONArray("character_start_times_seconds")
        val starts = if (startsJson == null) {
            emptyList()
        } else {
            List(startsJson.length()) { startsJson.optDouble(it, 0.0) }
        }
        return ElevenLabsGeneration(
            audioMp3 = Base64.decode(json.getString("audio_base64"), Base64.DEFAULT),
            characterStartSeconds = starts,
        )
    }

    private fun request(
        method: String,
        path: String,
        apiKey: String,
        body: String? = null,
    ): String {
        val connection = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 90_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("xi-api-key", apiKey)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) throw ElevenLabsApiException(status, errorMessage(response, status))
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(response: String, status: Int): String {
        return try {
            val root = JSONObject(response)
            val detail = root.opt("detail")
            when (detail) {
                is JSONObject -> detail.optString("message").ifBlank { detail.optString("status") }
                is String -> detail
                else -> root.optString("message")
            }.ifBlank { "ElevenLabs request failed ($status)" }
        } catch (e: Exception) {
            "ElevenLabs request failed ($status)"
        }
    }

    private fun JSONObject.optNullableString(name: String): String? =
        optString(name).takeIf { it.isNotBlank() && it != "null" }

    private companion object {
        const val BASE_URL = "https://api.elevenlabs.io"
    }
}
