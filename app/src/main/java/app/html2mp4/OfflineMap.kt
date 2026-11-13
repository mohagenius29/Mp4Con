package app.html2mp4

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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

/**
 * Disk cache for everything the page downloads (fonts, CDN scripts, images, audio files). The first render needs internet;
 * later renders of the same page work offline and are deterministic. Only static-looking files are cached, never API calls.
 */
class NetCache(ctx: Context) {
    private val dir = File(ctx.filesDir, "netcache").apply { mkdirs() }
    private fun key(url: String) = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun bin(url: String) = File(dir, key(url) + ".bin")
    private fun meta(url: String) = File(dir, key(url) + ".mime")

    fun cached(url: String): File? = bin(url).takeIf { it.exists() && it.length() > 0 }
    fun mime(url: String): String? = meta(url).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun cacheable(u: Uri): Boolean {
        val host = u.host ?: return false
        if (host == "fonts.googleapis.com" || host == "fonts.gstatic.com") return true
        val ext = (u.path ?: "").substringAfterLast('.', "").lowercase(Locale.US)
        return ext in setOf("js", "mjs", "css", "woff", "woff2", "ttf", "otf", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico",
            "mp3", "wav", "ogg", "oga", "m4a", "aac", "flac", "opus", "weba", "mp4", "webm", "glb", "gltf", "bin", "wasm")
    }

    fun fetch(url: String, headers: Map<String, String>): File? {
        cached(url)?.let { it.setLastModified(System.currentTimeMillis()); return it }
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
            headers["User-Agent"]?.let { c.setRequestProperty("User-Agent", it) }
            c.setRequestProperty("Accept", headers["Accept"] ?: "*/*")
            if (c.responseCode !in 200..299) { AppLog.d("net $url -> HTTP ${c.responseCode}"); return null }
            val tmp = File(dir, key(url) + ".tmp")
            c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (tmp.length() == 0L) { tmp.delete(); return null }
            bin(url).delete(); tmp.renameTo(bin(url))
            meta(url).writeText((c.contentType ?: "").substringBefore(';').trim())
            AppLog.d("net downloaded $url (${bin(url).length() / 1024} KB)")
            prune()
            bin(url)
        } catch (e: Exception) { AppLog.d("net failed $url: ${e.javaClass.simpleName} ${e.message}"); null }
    }

    private fun prune(maxBytes: Long = 300L * 1024 * 1024) {
        val files = dir.listFiles { f -> f.name.endsWith(".bin") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= maxBytes) break; total -= f.length(); f.delete(); File(dir, f.name.removeSuffix(".bin") + ".mime").delete() }
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
    private val net = NetCache(ctx)
    fun customFile(url: String): File? = custom.firstOrNull { url.startsWith(it.first) }?.second
    /** File downloaded earlier for this URL (used to find the audio of <audio src="https://...">). */
    fun cachedFile(url: String): File? = net.cached(url)

    fun intercept(req: WebResourceRequest): WebResourceResponse? {
        val u = req.url; val url = u.toString()
        if (url == INDEX || url.startsWith("$INDEX?") || url.startsWith("$INDEX#")) return resp("text/html", indexFile.inputStream())
        customFile(url)?.let { AppLog.d("map custom $url"); return resp(mime(it.name), it.inputStream()) }
        val path = u.path ?: ""
        if (u.host == "html2mp4.local" && path.startsWith("/offline/")) return asset(path.removePrefix("/"))
        if (u.host == "fonts.googleapis.com") return googleFontsCss(req)
        if (path.endsWith(".js") && path.contains("three") && (path.contains("r128") || path.contains("0.128"))) {
            AppLog.d("map three.js r128 <- $url"); return asset("offline/three.min.js")
        }
        // any other static file from the internet: download once, then serve from disk
        if ((u.scheme == "http" || u.scheme == "https") && u.host != "html2mp4.local" && req.method == "GET" && net.cacheable(u)) {
            net.fetch(url, req.requestHeaders)?.let { f -> return resp(net.mime(url) ?: mime(f.name), FileInputStream(f)) }
        }
        return null // fall through to the network if available
    }

    private fun googleFontsCss(req: WebResourceRequest): WebResourceResponse? {
        val u = req.url
        val families = u.getQueryParameters("family").flatMap { it.split('|') }
        val css = StringBuilder()
        var missing = 0
        for (fam in families) {
            val name = fam.substringBefore(':').replace('+', ' ')
            val slug = name.lowercase(Locale.US).replace(' ', '-')
            val files = ctx.assets.list("offline/fonts/$slug")
            if (files == null || files.isEmpty()) { missing++; continue }
            for (f in files) {
                val m = Regex(".*-(latin|arabic)-(\\d+)-(normal|italic)\\.woff2").matchEntire(f) ?: continue
                css.append("@font-face{font-family:'$name';font-style:${m.groupValues[3]};font-weight:${m.groupValues[2]};")
                    .append("src:url($ORIGIN/offline/fonts/$slug/$f) format('woff2');unicode-range:${if (m.groupValues[1] == "arabic") ARABIC else LATIN};}\n")
            }
        }
        if (css.isNotEmpty() && missing == 0) {
            AppLog.d("fonts ${u.query} -> bundled")
            return resp("text/css", ByteArrayInputStream(css.toString().toByteArray()))
        }
        // some family is not bundled: use the real Google Fonts CSS (cached for offline reuse), keep bundled files as last resort
        net.fetch(u.toString(), req.requestHeaders)?.let { f ->
            AppLog.d("fonts ${u.query} -> google (cached)")
            return resp("text/css", FileInputStream(f))
        }
        AppLog.d("fonts ${u.query} -> " + if (css.isEmpty()) "NOT available (offline and not bundled; add a custom mapping or connect once)" else "partial bundled (offline)")
        return if (css.isEmpty()) null else resp("text/css", ByteArrayInputStream(css.toString().toByteArray()))
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
