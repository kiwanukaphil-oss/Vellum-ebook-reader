package app.vellum.reader.reader.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class CachedElevenLabsNarration(
    val audioFile: File,
    val characterStartSeconds: List<Double>,
)

/** Validated, bounded paid-audio cache with one synthesis in flight per request. */
class ElevenLabsAudioCache(context: Context) {
    private val directory = File(File(context.filesDir, "tts"), "elevenlabs").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<CachedElevenLabsNarration>>()

    fun get(request: ElevenLabsGenerationRequest): CachedElevenLabsNarration? {
        val stem = key(request)
        val audio = File(directory, "$stem.mp3")
        val metadata = File(directory, "$stem.json")
        if (!audio.isFile || !metadata.isFile || audio.length() == 0L) return null
        return try {
            val json = JSONObject(metadata.readText())
            if (json.optLong("audio_bytes", -1L) != audio.length() ||
                json.optString("audio_sha256") != sha256(audio.readBytes())
            ) {
                throw IllegalStateException("Narration cache checksum mismatch")
            }
            val startsJson = json.getJSONArray("character_start_times_seconds")
            val starts = List(startsJson.length()) { startsJson.getDouble(it) }
            val now = System.currentTimeMillis()
            audio.setLastModified(now)
            metadata.setLastModified(now)
            CachedElevenLabsNarration(audio, starts)
        } catch (_: Exception) {
            audio.delete()
            metadata.delete()
            null
        }
    }

    fun contains(request: ElevenLabsGenerationRequest): Boolean = get(request) != null

    fun sizeBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    @Synchronized
    fun clear() {
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        directory.listFiles()?.forEach { it.delete() }
    }

    /** Caller cancellation does not discard a paid response; a restart awaits the same request. */
    suspend fun getOrGenerate(
        request: ElevenLabsGenerationRequest,
        generator: suspend () -> ElevenLabsGeneration,
    ): CachedElevenLabsNarration {
        get(request)?.let { return it }
        val stem = key(request)
        val deferred = inFlight.computeIfAbsent(stem) {
            scope.async {
                get(request) ?: generator().let { generation ->
                    currentCoroutineContext().ensureActive()
                    put(request, generation)
                }
            }
        }
        return try {
            deferred.await()
        } finally {
            if (deferred.isCompleted) inFlight.remove(stem, deferred)
        }
    }

    @Synchronized
    private fun put(
        request: ElevenLabsGenerationRequest,
        generation: ElevenLabsGeneration,
    ): CachedElevenLabsNarration {
        require(generation.audioMp3.isNotEmpty()) { "ElevenLabs returned empty audio" }
        val stem = key(request)
        val audio = File(directory, "$stem.mp3")
        val metadata = File(directory, "$stem.json")
        val nonce = UUID.randomUUID().toString()
        val tempAudio = File(directory, "$stem.$nonce.mp3.tmp")
        val tempMetadata = File(directory, "$stem.$nonce.json.tmp")
        try {
            tempAudio.writeBytes(generation.audioMp3)
            tempMetadata.writeText(
                JSONObject()
                    .put("audio_bytes", generation.audioMp3.size)
                    .put("audio_sha256", sha256(generation.audioMp3))
                    .put("character_start_times_seconds", JSONArray(generation.characterStartSeconds))
                    .toString(),
            )
            moveReplace(tempAudio, audio)
            moveReplace(tempMetadata, metadata)
            trimToLimit()
            return CachedElevenLabsNarration(audio, generation.characterStartSeconds)
        } catch (e: Exception) {
            tempAudio.delete()
            tempMetadata.delete()
            if (get(request) == null) {
                audio.delete()
                metadata.delete()
            }
            throw e
        }
    }

