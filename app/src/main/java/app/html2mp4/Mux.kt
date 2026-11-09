package app.html2mp4

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Concatenates cached video segments and interleaves the AAC track into the final MP4 (no re-encode). */
object Mux {
    class Seg(val file: File, val startFrame: Int)

    private fun track(ex: MediaExtractor, prefix: String): Int =
        (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(prefix) }

    fun run(segs: List<Seg>, fps: Int, audio: File?, durationUs: Long, out: File) {
        require(segs.isNotEmpty()) { "no video segments" }
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var vEx: MediaExtractor? = null
        var aEx: MediaExtractor? = null
        try {
            val first = MediaExtractor().apply { setDataSource(segs[0].file.absolutePath) }
            val vFmt = first.getTrackFormat(track(first, "video/")); first.release()
            val vTrack = muxer.addTrack(vFmt)
            var aTrack = -1
            if (audio != null) {
                aEx = MediaExtractor().apply { setDataSource(audio.absolutePath) }
                val ai = track(aEx, "audio/"); aEx.selectTrack(ai); aTrack = muxer.addTrack(aEx.getTrackFormat(ai))
            }
            muxer.start()

            var segI = -1; var off = 0L
            fun nextSeg(): Boolean {
                vEx?.release(); vEx = null
                segI++
                if (segI >= segs.size) return false
                off = segs[segI].startFrame * 1_000_000L / fps
                vEx = MediaExtractor().apply { setDataSource(segs[segI].file.absolutePath); selectTrack(track(this, "video/")) }
                return true
            }
            nextSeg()
            fun vTime(): Long { while (vEx != null && vEx!!.sampleTime < 0) if (!nextSeg()) return Long.MAX_VALUE; return if (vEx == null) Long.MAX_VALUE else vEx!!.sampleTime + off }
            fun aTime(): Long { val t = aEx?.sampleTime ?: -1; return if (t < 0 || t > durationUs) Long.MAX_VALUE else t }

            val buf = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val vt = vTime(); val at = aTime()
                if (vt == Long.MAX_VALUE && at == Long.MAX_VALUE) break
                val useVideo = vt <= at
                val ex = if (useVideo) vEx!! else aEx!!
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, if (useVideo) vt else at, flags)
                muxer.writeSampleData(if (useVideo) vTrack else aTrack, buf, info)
                ex.advance()
            }
            muxer.stop()
        } finally {
            vEx?.release(); aEx?.release(); runCatching { muxer.release() }
        }
    }
}
