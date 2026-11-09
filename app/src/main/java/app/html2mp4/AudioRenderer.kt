package app.html2mp4

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Locale

/** Renders time-varying-rate, pitch-preserved audio with ffmpeg (rubberband if present, else atempo). */
object AudioRenderer {
    private fun f(d: Double) = String.format(Locale.US, "%.6f", d)

    fun hasRubberband(): Boolean {
        val s = FFmpegKit.execute("-hide_banner -h filter=rubberband")
        val has = !(s.output ?: "").contains("Unknown filter", true)
        AppLog.d("ffmpeg rubberband filter available: $has")
        return has
    }

    private fun tempo(rate: Double, rubber: Boolean): String {
        if (abs1(rate)) return "anull"
        if (rubber) return "rubberband=tempo=${f(rate)}:pitch=1:transients=crisp"
        val parts = ArrayList<String>(); var r = rate
        while (r > 2.0) { parts += "atempo=2.0"; r /= 2.0 }
        while (r < 0.5) { parts += "atempo=0.5"; r /= 0.5 }
        parts += "atempo=${f(r)}"
        return parts.joinToString(",")
    }
    private fun abs1(r: Double) = kotlin.math.abs(r - 1.0) < 0.005

    /** inputs: source file + plan for each audio element. Returns an AAC .m4a exactly [totalSec] long. */
    fun render(inputs: List<Pair<File, List<AudioSeg>>>, totalSec: Double, out: File) {
        val rubber = hasRubberband()
        val g = StringBuilder()
        val labels = ArrayList<String>()
        inputs.forEachIndexed { i, (_, segs) ->
            val n = segs.size
            val playCount = segs.count { !it.silence }
            if (playCount > 0) {
                g.append("[$i:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo")
                if (playCount == 1) g.append("[b$i];") else {
                    g.append(",asplit=$playCount").append((0 until playCount).joinToString("") { "[b${i}_$it]" }).append(";")
                }
            }
            var pi = 0
            segs.forEachIndexed { j, s ->
                if (s.silence) {
                    g.append("anullsrc=r=44100:cl=stereo:d=${f(s.outDur)},aformat=sample_fmts=fltp[s${i}_$j];")
                } else {
                    val src = if (playCount == 1) "[b$i]" else "[b${i}_${pi++}]"
                    g.append("$src" + "atrim=start=${f(s.srcStart)}:end=${f(s.srcEnd)},asetpts=PTS-STARTPTS,${tempo(s.rate, rubber)}," +
                        "apad=whole_dur=${f(s.outDur)},atrim=0:${f(s.outDur)},asetpts=PTS-STARTPTS[s${i}_$j];")
                }
            }
            g.append((0 until n).joinToString("") { "[s${i}_$it]" }).append("concat=n=$n:v=0:a=1[a$i];")
            labels += "[a$i]"
        }
        val last = if (labels.size == 1) labels[0] else {
            g.append(labels.joinToString("")).append("amix=inputs=${labels.size}:duration=longest:normalize=0[mix];"); "[mix]"
        }
        g.append("$last" + "apad=whole_dur=${f(totalSec)},atrim=0:${f(totalSec)}[out]")

        val args = ArrayList<String>().apply {
            add("-y"); inputs.forEach { add("-i"); add(it.first.absolutePath) }
            add("-filter_complex"); add(g.toString()); add("-map"); add("[out]")
            addAll(listOf("-c:a", "aac", "-b:a", "192k", "-ar", "44100", "-ac", "2", out.absolutePath))
        }
        AppLog.d("ffmpeg audio: ${inputs.sumOf { it.second.size }} segments, rubberband=$rubber")
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode)) {
            val tail = (session.output ?: "").takeLast(1500)
            AppLog.e("ffmpeg failed rc=${session.returnCode}\n$tail")
            throw RenderError("Audio render failed (ffmpeg rc=${session.returnCode}). See log.")
        }
    }
}
