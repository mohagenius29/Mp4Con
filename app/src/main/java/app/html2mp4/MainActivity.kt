package app.html2mp4

import android.Manifest
import android.app.Dialog
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

class MainActivity : ComponentActivity(), RenderHost {
    private val _resumed = MutableStateFlow(false)
    override val resumed: StateFlow<Boolean> = _resumed
    private var htmlFile by mutableStateOf<File?>(null)
    private var htmlName by mutableStateOf("")
    private var rig: Rig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppLog.init(this)
        RenderController.host.value = this
        handleIntent(intent)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }
    override fun onResume() { super.onResume(); _resumed.value = true }
    override fun onPause() { _resumed.value = false; super.onPause() }
    override fun onDestroy() { if (RenderController.host.value === this) RenderController.host.value = null; super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) { RenderController.lowMemory = true; AppLog.d("onTrimMemory level=$level -> shrinking frame pool") }
    }

    // ---------------- input (picker / share / open-with) ----------------
    private fun handleIntent(i: Intent?) {
        val uri: Uri? = when (i?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
        if (uri != null) importUri(uri)
    }

    private fun importUri(uri: Uri) = lifecycleScope.launch {
        val f = withContext(Dispatchers.IO) {
            val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getColumnIndex(OpenableColumns.DISPLAY_NAME).let { if (it >= 0) c.getString(it) else null } else null
            } ?: "animation.html"
            val dir = File(filesDir, "inputs").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val dst = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            contentResolver.openInputStream(uri)!!.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            htmlName = name; dst
        }
        htmlFile = f
        RenderState.reset()
    }

    private fun useSample() = lifecycleScope.launch {
        val f = withContext(Dispatchers.IO) {
            val dir = File(filesDir, "inputs").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            File(dir, "sample.html").also { dst -> assets.open("sample.html").use { i -> dst.outputStream().use { o -> i.copyTo(o) } } }
        }
        htmlName = "sample.html"; htmlFile = f; RenderState.reset()
    }

    // ---------------- RenderHost: the visible window the WebView lives in ----------------
    private fun realSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else DisplayMetrics().also { @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }

    override suspend fun openRig(width: Int, height: Int): Rig {
        val landscape = width > height
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        withTimeoutOrNull(3000) { while ((realSize().let { it.first > it.second }) != landscape) delay(50) }
        val (rw, rh) = realSize()
        if (maxOf(width, height) > maxOf(rw, rh) || minOf(width, height) > minOf(rw, rh))
            throw RenderError("Screen is ${rw}x${rh}; it cannot host a ${width}x${height} render window. Choose a lower resolution.")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        _resumed.first { it }

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.allowFileAccess = false
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.BLACK)
        }
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(web, FrameLayout.LayoutParams(width, height)) }, ViewGroup.LayoutParams(width, height))
        dlg.setCancelable(false)
        val w = dlg.window!!
        w.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        w.setLayout(width, height)
        w.setGravity(Gravity.TOP or Gravity.START)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        w.attributes = w.attributes.also { it.x = 0; it.y = 0 }
        dlg.show()
        suspendCancellableCoroutine<Unit> { c -> web.post { c.resume(Unit) } }
        delay(150)
        if (web.width != width || web.height != height) {
            val got = "${web.width}x${web.height}"; dlg.dismiss(); web.destroy()
            throw RenderError("Android gave the render window $got instead of ${width}x${height}. Try a lower resolution.")
        }
        AppLog.d("render window ${web.width}x${web.height} on screen ${rw}x${rh}")
        return Rig(web, w, dlg, width, height).also { rig = it }
    }

    override fun closeRig() {
        rig?.let { r ->
            runCatching { r.webView.stopLoading() }; runCatching { r.dialog.dismiss() }; runCatching { r.webView.destroy() }
        }
        rig = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------- UI ----------------
    @Composable
    private fun App() {
        val ui by RenderState.ui.collectAsStateWithLifecycle()
        val busy = ui.phase == Phase.Preparing || ui.phase == Phase.Rendering || ui.phase == Phase.Audio || ui.phase == Phase.Muxing
        if (busy) RenderingScreen(ui) else SetupScreen(ui)
    }

    private fun dims(res: Int, o: Int): Pair<Int, Int> = when (o) { 0 -> res to res * 16 / 9; 1 -> res * 16 / 9 to res; else -> res to res }

    @Composable
    private fun SetupScreen(ui: UiState) {
        var res by rememberSaveable { mutableIntStateOf(720) }
        var orient by rememberSaveable { mutableIntStateOf(0) }
        var fps by rememberSaveable { mutableIntStateOf(30) }
        var hide by rememberSaveable { mutableStateOf(true) }
        var click by rememberSaveable { mutableStateOf(true) }
        var dur by rememberSaveable { mutableStateOf("") }
        var showMaps by rememberSaveable { mutableStateOf(false) }

        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) importUri(u) }
        val start = {
            val f = htmlFile
            if (f != null) {
                val (w, h) = dims(res, orient)
                RenderController.start(this, RenderConfig(f, htmlName, w, h, fps, hide, click, dur.toDoubleOrNull() ?: 0.0))
            }
        }
        val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
        val askAndStart = {
            when {
                Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> perm.launch(Manifest.permission.POST_NOTIFICATIONS)
                Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED -> perm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else -> start()
            }
        }

        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("HTML2MP4", style = MaterialTheme.typography.headlineMedium)
            Text("Frame-accurate HTML → MP4, rendered on this phone.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { pick.launch(arrayOf("text/html", "text/plain", "application/xhtml+xml")) }) { Text(if (htmlFile == null) "Pick .html file" else "File: $htmlName (change)") }

            OutlinedButton(onClick = { useSample() }) { Text("Use built-in 5 s test sample") }

            Text("Resolution"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(720, 1080).forEach { FilterChip(res == it, { res = it }, { Text("${it}p") }) } }
            Text("Orientation"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("9:16", "16:9", "1:1").forEachIndexed { i, s -> FilterChip(orient == i, { orient = i }, { Text(s) }) } }
            Text("FPS"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(24, 30, 60).forEach { FilterChip(fps == it, { fps = it }, { Text("$it") }) } }
            val (w, h) = dims(res, orient); Text("Output: ${w}×${h} @ $fps fps", style = MaterialTheme.typography.bodySmall)

            Row(verticalAlignment = Alignment.CenterVertically) { Switch(hide, { hide = it }); Spacer(Modifier.width(8.dp)); Text("Hide Play / Pause / Replay buttons") }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(click, { click = it }); Spacer(Modifier.width(8.dp)); Text("Auto-click Play / Start") }
            OutlinedTextField(dur, { dur = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Duration in seconds (blank = stop when audio ends + 0.4 s)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Button(onClick = { askAndStart() }, enabled = htmlFile != null, modifier = Modifier.fillMaxWidth()) { Text("Start") }

            when (ui.phase) {
                Phase.Done -> {
                    Text("✔ ${ui.msg}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ui.outUri?.let { u -> startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } }) { Text("Open in gallery") }
                        OutlinedButton(onClick = { shareLog() }) { Text("Share log") }
                    }
                }
                Phase.Error -> {
                    Text("✖ ${ui.error}", color = MaterialTheme.colorScheme.error)
                    Text("Finished parts are cached. Press Start to resume.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { shareLog() }) { Text("Share log") }
                }
                else -> if (ui.msg.isNotEmpty()) Text(ui.msg, style = MaterialTheme.typography.bodySmall)
            }

            TextButton(onClick = { showMaps = !showMaps }) { Text(if (showMaps) "Hide offline URL mappings" else "Offline URL mappings (CDN scripts / fonts)") }
            if (showMaps) MappingsEditor()
            Text("Built in: three.js r128 and Google Fonts Inter, Roboto, Poppins, Montserrat, Playfair Display, Amiri.", style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun MappingsEditor() {
        var url by rememberSaveable { mutableStateOf("") }
        var version by remember { mutableIntStateOf(0) }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
            if (u != null && url.isNotBlank()) { Mappings.add(this, url, u); url = ""; version++ }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url, { url = it }, label = { Text("URL or URL prefix (https://cdn.example.com/lib.js)") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { pick.launch(arrayOf("*/*")) }, enabled = url.isNotBlank()) { Text("Choose file for this URL") }
            key(version) {
                Mappings.load(this@MainActivity).forEach { (u, f) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$u → ${f.name.substringAfterLast('_')}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { Mappings.remove(this@MainActivity, u); version++ }) { Text("Remove") }
                    }
                }
            }
        }
    }

    @Composable
    private fun RenderingScreen(ui: UiState) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The WebView window covers the top-left corner at exact output size; controls use the remaining room.
            val rw = with(LocalDensity.current) { ui.renderW.toDp() }
            val rh = with(LocalDensity.current) { ui.renderH.toDp() }
            val right = maxWidth - rw; val bottom = maxHeight - rh
            val mod = if (right >= bottom) Modifier.align(Alignment.TopEnd).width(right).fillMaxHeight() else Modifier.align(Alignment.BottomStart).fillMaxWidth().height(bottom)
            Column(mod.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val frac = ui.fraction
                if (frac >= 0) LinearProgressIndicator(progress = { frac }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(ui.msg, style = MaterialTheme.typography.bodyMedium)
                if (ui.phase == Phase.Rendering) {
                    Text("Frame ${ui.frame}" + (if (ui.fraction >= 0) " · ${(ui.fraction * 100).toInt()}%" else "") +
                        (if (ui.renderFps > 0) " · %.1f fps".format(ui.renderFps) else "") + (if (ui.etaSec >= 0) " · ETA ${ui.etaSec / 60}m ${ui.etaSec % 60}s" else ""), style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ui.preview?.let { Image(it.asImageBitmap(), "Live preview", Modifier.height(if (right >= bottom) 120.dp else (bottom - 90.dp).coerceIn(48.dp, 160.dp))) }
                    OutlinedButton(onClick = { RenderController.cancel() }) { Text("Cancel") }
                }
            }
        }
    }

    private fun shareLog() {
        val u = FileProvider.getUriForFile(this, "$packageName.files", AppLog.file())
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share render log"))
    }
}