    private fun moveReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Keeps newest complete pairs and bounds cache growth to 512 MiB. */
    private fun trimToLimit() {
        var total = sizeBytes()
        if (total <= MAX_CACHE_BYTES) return
        val pairs = directory.listFiles()
            .orEmpty()
            .filter { it.extension == "mp3" }
            .sortedBy { it.lastModified() }
        for (audio in pairs) {
            if (total <= MAX_CACHE_BYTES) break
            val metadata = File(directory, "${audio.nameWithoutExtension}.json")
            total -= audio.length() + metadata.length()
            audio.delete()
            metadata.delete()
        }
    }

    private fun key(request: ElevenLabsGenerationRequest): String = sha256(
        listOf(
            request.voiceId,
            request.modelId,
            request.text,
            request.previousText.orEmpty(),
            request.nextText.orEmpty(),
        ).joinToString("\u0000").toByteArray(Charsets.UTF_8),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val MAX_CACHE_BYTES = 512L * 1024L * 1024L
    }
}

/** Plays cached narration with audio focus, interruption, and noisy-route handling. */
class ElevenLabsPlayback(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val cancelled = AtomicBoolean(false)
    private val pausedForFocus = AtomicBoolean(false)
    @Volatile private var player: MediaPlayer? = null

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                pausedForFocus.set(false)
                player?.let {
                    try { it.setVolume(1f, 1f); if (!it.isPlaying) it.start() } catch (_: Exception) { }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausedForFocus.set(true)
                try { player?.pause() } catch (_: Exception) { }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                try { player?.setVolume(0.2f, 0.2f) } catch (_: Exception) { }
            AudioManager.AUDIOFOCUS_LOSS -> cancel()
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener(focusListener)
        .setWillPauseWhenDucked(false)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) cancel()
        }
    }

    fun resetCancel() {
        cancelled.set(false)
        pausedForFocus.set(false)
    }

    suspend fun play(
        narration: CachedElevenLabsNarration,
        speed: Float,
        startPositionMs: Int = 0,
        onPosition: (positionMs: Int) -> Unit,
    ): Boolean {
        if (cancelled.get()) return false
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        appContext.registerReceiver(
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            Context.RECEIVER_NOT_EXPORTED,
        )
        val completed = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(audioAttributes)
            setDataSource(narration.audioFile.absolutePath)
            setOnCompletionListener { completed.set(true) }
            setOnErrorListener { _, _, _ -> failed.set(true); true }
            prepare()
            playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
        }
        player = mediaPlayer
        return try {
            if (startPositionMs > 0) seekTo(mediaPlayer, startPositionMs)
            if (cancelled.get()) return false
            mediaPlayer.start()
            while (currentCoroutineContext().isActive && !cancelled.get() && !completed.get() && !failed.get()) {
                if (!pausedForFocus.get()) {
                    val position = try { mediaPlayer.currentPosition } catch (_: Exception) { break }
                    onPosition(position)
                }
                delay(45)
            }
            currentCoroutineContext().isActive && !cancelled.get() && completed.get() && !failed.get()
        } finally {
            try { appContext.unregisterReceiver(noisyReceiver) } catch (_: Exception) { }
            audioManager.abandonAudioFocusRequest(focusRequest)
            try { mediaPlayer.release() } catch (_: Exception) { }
            if (player === mediaPlayer) player = null
        }
    }

    private suspend fun seekTo(mediaPlayer: MediaPlayer, positionMs: Int) {
        suspendCancellableCoroutine { continuation ->
            mediaPlayer.setOnSeekCompleteListener {
                it.setOnSeekCompleteListener(null)
                if (continuation.isActive) continuation.resume(Unit)
            }
            continuation.invokeOnCancellation { mediaPlayer.setOnSeekCompleteListener(null) }
            try {
                mediaPlayer.seekTo(positionMs.toLong(), MediaPlayer.SEEK_CLOSEST)
            } catch (_: Exception) {
                mediaPlayer.setOnSeekCompleteListener(null)
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        pausedForFocus.set(false)
        player?.let {
            try { it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        player = null
    }

    fun setSpeed(speed: Float) {
        player?.let {
            try { it.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) } catch (_: Exception) { }
        }
    }
}
