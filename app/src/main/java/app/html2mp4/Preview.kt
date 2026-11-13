package app.html2mp4

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Finished preview chunks (a few seconds of rendered video, already muxed with their audio) are published here by the
 * [Engine] while it renders; [PreviewController] queues them into a player.
 */
object PreviewFeed {
    class Chunk(val file: File, val durationSec: Double)

    private val _chunks = MutableStateFlow<List<Chunk>>(emptyList())
    val chunks: StateFlow<List<Chunk>> = _chunks

    fun dir(ctx: Context): File = File(ctx.cacheDir, "preview").apply { mkdirs() }

    fun reset(ctx: Context): File {
        _chunks.value = emptyList()
        dir(ctx).listFiles()?.forEach { it.delete() }
        return dir(ctx)
    }

    fun add(c: Chunk) { _chunks.update { it + c } }
}

/**
 * Plays the chunks back-to-back while the render is still running, without stalls.
 *
 * Rendering is usually slower than real time, so playing from the first chunk would run dry. The controller therefore
 * (1) waits until enough is buffered that playback will not catch up with the renderer (computed from the measured render
 * speed and the expected length; capped at [MAX_START_BUFFER] seconds), and (2) if it ever does catch up it pauses briefly
 * and resumes once a few seconds are buffered again, instead of stuttering frame by frame.
 * When the render finishes the player is switched to the final MP4 at the same position.
 * Main thread only.
 */
class PreviewController(ctx: Context) {
    companion object {
        private const val MAX_START_BUFFER = 90.0
        private const val STALL_LEAD = 0.35
        private const val RESUME_LEAD = 3.0
    }

    private val player: ExoPlayer = ExoPlayer.Builder(ctx).build()
    private val handler = Handler(Looper.getMainLooper())
    private val planned = ArrayList<Double>()
    private val win = Timeline.Window()

    private var started = false
    private var waiting = true
    private var finalMode = false
    private var userPaused = false
    private var appResumed = true

    var status by mutableStateOf("")
    var posSec by mutableDoubleStateOf(0.0)
    var bufferedSec by mutableDoubleStateOf(0.0)
    var playing by mutableStateOf(false)
    /** True when the video surface should be shown instead of the live thumbnail. */
    var live by mutableStateOf(false)

    private val tick = object : Runnable {
        override fun run() {
            try { update() } catch (e: Exception) { AppLog.d("preview tick: ${e.message}") }
            handler.postDelayed(this, 200)
        }
    }

    init { handler.post(tick) }

    fun setSurface(sv: SurfaceView) { player.setVideoSurfaceView(sv) }
    fun clearSurface(sv: SurfaceView) { player.clearVideoSurfaceView(sv) }

    fun setAppResumed(r: Boolean) { appResumed = r; if (!r) player.playWhenReady = false }

    fun release() { handler.removeCallbacksAndMessages(null); player.release() }

    // ---------- session control ----------
    fun resetSession() {
        player.stop(); player.clearMediaItems()
        planned.clear()
        started = false; waiting = true; finalMode = false; userPaused = false
        live = false; playing = false; status = ""; posSec = 0.0; bufferedSec = 0.0
        player.playWhenReady = false
    }

    /** Called whenever the feed changes. An empty list means a new render started. */
    fun sync(list: List<PreviewFeed.Chunk>) {
        if (list.isEmpty()) { resetSession(); return }
        if (finalMode) return
        while (planned.size < list.size) {
            val c = list[planned.size]
            val wasEnded = player.playbackState == Player.STATE_ENDED
            val first = planned.isEmpty()
            planned += c.durationSec
            player.addMediaItem(MediaItem.fromUri(Uri.fromFile(c.file)))
            if (first) player.prepare() else if (wasEnded) player.seekTo(player.mediaItemCount - 1, 0L)
        }
    }

    /** Render finished: continue with the real output file from the current position. */
    fun showFinal(uri: Uri, totalSec: Double) {
        val pos = if (planned.isEmpty()) 0.0 else globalPos()
        val resume = started && pos < totalSec - 0.7
        finalMode = true
        player.setMediaItem(MediaItem.fromUri(uri), if (resume) (pos * 1000).toLong() else 0L)
        player.prepare()
        player.playWhenReady = resume && !userPaused && appResumed
        live = true; status = ""
    }

    fun togglePlay() {
        if (finalMode) {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.playWhenReady = !player.playWhenReady && appResumed
        } else {
            userPaused = !userPaused
            if (userPaused) player.playWhenReady = false
        }
    }

    // ---------- policy ----------
    private fun winDur(i: Int): Double {
        val tl = player.currentTimeline
        if (i < tl.windowCount) {
            val d = tl.getWindow(i, win).durationMs
            if (d != C.TIME_UNSET && d > 0) return d / 1000.0
        }
        return planned.getOrElse(i) { 0.0 }
    }

    private fun globalPos(): Double {
        if (finalMode) return player.currentPosition / 1000.0
        val idx = player.currentMediaItemIndex.coerceIn(0, maxOf(planned.size - 1, 0))
        var s = 0.0
        for (i in 0 until idx) s += winDur(i)
        return s + player.currentPosition / 1000.0
    }

    private fun update() {
        playing = player.isPlaying
        if (finalMode) {
            posSec = globalPos()
            bufferedSec = if (player.duration != C.TIME_UNSET) player.duration / 1000.0 else bufferedSec
            return
        }
        val ui = RenderState.ui.value
        val rendering = ui.phase == Phase.Preparing || ui.phase == Phase.Rendering || ui.phase == Phase.Audio || ui.phase == Phase.Muxing
        if (planned.isEmpty()) { status = if (rendering) "Rendering first preview chunk…" else ""; return }

        var rendered = 0.0
        for (i in planned.indices) rendered += winDur(i)
        val pos = globalPos()
        bufferedSec = rendered; posSec = pos
        val lead = rendered - pos

        fun setPlay(b: Boolean) { player.playWhenReady = b && appResumed && !userPaused }

        if (!started) {
            val speed = if (ui.fps > 0) ui.renderFps.toDouble() / ui.fps else 0.0
            val total = if (ui.totalFrames > 0 && ui.fps > 0) ui.totalFrames.toDouble() / ui.fps else -1.0
            val need = when {
                !rendering -> 0.0
                speed <= 0.0 -> Double.MAX_VALUE                       // no speed measurement yet
                speed >= 1.0 -> planned[0]                             // faster than real time: start with the first chunk
                total > 0 -> minOf(total * (1.0 - speed) * 1.15 + 2.0, MAX_START_BUFFER)
                else -> 10.0
            }
            if (rendered >= need || !rendering) {
                started = true; waiting = false; live = true; status = ""; setPlay(true)
            } else {
                live = false
                status = if (speed > 0 && need != Double.MAX_VALUE)
                    "Smooth preview starts in ~${((need - rendered) / speed).toInt().coerceAtLeast(1)} s (buffering so it plays without stalls)"
                else "Measuring render speed…"
            }
            return
        }

        if (!rendering) { if (waiting) { waiting = false; setPlay(true) }; status = ""; return }
        if (!waiting && lead < STALL_LEAD) { waiting = true; setPlay(false) }
        else if (waiting && lead >= RESUME_LEAD) { waiting = false; setPlay(true) }
        status = if (waiting && !userPaused) "Buffering… (render is slower than real time)" else ""
        if (!waiting && !userPaused && appResumed && !player.playWhenReady) setPlay(true)
    }
}
