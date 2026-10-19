package app.whistleflux.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the bundled Whistle model on the phone through the JNI bridge. */
@RunWith(AndroidJUnit4::class)
class SpeechEngineTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    /** 16-bit mono 16 kHz WAV -> floats in [-1, 1]. */
    private fun readWav(name: String): FloatArray {
        val buf = ByteBuffer.wrap(testAssets.open(name).use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        while (at + 8 <= buf.limit()) {
            val id = String(ByteArray(4) { buf.get(at + it) }, Charsets.US_ASCII)
            val size = buf.getInt(at + 4)
            if (id == "data") {
                return FloatArray(size / 2) { buf.getShort(at + 8 + it * 2) / 32768f }
            }
            at += 8 + size + (size and 1)
        }
        error("$name has no data chunk")
    }

    @Test
    fun transcribesSpeech() {
        val t0 = System.nanoTime()
        SpeechEngine.preload(app)
        val t1 = System.nanoTime()
        val audio = readWav("hello.wav")
        val text = SpeechEngine.transcribe(app, audio)
        val t2 = System.nanoTime()
        Log.i("SpeechEngineTest", "load ${(t1 - t0) / 1_000_000} ms, ${audio.size / 16} ms of audio in ${(t2 - t1) / 1_000_000} ms: $text")
        assertTrue(text, text.startsWith("Hey Sarah, can we move the meeting to Wednesday"))
    }

    @Test
    fun silenceGivesNoText() {
        assertEquals("", SpeechEngine.transcribe(app, FloatArray(SpeechEngine.SAMPLE_RATE * 2)))
    }
}
