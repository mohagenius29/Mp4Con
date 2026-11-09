package app.html2mp4

import android.content.Context
import android.graphics.Bitmap
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.ceil

object Engine {
    private const val SEG_SECONDS = 10      // cached segment length (resume granularity)
    private const val SETTLE_VSYNCS = 2     // vsyncs to wait after the page painted, before PixelCopy. Lower = faster, riskier.
    private val pcThread by lazy { HandlerThread("pixelcopy").apply { start() } }
    private val pcHandler by lazy { Handler(pcThread.looper) }
    private var vsReq = 0L

    suspend fun render(ctx: Context, cfg: RenderConfig, host: RenderHost): Uri {
        val app = ctx.applicationContext
        val fps = cfg.fps
        AppLog.reset("HTML2MP4 on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n$cfg")
        RenderState.update { UiState(phase = Phase.Preparing, msg = "Preparing…", renderW = cfg.width, renderH = cfg.height) }

        // ---- job cache (resume) ----
        val jobId = sha1(cfg.htmlFile, "${cfg.width}x${cfg.height}@$fps|${cfg.hideOverlays}|${cfg.autoClick}|${cfg.manualDurationSec}")
        val jobsRoot = File(app.cacheDir, "jobs").apply { mkdirs() }
        jobsRoot.listFiles()?.filter { it.name != jobId }?.forEach { it.deleteRecursively() }
        val jobDir = File(jobsRoot, jobId).apply { mkdirs() }
        File(jobDir, "seg_000.part").delete()
        val audioFiles = withContext(Dispatchers.IO) { extractAudio(cfg.htmlFile, jobDir) }
        AppLog.d("extracted ${audioFiles.size} embedded audio file(s)")

        val rig = host.openRig(cfg.width, cfg.height)
        try {
            val web = rig.webView
            val map = OfflineMap(app, cfg.htmlFile)
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
            web.loadUrl(OfflineMap.INDEX)
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
            web.eval("__vc.prepare(${cfg.autoClick},${cfg.hideOverlays})", rig)
            web.eval("__vc.step(0,-1)", rig); web.eval("__vc.step(0,-1)", rig)
            settle(web); settle(web)

            // ---- frame loop ----
            val manualFrames = if (cfg.manualDurationSec > 0) ceil(cfg.manualDurationSec * fps).toInt() else -1
            val fallbackFrames = ceil(cfg.fallbackDurationSec * fps).toInt()
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

            suspend fun stepFrame(fr: Int): JSONObject {
                val r = web.eval("__vc.step(${fr * 1000.0 / fps},$fr)", rig)
                if (r == "null") throw RenderError("Page script crashed during frame $fr. See log.")
                val st = JSONObject(r)
                lastProg = st.getDouble("prog")
                if (manualFrames < 0) {
                    val played = st.getInt("played")
                    if (!audioEndSet && played > 0 && st.getInt("ended") >= played && st.getInt("playing") == 0) {
                        audioEndSet = true; endFrame = minOf(endFrame, fr + ceil(cfg.tailSec * fps).toInt())
                        AppLog.d("audio ended at frame $fr, stopping at $endFrame")
                    } else if (!audioEndSet && played == 0 && fr + 1 >= fallbackFrames && endFrame == Int.MAX_VALUE) {
                        endFrame = fr + 1; AppLog.d("no audio and no duration: stopping at fallback ${cfg.fallbackDurationSec}s")
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
                RenderState.update { it.copy(phase = Phase.Rendering, frame = fr, fraction = frac, etaSec = eta, renderFps = rfps, msg = "Rendering", preview = thumb ?: it.preview) }
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
                        continue
                    }
                    val start = f
                    val part = File(jobDir, "seg_%03d.part".format(segIndex))
                    val exec = Executors.newSingleThreadExecutor()
                    val enc = withContext(Dispatchers.IO) { exec.submit(Callable { SegmentEncoder(cfg.width, cfg.height, fps, part) }).get() }
                    var ok = false
                    try {
                        if (capStart == 0L) capStart = SystemClock.elapsedRealtime()
                        while (f < endFrame && f - start < segFrames && f < capFrames) {
                            host.resumed.first { it }               // paused while the app is in the background
                            stepFrame(f)
                            settle(web)
                            val bmp = free.receive()
                            copy(rig, bmp)
                            val thumb = if (f % 6 == 0) Bitmap.createScaledBitmap(bmp, 270, (270f * cfg.height / cfg.width).toInt().coerceAtLeast(1), true) else null
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
                    AppLog.d("segment ${segIndex} saved: ${f - start} frames")
                }
                stepFrame(f)                                          // one extra step so the audio log covers the last frame
            } finally {
                while (true) { val b = free.tryReceive().getOrNull() ?: break; b.recycle() }
            }
            val totalFrames = f
            val durationSec = totalFrames / fps.toDouble()
            AppLog.d("rendered $totalFrames frames (${"%.2f".format(durationSec)} s)")

            // ---- audio ----
            RenderState.update { it.copy(phase = Phase.Audio, msg = "Rendering audio…", fraction = -1f, etaSec = -1) }
            var audioOut: File? = null
            val meta = JSONArray(web.eval("__vc.audioMeta()", rig))
            if (meta.length() > 0) {
                val logJson = JSONArray(web.eval("__vc.log", rig, 120_000))
                val perId = HashMap<Int, ArrayList<StepLog>>()
                for (i in 0 until logJson.length()) {
                    val r = logJson.getJSONArray(i)
                    perId.getOrPut(r.getInt(1)) { ArrayList() } += StepLog(r.getInt(0), r.getDouble(2), r.getDouble(3), r.getInt(4) == 1)
                }
                val inputs = ArrayList<Pair<File, List<AudioSeg>>>()
                for (i in 0 until meta.length()) {
                    val m = meta.getJSONObject(i); val src = m.getString("src")
                    val file = if (src.startsWith("data:")) audioFiles.firstOrNull { src.startsWith(it.first) || it.first.startsWith(src) }?.second else map.customFile(src)
                    val plan = AudioPlanner.plan(perId[m.getInt("id")] ?: emptyList(), fps, totalFrames)
                    val audible = plan.any { !it.silence }
                    AppLog.d("audio #$i src=${src.take(40)} file=${file?.name} segments=${plan.size} audible=$audible")
                    if (file == null && audible) AppLog.d("WARN audio #$i played but its source is not a data: URI or a mapped URL; it will be silent.")
                    if (file != null && audible) inputs += file to plan
                }
                if (inputs.isNotEmpty()) {
                    audioOut = File(jobDir, "audio.m4a")
                    withContext(Dispatchers.IO) { AudioRenderer.render(inputs, durationSec, audioOut) }
                }
            }

            // ---- mux + save ----
            RenderState.update { it.copy(phase = Phase.Muxing, msg = "Writing MP4…") }
            val finalFile = File(jobDir, "final.mp4")
            withContext(Dispatchers.IO) { Mux.run(segs, fps, audioOut, totalFrames * 1_000_000L / fps, finalFile) }
            val name = cfg.displayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_") + "_${cfg.width}x${cfg.height}_${System.currentTimeMillis() / 1000}.mp4"
            val uri = withContext(Dispatchers.IO) { Saver.save(app, finalFile, name) }
            jobDir.deleteRecursively()
            AppLog.d("saved $uri")
            return uri
        } finally {
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
    private suspend fun settle(web: WebView) {
        withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<Unit> { c ->
                web.postVisualStateCallback(++vsReq, object : WebView.VisualStateCallback() { override fun onComplete(requestId: Long) { if (c.isActive) c.resume(Unit) } })
            }
        }
        repeat(SETTLE_VSYNCS) { vsync() }
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
