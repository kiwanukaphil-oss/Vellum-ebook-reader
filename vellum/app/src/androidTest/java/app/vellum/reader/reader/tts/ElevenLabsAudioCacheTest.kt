package app.vellum.reader.reader.tts

import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ElevenLabsAudioCacheTest {
    private val cache by lazy {
        ElevenLabsAudioCache(ApplicationProvider.getApplicationContext())
    }
    private val request = ElevenLabsGenerationRequest(
        voiceId = "voice",
        modelId = "model",
        text = "A short passage.",
        previousText = null,
        nextText = null,
    )

    @Before
    fun prepare() = cache.clear()

    @After
    fun cleanUp() = cache.clear()

    @Test
    fun concurrentRequestsGenerateOnlyOnceAndPersist() = runBlocking {
        val calls = AtomicInteger()
        val generated = ElevenLabsGeneration(byteArrayOf(1, 2, 3, 4), listOf(0.0, 0.1))

        val results = coroutineScope {
            List(8) {
                async {
                    cache.getOrGenerate(request) {
                        calls.incrementAndGet()
                        delay(30)
                        generated
                    }
                }
            }.map { it.await() }
        }

        assertEquals(1, calls.get())
        assertTrue(cache.contains(request))
        results.forEach { assertArrayEquals(generated.audioMp3, it.audioFile.readBytes()) }
    }

    @Test
    fun corruptedAudioIsRejectedAndRemoved() = runBlocking {
        val cached = cache.getOrGenerate(request) {
            ElevenLabsGeneration(byteArrayOf(1, 2, 3), listOf(0.0))
        }
        cached.audioFile.appendBytes(byteArrayOf(9))

        assertFalse(cache.contains(request))
        assertFalse(cached.audioFile.exists())
    }

    @Test
    fun clearingDuringGenerationCannotRepopulateTheCache() = runBlocking {
        val pending = async {
            cache.getOrGenerate(request) {
                delay(100)
                ElevenLabsGeneration(byteArrayOf(1, 2, 3), listOf(0.0))
            }
        }
        delay(20)

        cache.clear()

        try {
            pending.await()
            throw AssertionError("Expected the cleared request to be cancelled")
        } catch (_: CancellationException) {
            // Expected: clear cancels all process-wide paid-audio work.
        }
        delay(120)
        assertFalse(cache.contains(request))
    }
}
