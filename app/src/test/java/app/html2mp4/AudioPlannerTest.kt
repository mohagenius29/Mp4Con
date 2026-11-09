package app.html2mp4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioPlannerTest {
    private val fps = 30

    /** Audio starts at frame 3 (play() called in frame 3's callbacks), constant rate 1. */
    @Test fun silenceThenConstantRate() {
        val steps = (1..60).map { f ->
            val started = f >= 4                                  // step 4 is the first to advance audio
            val before = if (started) (f - 4) / fps.toDouble() else 0.0
            StepLog(f, before, if (started) before + 1.0 / fps else 0.0, started)
        }
        val plan = AudioPlanner.plan(steps, fps, 50)
        assertTrue(plan.first().silence)
        assertEquals(3.0 / fps, plan.first().outDur, 1e-9)       // intervals 0..2 are silent
        val total = plan.sumOf { it.outDur }
        assertEquals(50.0 / fps, total, 1e-9)
        plan.filter { !it.silence }.forEach { assertEquals(1.0, it.rate, 1e-6) }
    }

    /** Playback rate 2x: source advances 2/fps per frame, so each segment's rate must be 2. */
    @Test fun doubleRate() {
        val steps = (1..30).map { f -> StepLog(f, (f - 1) * 2.0 / fps, f * 2.0 / fps, true) }
        val plan = AudioPlanner.plan(steps, fps, 20)
        assertTrue(plan.none { it.silence })
        plan.forEach { assertEquals(2.0, it.rate, 1e-6) }
        assertEquals(20.0 / fps, plan.sumOf { it.outDur }, 1e-9)
        assertTrue(plan.all { it.outDur <= 6.0 / fps + 1e-9 })   // grouped at most 6 frames
    }

    /** A seek (currentTime set by the page) must break the group. */
    @Test fun seekSplitsSegment() {
        val steps = listOf(StepLog(1, 0.0, 0.1, true), StepLog(2, 0.1, 0.2, true), StepLog(3, 5.0, 5.1, true), StepLog(4, 5.1, 5.2, true))
        val plan = AudioPlanner.plan(steps, fps, 4)
        assertEquals(2, plan.size)
        assertTrue(abs(plan[1].srcStart - 5.0) < 1e-9)
    }
}
