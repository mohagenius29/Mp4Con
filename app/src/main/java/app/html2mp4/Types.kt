package app.html2mp4

import android.app.Dialog
import android.graphics.Bitmap
import android.net.Uri
import android.view.Window
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class RenderConfig(
    val htmlFile: File,
    val displayName: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val hideOverlays: Boolean,
    val autoClick: Boolean,
    /** 0 = auto (stop when audio ends + tail). */
    val manualDurationSec: Double,
    val tailSec: Double = 0.4,
    val maxDurationSec: Double = 600.0,
    /** Used when there is no audio and no manual duration. */
    val fallbackDurationSec: Double = 10.0,
    /** Optional uploaded soundtrack (mp3/wav/m4a/...), mixed into the video from t=0. */
    val audioFile: File? = null,
    val audioDurSec: Double = 0.0,
    /** Turbo: wait 1 vsync instead of 2 before each capture (faster; set false if you ever see repeated frames). */
    val turbo: Boolean = true,
)

enum class Phase { Idle, Preparing, Rendering, Audio, Muxing, Done, Error }

data class UiState(
    val phase: Phase = Phase.Idle,
    val frame: Int = 0,
    val fraction: Float = -1f,
    val etaSec: Long = -1,
    val renderFps: Float = 0f,
    val msg: String = "",
    val preview: Bitmap? = null,
    val outUri: Uri? = null,
    val error: String? = null,
    val renderW: Int = 0,
    val renderH: Int = 0,
    val fps: Int = 0,
    val totalFrames: Int = -1,
)

object RenderState {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    fun update(f: (UiState) -> UiState) = _ui.update(f)
    fun reset() { _ui.value = UiState() }
    /** Non-fatal problem found while rendering (e.g. no sound captured); shown next to "Saved". */
    @Volatile var warning: String? = null
}

class RenderError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The window that hosts the WebView. Owned by the Activity (PixelCopy needs a real, visible window). */
class Rig(val webView: WebView, val window: Window, val dialog: Dialog, val width: Int, val height: Int) {
    @Volatile var failure: Throwable? = null
}

interface RenderHost {
    val resumed: StateFlow<Boolean>
    suspend fun openRig(width: Int, height: Int): Rig
    fun closeRig()
}
