package app.html2mp4

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Bridges the UI, the foreground service and the engine. */
object RenderController {
    /** Set by MainActivity while it exists: it owns the visible window the WebView renders in. */
    val host = MutableStateFlow<RenderHost?>(null)
    @Volatile var lowMemory = false
    @Volatile private var pending: RenderConfig? = null
    private var job: Job? = null
    val running: Boolean get() = job?.isActive == true

    fun start(ctx: Context, cfg: RenderConfig) {
        if (running) return
        pending = cfg; lowMemory = false
        RenderState.reset()
        RenderState.update { it.copy(phase = Phase.Preparing, msg = "Starting…", renderW = cfg.width, renderH = cfg.height) }
        ContextCompat.startForegroundService(ctx, Intent(ctx, RenderService::class.java))
    }

    fun cancel() { job?.cancel() }

    fun run(service: RenderService) {
        val cfg = pending ?: run { service.finish("Nothing to render"); return }
        job = service.scope.launch {
            try {
                val h = host.filterNotNull().first()
                val uri = Engine.render(service.applicationContext, cfg, h)
                RenderState.update { it.copy(phase = Phase.Done, outUri = uri, msg = "Saved to Movies/HTML2MP4", fraction = 1f, etaSec = 0) }
                service.finish("Video saved to Movies/HTML2MP4")
            } catch (e: CancellationException) {
                RenderState.update { UiState(phase = Phase.Idle, msg = "Cancelled (finished parts are cached; press Start to resume).") }
                service.finish("Cancelled")
                throw e
            } catch (t: Throwable) {
                AppLog.e("Render failed", t)
                val msg = (t as? RenderError)?.message ?: (t.javaClass.simpleName + ": " + (t.message ?: "unknown error") + (if (t is OutOfMemoryError) " (out of memory, try 720p or lower FPS)" else ""))
                RenderState.update { it.copy(phase = Phase.Error, error = msg) }
                service.finish("Failed: $msg")
            }
        }
    }
}
