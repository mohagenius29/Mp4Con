package app.html2mp4

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import android.view.Choreographer
import android.view.PixelCopy
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.ceil

object Engine {
    private const val SEG_SECONDS = 4       // cached segment length = resume granularity = live-preview chunk length
    private const val SAFE_VSYNCS = 2       // vsyncs to wait after the page painted, before PixelCopy (Turbo uses 1)
    private val pcThread by lazy { HandlerThread("pixelcopy").apply { start() } }
    private val pcHandler by lazy { Handler(pcThread.looper) }
    private var vsReq = 0L

    suspend fun render(ctx: Context, cfg: RenderConfig, host: RenderHost): Uri {
        val app = ctx.applicationContext
        val fps = cfg.fps
        AppLog.reset("HTML2MP4 on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n$cfg")
        RenderState.warning = null
        RenderState.update { UiState(phase = Phase.Preparing, msg = "Preparing…", renderW = cfg.width, renderH = cfg.height) }

        // ---- job cache (resume) ----
        val jobId = sha1(cfg.htmlFile, "${cfg.width}x${cfg.height}@$fps|${cfg.hideOverlays}|${cfg.autoClick}|${cfg.manualDurationSec}|${cfg.audioFile?.length() ?: 0}")
        val jobsRoot = File(app.cacheDir, "jobs").apply { mkdirs() }
        jobsRoot.listFiles()?.filter { it.name != jobId }?.forEach { it.deleteRecursively() }
        val jobDir = File(jobsRoot, jobId).apply { mkdirs() }
        File(jobDir, "seg_000.part").delete()
        val audioFiles = withContext(Dispatchers.IO) { extractAudio(cfg.htmlFile, jobDir) }
        AppLog.d("extracted ${audioFiles.size} embedded audio file(s)" + (cfg.audioFile?.let { "; uploaded soundtrack ${it.name} (${"%.1f".format(cfg.audioDurSec)} s)" } ?: ""))
        val previewDir = PreviewFeed.reset(app, cfg.audioFile)
        val previewExec = Executors.newSingleThreadExecutor()

        val rig = host.openRig(cfg.width, cfg.height)
        try {
            val web = rig.webView
            val map = OfflineMap(app, cfg.htmlFile)
            val blobs = BlobSink(jobDir)
            web.addJavascriptInterface(blobs, "__h2m")
            val loaded = CompletableDeferred<Unit>()
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest) = map.intercept(r)
                override fun onPageFinished(v: WebView, url: String) { loaded.complete(Unit) }
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    AppLog.d("resource error ${r.url}: ${e.description}")
                    if (r.isForMainFrame) loaded.completeExceptionally(RenderError("Page failed to load: ${e.description}"))
                }
                override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                    val err = RenderError("The WebView process " + if (d.didCrash()) "crashed." else "was killed by Android (likely low memory). Close other apps and press Start to resume.")
                    rig.failure = err; loaded.completeExceptionally(err); return true
                }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean { AppLog.d("console ${m.messageLevel()}: ${m.message()} (${m.sourceId().takeLast(24)}:${m.lineNumber()})"); return true }
            }
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                throw RenderError("This phone's Android System WebView is too old (no document-start scripts). Update \"Android System WebView\" in the Play Store.")
            val clockJs = app.assets.open("virtual_clock.js").bufferedReader().readText().replace("__W__", cfg.width.toString())
            WebViewCompat.addDocumentStartJavaScript(web, clockJs, setOf("*"))
            web.loadUrl(OfflineMap.INDEX + "#render") // some pages look for "render" in the URL to auto-start
            withTimeout(60_000) { loaded.await() }

            // ---- wait for fonts + images, then pre-roll ----
            RenderState.update { it.copy(msg = "Waiting for fonts and images…") }
            val infoTxt = web.eval("__vc.info()", rig)
            if (infoTxt == "null") throw RenderError("Virtual clock was not injected (page replaced the document?).")
            web.eval("__vc.ready()", rig)
            var ready = false
            for (i in 0 until 600) { if (web.eval("__vc.isReady", rig) == "true") { ready = true; break }; delay(50) }
            if (!ready) AppLog.d("WARN fonts/images not ready after 30 s, continuing")
            val info = JSONObject(web.eval("__vc.info()", rig))
            AppLog.d("page viewport ${info.getInt("w")}x${info.getInt("h")} (target ${cfg.width}x${cfg.height})")
            if (info.getInt("w") != cfg.width) AppLog.d("WARN page CSS viewport width differs from output width; layout may be scaled")
            // durations of embedded audio, in case the WebView cannot load the element itself
            val durJson = JSONObject()
            withContext(Dispatchers.IO) {
                for ((key, file) in audioFiles) try {
                    val r = MediaMetadataRetriever(); r.setDataSource(file.absolutePath)
                    val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull(); r.release()
                    if (ms != null && ms > 0) durJson.put(key, ms / 1000.0)
                } catch (e: Exception) { AppLog.d("could not read duration of ${file.name}: ${e.message}") }
            }
            if (durJson.length() > 0) web.eval("Object.assign(__vc.durByKey, $durJson); 1", rig)
            web.eval("__vc.prepare(${cfg.autoClick},${cfg.hideOverlays})", rig)
            web.eval("__vc.step(0,-1)", rig); web.eval("__vc.step(0,-1)", rig)
            settle(web, SAFE_VSYNCS); settle(web, SAFE_VSYNCS)

            // ---- frame loop ----
            val manualFrames = if (cfg.manualDurationSec > 0) ceil(cfg.manualDurationSec * fps).toInt() else -1
            // an uploaded soundtrack sets the minimum length (its duration + tail) when no manual duration is given
            val extFrames = if (cfg.audioFile != null && cfg.audioDurSec > 0) ceil((cfg.audioDurSec + cfg.tailSec) * fps).toInt() else 0
            val fallbackFrames = if (extFrames > 0) extFrames else ceil(cfg.fallbackDurationSec * fps).toInt()
            val capFrames = ceil(cfg.maxDurationSec * fps).toInt()
            var endFrame = if (manualFrames > 0) manualFrames else Int.MAX_VALUE
            var audioEndSet = false
            var lastProg = -1.0
            val segFrames = SEG_SECONDS * fps
            val segRe = Regex("seg_(\\d{3})_(\\d+)\\.mp4")
            val existing = jobDir.listFiles()!!.filter { segRe.matches(it.name) }.sortedBy { it.name }
            val segs = ArrayList<Mux.Seg>()
            val free = Channel<Bitmap>(Channel.UNLIMITED)
            val poolCount = AtomicInteger(3)
            repeat(3) { free.trySend(Bitmap.createBitmap(cfg.width, cfg.height, Bitmap.Config.ARGB_8888)) }
            val encErr = AtomicReference<Throwable?>(null)
            var f = 0; var segIndex = 0
            var capStart = 0L; var captured = 0

            class Slot(val exec: ExecutorService, val part: File, val enc: Future<SegmentEncoder>, val idx: Int)
            fun makeSlot(idx: Int): Slot {
                val ex = Executors.newSingleThreadExecutor()          // the encoder's EGL context lives on this one thread
                val part = File(jobDir, "seg_%03d.part".format(idx)).also { it.delete() }
                return Slot(ex, part, ex.submit(Callable { SegmentEncoder(cfg.width, cfg.height, fps, part) }), idx)
            }
            var warm: Slot? = null
            suspend fun takeEncoder(s: Slot): Pair<Slot, SegmentEncoder> = withContext(Dispatchers.IO) {
                try { s to s.enc.get() } catch (e: ExecutionException) {
                    AppLog.d("encoder start failed (${e.cause?.message}); retrying once")
                    s.exec.shutdown()
                    val s2 = makeSlot(s.idx)
                    try { s2 to s2.enc.get() } catch (e2: ExecutionException) { throw RenderError("Video encoder failed to start: ${e2.cause?.message}", e2.cause) }
                }
            }

            // ---- live preview: every finished segment becomes a small MP4 (video + its audio) for the in-app player ----
            fun byKey(k: String) = audioFiles.firstOrNull { k.startsWith(it.first) || it.first.startsWith(k) }?.second
            class Pending(val file: File, val start: Int, val end: Int)
            var pending: Pending? = null
            var logPos = 0
            var chunkNo = 0
            val liveLog = HashMap<Int, ArrayList<StepLog>>()

            fun resolveAudioFile(m: JSONObject): File? {
                val src = m.getString("src")
                var file: File? = when {
                    src.startsWith("data:") -> byKey(src)
                    src.startsWith("blob:") -> blobs.fileFor(src) ?: m.optString("key").takeIf { it.isNotEmpty() }?.let { byKey(it) }
                    else -> map.customFile(src) ?: map.cachedFile(src)
                }
                if (file == null && audioFiles.size == 1) file = audioFiles[0].second
                return file
            }

            suspend fun dispatchChunk(p: Pending) {
                val dur = (p.end - p.start).toDouble() / fps
                val inputs = ArrayList<Pair<File, List<AudioSeg>>>()
                try {
                    val meta = JSONArray(web.eval("__vc.audioMeta()", rig))
                    if (meta.length() > 0) {
                        val sl = JSONObject(web.eval("__vc.logSlice($logPos)", rig))
                        logPos = sl.getInt("n")
                        val rows = sl.getJSONArray("rows")
                        for (i in 0 until rows.length()) {
                            val r = rows.getJSONArray(i)
                            liveLog.getOrPut(r.getInt(1)) { ArrayList() } += StepLog(r.getInt(0), r.getDouble(2), r.getDouble(3), r.getInt(4) == 1)
                        }
                        for (i in 0 until meta.length()) {
                            val m = meta.getJSONObject(i)
                            val file = resolveAudioFile(m) ?: continue
                            val plan = AudioPlanner.planRange(liveLog[m.getInt("id")] ?: emptyList(), fps, p.start, p.end)
                            if (plan.any { !it.silence }) inputs += file to plan
                        }
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { AppLog.d("preview: could not read page audio state: ${e.message}") }
                // (the uploaded soundtrack is played by its own synced player in the preview, not through these chunks)
                val idx = chunkNo++
                previewExec.execute {
                    try {
                        var aud: File? = null
                        if (inputs.isNotEmpty()) {
                            val a = File(previewDir, "a_%04d.m4a".format(idx))
                            try { AudioRenderer.render(inputs, dur, a); aud = a } catch (e: Exception) { AppLog.d("preview chunk $idx audio failed: ${e.message}") }
                        }
                        val out = File(previewDir, "p_%04d.mp4".format(idx))
                        Mux.run(listOf(Mux.Seg(p.file, 0)), fps, aud, (dur * 1_000_000).toLong(), out)
                        aud?.delete()
                        PreviewFeed.add(PreviewFeed.Chunk(out, dur))
                    } catch (t: Throwable) { AppLog.d("preview chunk $idx failed: ${t.javaClass.simpleName} ${t.message}") }
                }
            }

            suspend fun stepFrame(fr: Int): JSONObject {
                val r = web.eval("__vc.step(${fr * 1000.0 / fps},$fr)", rig)
                if (r == "null") throw RenderError("Page script crashed during frame $fr. See log.")
                val st = JSONObject(r)
                lastProg = st.getDouble("prog")
                pending?.let { if (fr >= it.end) { pending = null; dispatchChunk(it) } }
                if (manualFrames < 0) {
                    val played = st.getInt("played")
                    if (!audioEndSet && played > 0 && st.getInt("ended") >= played && st.getInt("playing") == 0) {
                        audioEndSet = true; endFrame = minOf(endFrame, maxOf(extFrames, fr + ceil(cfg.tailSec * fps).toInt()))
                        AppLog.d("audio ended at frame $fr, stopping at $endFrame")
                    } else if (!audioEndSet && played == 0 && fr + 1 >= fallbackFrames && endFrame == Int.MAX_VALUE) {
                        endFrame = fr + 1; AppLog.d("no page audio and no duration: stopping at frame $endFrame (" + (if (extFrames > 0) "uploaded soundtrack length" else "fallback ${cfg.fallbackDurationSec}s") + ")")
                    }
                }
                return st
            }

            fun report(fr: Int, thumb: Bitmap?) {
                val el = (SystemClock.elapsedRealtime() - capStart) / 1000.0
                val rfps = if (captured > 5 && el > 0) (captured / el).toFloat() else 0f
                val total = when { manualFrames > 0 -> manualFrames; endFrame != Int.MAX_VALUE -> endFrame; lastProg > 0.02 -> (fr / lastProg).toInt(); else -> -1 }
                val frac = if (total > 0) (fr.toFloat() / total).coerceIn(0f, 1f) else -1f
                val eta = if (total > 0 && rfps > 0) ((total - fr) / rfps).toLong() else -1L
                RenderState.update { it.copy(phase = Phase.Rendering, frame = fr, fraction = frac, etaSec = eta, renderFps = rfps, msg = "Rendering", preview = thumb ?: it.preview, fps = fps, totalFrames = total) }
            }

            try {
                while (f < endFrame && f < capFrames) {
                    val cached = existing.getOrNull(segIndex)
                    if (cached != null) {                          // ---- resume: replay cached segment without capturing ----
                        val n = segRe.matchEntire(cached.name)!!.groupValues[2].toInt()
                        val start = f
                        RenderState.update { it.copy(phase = Phase.Rendering, msg = "Resuming: replaying cached part ${segIndex + 1}") }
                        for (k in 0 until n) { stepFrame(f); f++ }
                        segs += Mux.Seg(cached, start); segIndex++
                        pending = Pending(cached, start, f)
                        continue
                    }
                    val start = f
                    val cur = warm ?: makeSlot(segIndex); warm = null
                    val (used, enc) = takeEncoder(cur)
                    val exec = used.exec; val part = used.part
                    var ok = false
                    try {
                        if (capStart == 0L) capStart = SystemClock.elapsedRealtime()
                        while (f < endFrame && f - start < segFrames && f < capFrames) {
                            host.resumed.first { it }               // paused while the app is in the background
                            if (warm == null && f - start == segFrames - fps && existing.getOrNull(segIndex + 1) == null && endFrame - f > fps * 2)
                                warm = makeSlot(segIndex + 1)       // start the next encoder now so segment changes don't stall
                            stepFrame(f)
                            settle(web, if (cfg.turbo) 1 else SAFE_VSYNCS)
                            val bmp = free.receive()
                            copy(rig, bmp)
                            val thumb = if (f % 8 == 0) Bitmap.createScaledBitmap(bmp, 270, (270f * cfg.height / cfg.width).toInt().coerceAtLeast(1), true) else null
                            val pts = (f - start) * 1_000_000_000L / fps
                            exec.execute {
                                try { enc.encode(bmp, pts) } catch (t: Throwable) { encErr.compareAndSet(null, t) }
                                finally {
                                    if (RenderController.lowMemory && poolCount.get() > 1) { poolCount.decrementAndGet(); bmp.recycle() } else free.trySend(bmp)
                                }
                            }
                            encErr.get()?.let { throw RenderError("Video encoder failed: ${it.message}", it) }
                            f++; captured++
                            report(f, thumb)
                        }
                        withContext(Dispatchers.IO) { exec.submit { enc.finish() }.get() }
                        ok = true
                    } finally {
                        if (!ok) withContext(NonCancellable + Dispatchers.IO) { runCatching { exec.submit { enc.abort() }.get() } }
                        exec.shutdown()
                    }
                    val done = File(jobDir, "seg_%03d_%d.mp4".format(segIndex, f - start))
                    part.renameTo(done)
                    segs += Mux.Seg(done, start); segIndex++
                    pending = Pending(done, start, f)
                    AppLog.d("segment ${segIndex} saved: ${f - start} frames")
                }
                stepFrame(f)                                          // one extra step so the audio log covers the last frame
            } finally {
                while (true) { val b = free.tryReceive().getOrNull() ?: break; b.recycle() }
                warm?.let { w -> withContext(NonCancellable + Dispatchers.IO) { runCatching { w.exec.submit { w.enc.get().abort() }.get() }; w.exec.shutdown() } }
            }
            val totalFrames = f
            val durationSec = totalFrames / fps.toDouble()
            AppLog.d("rendered $totalFrames frames (${"%.2f".format(durationSec)} s)")

            // ---- audio ----
            RenderState.update { it.copy(phase = Phase.Audio, msg = "Rendering audio…", fraction = -1f, etaSec = -1) }
            var audioOut: File? = null
            var warn: String? = null
            var unresolved = 0
            val meta = JSONArray(web.eval("__vc.audioMeta()", rig))
            val inputs = ArrayList<Pair<File, List<AudioSeg>>>()
            if (meta.length() > 0) {
                val logJson = JSONArray(web.eval("__vc.log", rig, 120_000))
                val perId = HashMap<Int, ArrayList<StepLog>>()
                for (i in 0 until logJson.length()) {
                    val r = logJson.getJSONArray(i)
                    perId.getOrPut(r.getInt(1)) { ArrayList() } += StepLog(r.getInt(0), r.getDouble(2), r.getDouble(3), r.getInt(4) == 1)
                }
                for (i in 0 until meta.length()) {
                    val m = meta.getJSONObject(i); val src = m.getString("src")
                    val file = resolveAudioFile(m)
                    val plan = AudioPlanner.plan(perId[m.getInt("id")] ?: emptyList(), fps, totalFrames)
                    val audible = plan.any { !it.silence }
                    AppLog.d("audio #$i src=${src.take(40)} file=${file?.name} segments=${plan.size} audible=$audible")
                    if (file == null && audible) { unresolved++; AppLog.d("WARN audio #$i played but its file could not be found; it will be silent.") }
                    if (file != null && audible) inputs += file to plan
                }
            }
            cfg.audioFile?.let { ext ->
                inputs += ext to listOf(AudioSeg(false, 0.0, durationSec, durationSec))
                AppLog.d("audio: uploaded soundtrack ${ext.name} mixed in from 0 s")
            }
            if (inputs.isNotEmpty()) {
                val out = File(jobDir, "audio.m4a")
                try {
                    withContext(Dispatchers.IO) { AudioRenderer.render(inputs, durationSec, out) }
                    audioOut = out
                    AppLog.d("audio mixed: ${out.length() / 1024} KB for ${"%.1f".format(durationSec)} s")
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    AppLog.d("audio render failed, saving the video without sound: ${e.message}")
                    warn = "The audio could not be mixed (${e.message}), so the video has no sound. See log."
                }
            }
            if (audioOut == null && warn == null) {
                if (unresolved > 0) warn = "The page played audio but its file could not be found, so the video has no sound. See log."
                else if (web.eval("__vc.webAudio", rig) == "true") warn = "This page makes sound with the Web Audio API, which cannot be captured, so the video has no sound."
            }
            RenderState.warning = warn
            warn?.let { AppLog.d("WARNING: $it") }

            // ---- mux + save ----
            RenderState.update { it.copy(phase = Phase.Muxing, msg = "Writing MP4…") }
            val finalFile = File(jobDir, "final.mp4")
            withContext(Dispatchers.IO) { Mux.run(segs, fps, audioOut, totalFrames * 1_000_000L / fps, finalFile) }
            val name = cfg.displayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_") + "_${cfg.width}x${cfg.height}_${System.currentTimeMillis() / 1000}.mp4"
            val hasSound = withContext(Dispatchers.IO) {
                runCatching { val r = MediaMetadataRetriever(); try { r.setDataSource(finalFile.absolutePath); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes" } finally { r.release() } }.getOrDefault(false)
            }
            RenderState.hasSound = hasSound
            AppLog.d("final file has audio track: $hasSound")
            if (cfg.audioFile != null && !hasSound && RenderState.warning == null)
                RenderState.warning = "Your uploaded audio did not end up in the video. Tap Share log and send it to me."
            val uri = withContext(Dispatchers.IO) { Saver.save(app, finalFile, name) }
            withContext(Dispatchers.IO) { previewExec.shutdown(); previewExec.awaitTermination(30, TimeUnit.SECONDS) }
            jobDir.deleteRecursively()
            AppLog.d("saved $uri")
            return uri
        } finally {
            previewExec.shutdownNow()
            host.closeRig()
        }
    }

    // ---------- helpers ----------
    private suspend fun WebView.eval(js: String, rig: Rig, timeoutMs: Long = 30_000): String {
        rig.failure?.let { throw it }
        try {
            return withTimeout(timeoutMs) { suspendCancellableCoroutine<String> { c -> evaluateJavascript(js) { c.resume(it ?: "null") } } }
        } catch (e: TimeoutCancellationException) {
            throw rig.failure ?: RenderError("The page stopped responding (a script ran longer than ${timeoutMs / 1000}s).")
        }
    }

    private suspend fun vsync() = suspendCancellableCoroutine<Unit> { c -> Choreographer.getInstance().postFrameCallback { if (c.isActive) c.resume(Unit) } }

    /** After the page ran its frame callbacks: wait until Chromium has painted them, then a couple of vsyncs so the window surface holds them. */
    private suspend fun settle(web: WebView, vsyncs: Int) {
        withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<Unit> { c ->
                web.postVisualStateCallback(++vsReq, object : WebView.VisualStateCallback() { override fun onComplete(requestId: Long) { if (c.isActive) c.resume(Unit) } })
            }
        }
        repeat(vsyncs) { vsync() }
    }

    private suspend fun copy(rig: Rig, bmp: Bitmap) {
        var tries = 0
        while (true) {
            val code = suspendCancellableCoroutine<Int> { c ->
                PixelCopy.request(rig.window, Rect(0, 0, rig.width, rig.height), bmp, { r -> if (c.isActive) c.resume(r) }, pcHandler)
            }
            if (code == PixelCopy.SUCCESS) return
            if (++tries > 6) throw RenderError("PixelCopy failed (code $code). Keep the app open in the foreground with the screen on.")
            vsync()
        }
    }

    private fun sha1(file: File, extra: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.update(extra.toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Finds every data:audio/...;base64 URI in the HTML and writes it to a file. Key = first 60 chars of the URI. */
    private fun extractAudio(html: File, dir: File): List<Pair<String, File>> {
        val text = html.readText()
        val re = Regex("data:((?:audio|video)/[A-Za-z0-9.+-]+)(?:;[A-Za-z0-9=._-]+)*;base64,([A-Za-z0-9+/=]+)")
        val out = ArrayList<Pair<String, File>>()
        for (m in re.findAll(text)) {
            val key = m.value.take(60)
            if (out.any { it.first == key }) continue
            val ext = when (m.groupValues[1].substringAfter('/').lowercase()) {
                "mpeg", "mp3" -> "mp3"; "wav", "x-wav", "wave" -> "wav"; "ogg" -> "ogg"; "mp4", "x-m4a", "aac" -> "m4a"; "webm" -> "webm"; "flac" -> "flac"; else -> "bin"
            }
            val f = File(dir, "src_${out.size}.$ext")
            f.writeBytes(Base64.decode(m.groupValues[2], Base64.DEFAULT))
            out += key to f
        }
        return out
    }
}
