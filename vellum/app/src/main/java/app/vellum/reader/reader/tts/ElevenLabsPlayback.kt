package app.vellum.reader.reader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class CachedElevenLabsNarration(
    val audioFile: File,
    val characterStartSeconds: List<Double>,
)

/** Persistent paid-audio cache. It is cleared only by an explicit user action. */
class ElevenLabsAudioCache(context: Context) {
    private val directory = File(File(context.filesDir, "tts"), "elevenlabs").apply { mkdirs() }

    fun get(request: ElevenLabsGenerationRequest): CachedElevenLabsNarration? {
        val stem = key(request)
        val audio = File(directory, "$stem.mp3")
        val metadata = File(directory, "$stem.json")
        if (!audio.isFile || !metadata.isFile) return null
        return try {
            val json = JSONObject(metadata.readText())
            val startsJson = json.getJSONArray("character_start_times_seconds")
            val starts = List(startsJson.length()) { startsJson.getDouble(it) }
            val now = System.currentTimeMillis()
            audio.setLastModified(now)
            metadata.setLastModified(now)
            CachedElevenLabsNarration(audio, starts)
        } catch (e: Exception) {
            audio.delete()
            metadata.delete()
            null
        }
    }

    fun contains(request: ElevenLabsGenerationRequest): Boolean {
        val stem = key(request)
        return File(directory, "$stem.mp3").isFile && File(directory, "$stem.json").isFile
    }

    fun sizeBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        directory.deleteRecursively()
        directory.mkdirs()
    }

    fun put(
        request: ElevenLabsGenerationRequest,
        generation: ElevenLabsGeneration,
    ): CachedElevenLabsNarration {
        val stem = key(request)
        val audio = File(directory, "$stem.mp3")
        val metadata = File(directory, "$stem.json")
        val tempAudio = File(directory, "$stem.mp3.tmp")
        val tempMetadata = File(directory, "$stem.json.tmp")
        tempAudio.writeBytes(generation.audioMp3)
        tempMetadata.writeText(
            JSONObject()
                .put("character_start_times_seconds", JSONArray(generation.characterStartSeconds))
                .toString(),
        )
        audio.delete()
        metadata.delete()
        if (!tempAudio.renameTo(audio) || !tempMetadata.renameTo(metadata)) {
            tempAudio.delete()
            tempMetadata.delete()
            audio.delete()
            metadata.delete()
            throw IllegalStateException("Could not save ElevenLabs narration cache")
        }
        return CachedElevenLabsNarration(audio, generation.characterStartSeconds)
    }

    private fun key(request: ElevenLabsGenerationRequest): String {
        val value = listOf(
            request.voiceId,
            request.modelId,
            request.text,
            request.previousText.orEmpty(),
            request.nextText.orEmpty(),
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

}

/** Plays cached MP3 narration and reports source-audio position for highlighting. */
class ElevenLabsPlayback {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var player: MediaPlayer? = null

    fun resetCancel() = cancelled.set(false)

    suspend fun play(
        narration: CachedElevenLabsNarration,
        speed: Float,
        startPositionMs: Int = 0,
        onPosition: (positionMs: Int) -> Unit,
    ): Boolean {
        if (cancelled.get()) return false
        val mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(narration.audioFile.absolutePath)
            prepare()
            playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
        }
        player = mediaPlayer
        return try {
            if (startPositionMs > 0) seekTo(mediaPlayer, startPositionMs)
            if (cancelled.get()) return false
            mediaPlayer.start()
            while (currentCoroutineContext().isActive && !cancelled.get()) {
                val position = try { mediaPlayer.currentPosition } catch (e: Exception) { break }
                onPosition(position)
                if (!mediaPlayer.isPlaying) break
                delay(45)
            }
            currentCoroutineContext().isActive && !cancelled.get()
        } finally {
            try { mediaPlayer.release() } catch (e: Exception) { /* Already released by cancel. */ }
            if (player === mediaPlayer) player = null
        }
    }

    private suspend fun seekTo(mediaPlayer: MediaPlayer, positionMs: Int) {
        suspendCancellableCoroutine { continuation ->
            mediaPlayer.setOnSeekCompleteListener {
                it.setOnSeekCompleteListener(null)
                if (continuation.isActive) continuation.resume(Unit)
            }
            try {
                mediaPlayer.seekTo(positionMs.toLong(), MediaPlayer.SEEK_CLOSEST)
            } catch (e: Exception) {
                mediaPlayer.setOnSeekCompleteListener(null)
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        player?.let {
            try { it.stop() } catch (e: Exception) { /* Already stopped. */ }
            try { it.release() } catch (e: Exception) { /* Already released. */ }
        }
        player = null
    }

    fun setSpeed(speed: Float) {
        player?.let {
            try {
                it.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
            } catch (e: Exception) {
                // The next passage will still pick up the selected speed.
            }
        }
    }
}
