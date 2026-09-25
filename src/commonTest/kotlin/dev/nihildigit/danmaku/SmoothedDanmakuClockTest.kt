package dev.nihildigit.danmaku

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class SmoothedDanmakuClockTest {

    /** 播放器那样的读数:位置每 10ms 才更新一格。 */
    private class SteppedClock : DanmakuClock {
        var trueMillis = 0.0
        var offsetMillis = 0L
        override val positionMillis: Long get() = (trueMillis / 10).toLong() * 10 + offsetMillis
        override val isPlaying = true
        override val playbackSpeed = 1f
    }

    private val frameNanos = 1_000_000_000L / 120

    @Test
    fun `十毫秒台阶的读数按帧采样后每帧位移均匀`() {
        val source = SteppedClock()
        val clock = SmoothedDanmakuClock(source)
        val steps = mutableListOf<Long>()
        var last = 0L
        for (frame in 0 until 600) {
            source.trueMillis = frame * 1000.0 / 120
            val position = clock.positionAtFrame(frame * frameNanos)
            if (frame > 240) steps += position - last
            last = position
        }
        // 理想是每帧 8.33ms;取整后只能是 8 或 9。台阶直接透出来时会出现 0、10、20。
        assertTrue(steps.all { it in 8L..9L }, "每帧位移不均匀:${steps.groupingBy { it }.eachCount()}")
    }

    @Test
    fun `跳转之后立刻对齐到读数`() {
        val source = SteppedClock()
        val clock = SmoothedDanmakuClock(source)
        for (frame in 0 until 120) {
            source.trueMillis = frame * 1000.0 / 120
            clock.positionAtFrame(frame * frameNanos)
        }
        source.offsetMillis = 10_000L
        source.trueMillis = 120 * 1000.0 / 120
        assertEquals(source.positionMillis, clock.positionAtFrame(120 * frameNanos))
    }
}
