package app.vellum.reader.reader.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Kokoro-82M neural voice pack: a one-time ~305MB download from the
 * sherpa-onnx model releases, extracted into app storage. This is the only
 * other network call Vellum can make, is user-initiated, and downloads a
 * public model file — nothing is sent.
 */
object KokoroVoicePack {

    private const val PACK_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2"
    private const val DIR_NAME = "kokoro-en-v0_19"

    /** Kokoro v0.19 speaker ids, in model order. */
    val VOICES = listOf(
        "Default (US female)", "Bella (US female)", "Nicole (US female)",
        "Sarah (US female)", "Sky (US female)", "Adam (US male)",
        "Michael (US male)", "Emma (UK female)", "Isabella (UK female)",
        "George (UK male)", "Lewis (UK male)",
    )

    fun modelDir(context: Context): File = File(File(context.filesDir, "tts"), DIR_NAME)

    fun isInstalled(context: Context): Boolean {
        val dir = modelDir(context)
        return File(dir, ".complete").readTextOrNull() == DIR_NAME && assetsValid(dir)
    }

    private fun assetsValid(dir: File): Boolean =
        File(dir, "model.onnx").length() > 50L * 1024L * 1024L &&
            File(dir, "voices.bin").length() > 100_000L &&
            File(dir, "tokens.txt").length() > 1_000L &&
            File(dir, "espeak-ng-data").isDirectory

    private fun File.readTextOrNull(): String? = try { if (isFile) readText() else null } catch (_: Exception) { null }

    /**
     * Downloads and unpacks the voice pack, reporting 0..1 progress (download
     * is ~90% of the bar, extraction the rest). Safe to re-run after failure.
     */
    suspend fun install(context: Context, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val ttsDir = File(context.filesDir, "tts").apply { mkdirs() }
        val archive = File(ttsDir, "kokoro.tar.bz2")
        try {
            val connection = URL(PACK_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                val total = connection.contentLengthLong.coerceAtLeast(1)
                connection.inputStream.use { input ->
                    archive.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(0.9f * copied / total)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            File(modelDir(context), ".complete").delete()
            extract(archive, ttsDir, onProgress)
            archive.delete()
            val installed = assetsValid(modelDir(context))
            if (installed) File(modelDir(context), ".complete").writeText(DIR_NAME)
            installed
        } catch (e: Exception) {
            Log.e("VellumTts", "Kokoro voice-pack installation failed", e)
            archive.delete()
            false
        }
    }

    private fun extract(archive: File, into: File, onProgress: (Float) -> Unit) {
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())),
        ).use { tar ->
            var entry = tar.nextEntry
            var count = 0
            while (entry != null) {
                val target = File(into, entry.name)
                // Guard against path traversal from a hostile archive.
                if (!target.canonicalPath.startsWith(into.canonicalPath)) {
                    entry = tar.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { tar.copyTo(it) }
                }
                count++
                if (count % 20 == 0) onProgress((0.9f + 0.1f * count / 500f).coerceAtMost(0.995f))
                entry = tar.nextEntry
            }
        }
        onProgress(1f)
    }
}
