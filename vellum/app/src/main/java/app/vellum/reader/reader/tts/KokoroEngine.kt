package app.vellum.reader.reader.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Kokoro-82M via sherpa-onnx: fully on-device neural speech. Synthesis
 * uses the stable non-streaming JNI path, then writes PCM to an
 * AudioTrack in cancellable slices so playback still stops promptly
 * mid-sentence on cancel. One instance holds the loaded model (~2–4s init);
 * keep it for the whole reading session.
 */
class KokoroEngine(modelDir: File) {

    private val tts: OfflineTts
    private val generation = AtomicLong(0L)
    private var track: AudioTrack? = null

    init {
        val config = OfflineTtsConfig().apply {
            model = OfflineTtsModelConfig().apply {
                kokoro = OfflineTtsKokoroModelConfig().apply {
                    model = File(modelDir, "model.onnx").absolutePath
                    voices = File(modelDir, "voices.bin").absolutePath
                    tokens = File(modelDir, "tokens.txt").absolutePath
                    dataDir = File(modelDir, "espeak-ng-data").absolutePath
                }
                numThreads = 4
            }
        }
        tts = OfflineTts(assetManager = null, config = config)
    }

    val sampleRate: Int get() = tts.sampleRate()

    /** A monotonically unique playback generation; cancelled work can never rejoin. */
    fun beginSession(): Long = generation.incrementAndGet()

    /**
     * Synthesis only — runs on the producer coroutine so the next sentence is
     * ready while the current one plays. 16-bit PCM out: some audio HALs
     * reject ENCODING_PCM_FLOAT at 24kHz and yield an uninitialized track.
     * (Plain generate() is deliberate: sherpa-onnx 1.13.4's streaming-callback
     * JNI path trips a CheckJNI abort.)
     */
    fun synthesize(text: String, speakerId: Int, speed: Float, session: Long): ShortArray? {
        if (generation.get() != session) return null
        val result = tts.generate(text, speakerId, speed)
        if (generation.get() != session || result.samples.isEmpty()) return null
        return ShortArray(result.samples.size) { i ->
            (result.samples[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }

    /** Streams one synthesized utterance; blocking; false if cancelled. */
    fun playBlocking(pcm: ShortArray, session: Long): Boolean {
        if (generation.get() != session) return false
        val audioTrack = obtainTrack() ?: return false // no usable audio output
        audioTrack.play()
        val slice = sampleRate / 4 // 250ms of audio per write
        var offset = 0
        while (offset < pcm.size) {
            if (generation.get() != session) return false
            val count = minOf(slice, pcm.size - offset)
            audioTrack.write(pcm, offset, count, AudioTrack.WRITE_BLOCKING)
            offset += count
        }
        return generation.get() == session
    }

    fun cancel() {
        generation.incrementAndGet()
        track?.let {
            try {
                it.pause()
                it.flush()
            } catch (e: Exception) {
                // Track already released — nothing to silence.
            }
        }
    }

    fun release() {
        cancel()
        track?.release()
        track = null
        tts.release()
    }

    /** Null when the device offers no usable output for this format. */
    private fun obtainTrack(): AudioTrack? {
        track?.let { if (it.state == AudioTrack.STATE_INITIALIZED) return it }
        track?.release()
        track = null
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).let { if (it > 0) it else sampleRate * 2 }
        val newTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            return null
        }
        if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
            newTrack.release()
            return null
        }
        track = newTrack
        return newTrack
    }
}
