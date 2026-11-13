package app.html2mp4

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Concatenates cached video segments into the final MP4. Samples are copied as they are, nothing is re-encoded. */
object Mux {
    class Seg(val file: File, val startFrame: Int)

    private fun track(ex: MediaExtractor): Int =
        (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }

    fun run(segs: List<Seg>, fps: Int, out: File) {
        require(segs.isNotEmpty()) { "no video segments" }
        AppLog.d("mux: ${segs.size} video segment(s)")
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val buf = ByteBuffer.allocateDirect(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        try {
            var vTrack = -1
            for (seg in segs) {
                val ex = MediaExtractor().apply { setDataSource(seg.file.absolutePath) }
                try {
                    val ti = track(ex)
                    if (vTrack < 0) { vTrack = muxer.addTrack(ex.getTrackFormat(ti)); muxer.start() }
                    ex.selectTrack(ti)
                    val off = seg.startFrame * 1_000_000L / fps
                    while (true) {
                        val t = ex.sampleTime
                        if (t < 0) break
                        buf.clear()
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        info.set(0, size, t + off, flags)
                        muxer.writeSampleData(vTrack, buf, info)
                        ex.advance()
                    }
                } finally { ex.release() }
            }
            muxer.stop()
        } finally { runCatching { muxer.release() } }
    }
}
