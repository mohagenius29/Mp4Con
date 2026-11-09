package app.html2mp4

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the bundled 5 s sample (canvas + WebGL + CSS animation + audio with varying playbackRate) and checks:
 * duration ≈ audio length + 0.4 s tail, an audio track exists, and frames at different times differ.
 * Run on a real device: ./gradlew connectedDebugAndroidTest   (keep the screen unlocked)
 */
@RunWith(AndroidJUnit4::class)
class SampleRenderTest {
    @Test fun rendersSample720p() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val html = File(ctx.cacheDir, "sample.html").also { f -> ctx.assets.open("sample.html").use { i -> f.outputStream().use { o -> i.copyTo(o) } } }
        ActivityScenario.launch(MainActivity::class.java).use {
            val cfg = RenderConfig(html, "sample.html", 720, 1280, 30, hideOverlays = true, autoClick = true, manualDurationSec = 0.0)
            val uri: Uri = runBlocking(Dispatchers.Main) {
                withTimeout(300_000) {
                    val host = RenderController.host.value
                    assertNotNull("MainActivity must be registered as render host", host)
                    Engine.render(ctx, cfg, host!!)
                }
            }
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(ctx, uri)
                val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("duration $ms ms should be ~5000 ms", ms in 4600..5600)
                assertEquals("yes", mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertEquals("720", mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                val a = mmr.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
                val b = mmr.getFrameAtTime(2_500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
                assertFalse(a.sameAs(b))
            } finally { mmr.release() }
        }
    }
    private fun assertFalse(v: Boolean) = org.junit.Assert.assertFalse("frames at 0.5 s and 2.5 s should differ (animation is moving)", v)
}
