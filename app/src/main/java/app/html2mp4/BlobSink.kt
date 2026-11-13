package app.html2mp4

import android.util.Base64
import android.webkit.JavascriptInterface
import java.io.File

/**
 * Receives the bytes of blob: URLs created by the page (streamed in chunks by virtual_clock.js), so audio that a page builds
 * at runtime (base64 -> Blob, fetch -> Blob, ...) can still be put into the MP4.
 */
class BlobSink(private val dir: File) {
    private val files = HashMap<String, File>()
    private val parts = HashMap<String, Array<ByteArray?>>()
    private var counter = 0

    @JavascriptInterface
    @Synchronized
    fun chunk(url: String, i: Int, n: Int, type: String, data: String) {
        try {
            val arr = parts.getOrPut(url) { arrayOfNulls(n) }
            arr[i] = Base64.decode(data, Base64.DEFAULT)
            if (arr.all { it != null }) {
                val ext = when (type.substringAfter('/').lowercase()) {
                    "mpeg", "mp3" -> "mp3"; "wav", "x-wav", "wave" -> "wav"; "ogg" -> "ogg"
                    "mp4", "x-m4a", "aac" -> "m4a"; "webm" -> "webm"; "flac" -> "flac"; else -> "bin"
                }
                val f = File(dir, "blob_${counter++}.$ext")
                f.outputStream().use { o -> arr.forEach { o.write(it!!) } }
                files[url] = f; parts.remove(url)
            }
        } catch (e: Exception) { AppLog.d("blob chunk failed: ${e.message}") }
    }

    @Synchronized fun fileFor(url: String): File? = files[url]
}
