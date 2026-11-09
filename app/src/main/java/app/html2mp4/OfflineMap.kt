package app.html2mp4

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

/** User-defined URL-prefix -> local file mappings (copied into app storage). One "url<TAB>filename" per line. */
object Mappings {
    private fun dir(ctx: Context) = File(ctx.filesDir, "mappings").apply { mkdirs() }
    private fun index(ctx: Context) = File(dir(ctx), "index.tsv")

    fun load(ctx: Context): List<Pair<String, File>> =
        if (!index(ctx).exists()) emptyList() else index(ctx).readLines().mapNotNull { line ->
            val p = line.split('\t'); if (p.size == 2 && File(dir(ctx), p[1]).exists()) p[0] to File(dir(ctx), p[1]) else null
        }

    fun add(ctx: Context, url: String, source: Uri) {
        val name = "m_" + System.currentTimeMillis() + "_" + (source.lastPathSegment ?: "file").substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        ctx.contentResolver.openInputStream(source)!!.use { i -> File(dir(ctx), name).outputStream().use { o -> i.copyTo(o) } }
        index(ctx).appendText("${url.trim()}\t$name\n")
    }

    fun remove(ctx: Context, url: String) {
        val keep = index(ctx).readLines().filter { it.substringBefore('\t') != url }
        index(ctx).writeText(keep.joinToString("\n") + if (keep.isEmpty()) "" else "\n")
    }
}

/** Serves the HTML itself, bundled assets (three.js r128, Google Fonts) and custom mappings without network. */
class OfflineMap(private val ctx: Context, private val indexFile: File) {
    companion object {
        const val ORIGIN = "https://html2mp4.local"
        const val INDEX = "$ORIGIN/index.html"
        private val LATIN = "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD"
        private val ARABIC = "U+0600-06FF,U+200C-200E,U+2010-2011,U+204F,U+2E41,U+FB50-FDFF,U+FE80-FEFC"
    }

    private val custom = Mappings.load(ctx)
    fun customFile(url: String): File? = custom.firstOrNull { url.startsWith(it.first) }?.second

    fun intercept(req: WebResourceRequest): WebResourceResponse? {
        val u = req.url; val url = u.toString()
        if (url == INDEX || url.startsWith("$INDEX?") || url.startsWith("$INDEX#")) return resp("text/html", indexFile.inputStream())
        customFile(url)?.let { AppLog.d("map custom $url"); return resp(mime(it.name), it.inputStream()) }
        val path = u.path ?: ""
        if (u.host == "html2mp4.local" && path.startsWith("/offline/")) return asset(path.removePrefix("/"))
        if (u.host == "fonts.googleapis.com") return googleFontsCss(u)
        if (path.endsWith(".js") && path.contains("three") && (path.contains("r128") || path.contains("0.128"))) {
            AppLog.d("map three.js r128 <- $url"); return asset("offline/three.min.js")
        }
        return null // fall through to the network if available
    }

    private fun googleFontsCss(u: Uri): WebResourceResponse? {
        val families = u.getQueryParameters("family").flatMap { it.split('|') }
        val css = StringBuilder()
        for (fam in families) {
            val name = fam.substringBefore(':').replace('+', ' ')
            val slug = name.lowercase(Locale.US).replace(' ', '-')
            val files = ctx.assets.list("offline/fonts/$slug") ?: continue
            for (f in files) {
                val m = Regex(".*-(latin|arabic)-(\\d+)-(normal|italic)\\.woff2").matchEntire(f) ?: continue
                css.append("@font-face{font-family:'$name';font-style:${m.groupValues[3]};font-weight:${m.groupValues[2]};")
                    .append("src:url($ORIGIN/offline/fonts/$slug/$f) format('woff2');unicode-range:${if (m.groupValues[1] == "arabic") ARABIC else LATIN};}\n")
            }
        }
        AppLog.d("map google fonts css ${u.query} -> ${if (css.isEmpty()) "no bundled family (add a custom mapping)" else "bundled"}")
        if (css.isEmpty()) return null
        return resp("text/css", ByteArrayInputStream(css.toString().toByteArray()))
    }

    private fun asset(p: String): WebResourceResponse? = try { resp(mime(p), ctx.assets.open(p)) } catch (e: Exception) { null }
    private fun mime(name: String) = when (name.substringAfterLast('.').lowercase(Locale.US)) {
        "js", "mjs" -> "application/javascript"; "css" -> "text/css"; "woff2" -> "font/woff2"; "woff" -> "font/woff"; "ttf" -> "font/ttf"
        "html" -> "text/html"; "json" -> "application/json"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.')) ?: "application/octet-stream"
    }
    private fun resp(mime: String, s: InputStream) =
        WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), s)
}
