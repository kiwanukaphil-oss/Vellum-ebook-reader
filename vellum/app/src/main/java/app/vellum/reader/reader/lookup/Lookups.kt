package app.vellum.reader.reader.lookup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * User-initiated word/topic lookups. These are the app's only network calls,
 * fire only on an explicit tap, and send just the selected term — never
 * reading content or identifiers.
 */
object Lookups {

    /** Definitions from dictionaryapi.dev (Wiktionary data), or null. */
    suspend fun define(word: String): String? = withContext(Dispatchers.IO) {
        val json = fetch("https://api.dictionaryapi.dev/api/v2/entries/en/" + encode(word.trim())) ?: return@withContext null
        try {
            val entries = JSONArray(json)
            if (entries.length() == 0) return@withContext null
            val builder = StringBuilder()
            val entry = entries.getJSONObject(0)
            val meanings = entry.optJSONArray("meanings") ?: return@withContext null
            for (m in 0 until minOf(meanings.length(), 3)) {
                val meaning = meanings.getJSONObject(m)
                builder.append(meaning.optString("partOfSpeech")).append('\n')
                val definitions = meaning.optJSONArray("definitions") ?: continue
                for (d in 0 until minOf(definitions.length(), 2)) {
                    builder.append("  • ").append(definitions.getJSONObject(d).optString("definition")).append('\n')
                }
            }
            builder.toString().trim().ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "Dictionary response could not be parsed", e)
            null
        }
    }

    /** Wikipedia lead summary for a term, or null if there's no article. */
    suspend fun wikipediaSummary(term: String): String? = withContext(Dispatchers.IO) {
        val json = fetch("https://en.wikipedia.org/api/rest_v1/page/summary/" + encode(term.trim())) ?: return@withContext null
        try {
            val body = JSONObject(json)
            val extract = body.optString("extract")
            extract.ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "Wikipedia response could not be parsed", e)
            null
        }
    }

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun fetch(url: String): String? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 6000
        connection.setRequestProperty("User-Agent", "Vellum-personal-reader")
        try {
            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().readText()
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Lookup request failed", e)
        null
    }

    private const val TAG = "VellumLookup"
}
