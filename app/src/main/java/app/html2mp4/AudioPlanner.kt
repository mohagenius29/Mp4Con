package app.html2mp4

import kotlin.math.abs

/** One log row per (frame, audio element), produced by the virtual clock's step(). */
data class StepLog(val frame: Int, val posBefore: Double, val posAfter: Double, val playing: Boolean)

/** silence=true -> outDur seconds of silence. Otherwise play source [srcStart,srcEnd] squeezed into outDur (rate = len/outDur). */
data class AudioSeg(val silence: Boolean, val srcStart: Double, val srcEnd: Double, val outDur: Double) {
    val rate: Double get() = if (silence) 1.0 else (srcEnd - srcStart) / outDur
}

/**
 * Turns the per-frame source positions recorded under the virtual clock into a short list of constant-rate
 * segments. Output interval k (time k/fps .. (k+1)/fps) is what advanced during step k+1.
 */
object AudioPlanner {
    fun plan(steps: List<StepLog>, fps: Int, totalFrames: Int, maxGroup: Int = 6): List<AudioSeg> {
        val byFrame = HashMap<Int, StepLog>().also { m -> steps.forEach { m[it.frame] = it } }
        fun at(k: Int): StepLog? = byFrame[k + 1]
        fun isPlay(s: StepLog?) = s != null && s.playing && s.posAfter > s.posBefore + 1e-9
        val frameDur = 1.0 / fps
        val out = ArrayList<AudioSeg>()
        var k = 0
        while (k < totalFrames) {
            val first = at(k)
            if (!isPlay(first)) {
                var n = 1
                while (k + n < totalFrames && !isPlay(at(k + n))) n++
                out += AudioSeg(true, 0.0, 0.0, n * frameDur); k += n
            } else {
                var n = 1; var last = first!!
                while (k + n < totalFrames && n < maxGroup) {
                    val nx = at(k + n)
                    if (!isPlay(nx) || abs(nx!!.posBefore - last.posAfter) > 1e-4) break
                    last = nx; n++
                }
                out += AudioSeg(false, first.posBefore, last.posAfter, n * frameDur); k += n
            }
        }
        return out
    }
}
