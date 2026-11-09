package app.html2mp4

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private lateinit var file: File
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    fun init(ctx: Context) { file = File(ctx.filesDir, "render.log") }
    fun file(): File = file
    @Synchronized fun reset(header: String) { file.writeText(header + "\n") }
    @Synchronized fun d(msg: String) {
        Log.d("HTML2MP4", msg)
        runCatching { if (file.length() < 2_000_000) file.appendText("${fmt.format(Date())}  $msg\n") }
    }
    fun e(msg: String, t: Throwable? = null) = d("ERROR $msg" + (t?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
}
