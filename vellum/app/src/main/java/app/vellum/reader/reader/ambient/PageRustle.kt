package app.vellum.reader.reader.ambient

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import kotlin.math.exp
import kotlin.random.Random

/**
 * The optional page-turn rustle: a 130ms noise burst shaped like paper,
 * synthesized once into a WAV (no bundled asset, no network) and played
 * through a tiny SoundPool at low volume.
 */
class PageRustle(context: Context) {

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private var soundId = 0
    private var loaded = false

    init {
        val wav = ensureWav(context)
        soundPool.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
        soundId = soundPool.load(wav.absolutePath, 1)
    }

    fun play() {
        if (loaded) soundPool.play(soundId, 0.25f, 0.25f, 1, 0, 1f)
    }

    fun release() = soundPool.release()

    /** Synthesizes the rustle WAV on first use: shaped, softened white noise. */
    private fun ensureWav(context: Context): File {
        val file = File(context.filesDir, "rustle.wav")
        if (file.exists()) return file
        val sampleRate = 44_100
        val samples = (sampleRate * 0.13).toInt()
        val random = Random(7)
        val raw = FloatArray(samples) { i ->
            val t = i.toFloat() / samples
            val attack = (t / 0.06f).coerceAtMost(1f)
            val decay = exp(-4.5f * t)
            (random.nextFloat() * 2f - 1f) * attack * decay
        }
        // Three-tap average ≈ gentle low-pass: hiss becomes paper.
        val soft = FloatArray(samples) { i ->
            (raw[i] + raw[maxOf(0, i - 1)] + raw[maxOf(0, i - 2)]) / 3f
        }
        val pcm = ByteArray(samples * 2)
        soft.forEachIndexed { i, sample ->
            val value = (sample * 12_000).toInt().coerceIn(-32_768, 32_767)
            pcm[i * 2] = (value and 0xFF).toByte()
            pcm[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        file.outputStream().use { out ->
            val dataSize = pcm.size
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            header.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            header.put("data".toByteArray()).putInt(dataSize)
            out.write(header.array())
            out.write(pcm)
        }
        return file
    }
}
