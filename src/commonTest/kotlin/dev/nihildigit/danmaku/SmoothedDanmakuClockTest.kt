package dev.nihildigit.danmaku

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmoothedDanmakuClockTest {

    /** 模拟按固定媒体步长刷新的播放器读数(ExoPlayer 10ms、mpv 24fps 41.67ms 等)。 */
    private class SteppedClock(private val stepMillis: Double = 10.0) : DanmakuClock {
        var trueMillis = 0.0
        var offsetMillis = 0L
        override var isPlaying = true
        override var playbackSpeed = 1f
        override val positionMillis: Long
            get() = ((trueMillis / stepMillis).toLong() * stepMillis).toLong() + offsetMillis
    }

    private val frameNanos120 = 1_000_000_000L / 120
    private val frameNanos60 = 1_000_000_000L / 60

    @Test
    fun `十毫秒台阶的读数按帧采样后每帧位移均匀`() {
        val source = SteppedClock(stepMillis = 10.0)
        val clock = SmoothedDanmakuClock(source)
        val steps = mutableListOf<Long>()
        var last = 0L
        for (frame in 0 until 600) {
            source.trueMillis = frame * 1000.0 / 120
            val position = clock.positionAtFrame(frame * frameNanos120)
            if (frame > 240) steps += position - last
            last = position
        }
        // 理想是每帧 8.33ms;取整后只能是 8 或 9。台阶直接透出来时会出现 0、10、20。
        assertTrue(steps.all { it in 8L..9L }, "每帧位移不均匀:${steps.groupingBy { it }.eachCount()}")
    }

    @Test
    fun `桌面端 24fps 粗刻度在 60Hz 与 120Hz 下亚毫秒步长连续均匀`() {
        // mpv 播 24fps 视频时 time-pos 约每 41.67ms 才跳一格。
        run {
            val source = SteppedClock(stepMillis = 1000.0 / 24.0)
            val clock = SmoothedDanmakuClock(source)
            val deltas = mutableListOf<Double>()
            var last = 0.0
            for (frame in 0 until 360) {
                source.trueMillis = frame * 1000.0 / 60.0
                val pos = clock.precisePositionAtFrame(frame * frameNanos60)
                if (frame > 90) deltas += pos - last
                last = pos
            }
            assertTrue(
                deltas.all { it in 15.8..17.5 },
                "60Hz 下 24fps 刻度出现锯齿步长: min=${deltas.min()} max=${deltas.max()}",
            )
        }

        run {
            val source = SteppedClock(stepMillis = 1000.0 / 24.0)
            val clock = SmoothedDanmakuClock(source)
            val deltas = mutableListOf<Double>()
            var last = 0.0
            for (frame in 0 until 720) {
                source.trueMillis = frame * 1000.0 / 120.0
                val pos = clock.precisePositionAtFrame(frame * frameNanos120)
                if (frame > 180) deltas += pos - last
                last = pos
            }
            assertTrue(
                deltas.all { it in 7.8..8.9 },
                "120Hz 下 24fps 刻度出现锯齿步长: min=${deltas.min()} max=${deltas.max()}",
            )
        }
    }

    @Test
    fun `慢倍速 0_5x 下 24fps 刻度不会误触发停顿冻结`() {
        val source = SteppedClock(stepMillis = 1000.0 / 24.0).apply { playbackSpeed = 0.5f }
        val clock = SmoothedDanmakuClock(source)
        val deltas = mutableListOf<Double>()
        var last = 0.0
        for (frame in 0 until 720) {
            source.trueMillis = frame * (1000.0 / 120.0) * 0.5
            val pos = clock.precisePositionAtFrame(frame * frameNanos120)
            if (frame > 180) deltas += pos - last
            last = pos
        }
        // 120Hz 0.5x 理想每帧 4.167ms,绝不能在 83.3ms 视频帧间隔中途冻成 0。
        assertTrue(
            deltas.all { it in 3.7..4.6 },
            "0.5x 慢放下步长异常或误判停顿: min=${deltas.min()} max=${deltas.max()}",
        )
    }

    @Test
    fun `播放中读数卡死转入停顿保持态且恢复后不回退`() {
        val source = SteppedClock(stepMillis = 1000.0 / 24.0)
        val clock = SmoothedDanmakuClock(source)
        var last = 0.0

        // 正常播放 2 秒到 2000ms。
        for (frame in 0 until 240) {
            source.trueMillis = frame * 1000.0 / 120.0
            last = clock.precisePositionAtFrame(frame * frameNanos120)
        }

        // 模拟网络缓冲卡住 1.5 秒(isPlaying 仍为 true,但读数停在原地不动)。
        val frozenMillis = source.trueMillis
        for (frame in 240 until 420) {
            source.trueMillis = frozenMillis
            val pos = clock.precisePositionAtFrame(frame * frameNanos120)
            assertTrue(pos >= last, "卡顿期间出现回退: $pos < $last")
            assertTrue(pos - source.positionMillis < 120.0, "卡顿期间无限外推冲破了重同步阈值: $pos vs ${source.positionMillis}")
            last = pos
        }

        // 恢复推进后平滑跟上,全程单调不倒退。
        for (frame in 420 until 600) {
            source.trueMillis = frozenMillis + (frame - 419) * (1000.0 / 120.0)
            val pos = clock.precisePositionAtFrame(frame * frameNanos120)
            assertTrue(pos >= last, "恢复播放时位置倒退: $pos < $last")
            last = pos
        }
        assertTrue(abs(last - source.trueMillis) < 25.0, "恢复播放后未能重新收敛到真实时间: $last vs ${source.trueMillis}")
    }

    @Test
    fun `同一帧内混调浮点与整数接口幂等`() {
        val source = SteppedClock(stepMillis = 10.0)
        val clock = SmoothedDanmakuClock(source)
        clock.precisePositionAtFrame(0L)
        source.trueMillis = 8.33
        val precise = clock.precisePositionAtFrame(frameNanos120)
        val integral = clock.positionAtFrame(frameNanos120)
        val preciseAgain = clock.precisePositionAtFrame(frameNanos120)
        assertEquals(precise, preciseAgain)
        assertEquals(precise.toLong(), integral)
    }

    @Test
    fun `24fps 粗刻度下长按加速再松手全程单调平滑收敛`() {
        val source = SteppedClock(stepMillis = 1000.0 / 24.0)
        val clock = SmoothedDanmakuClock(source)
        var truePos = 0.0
        var last = 0.0
        for (frame in 0 until 720) {
            val speed = if (frame in 180 until 480) 2.5 else 1.0
            source.playbackSpeed = speed.toFloat()
            if (frame > 0) truePos += (1000.0 / 120.0) * speed
            source.trueMillis = truePos
            val pos = clock.precisePositionAtFrame(frame * frameNanos120)
            assertTrue(pos >= last, "变速过渡出现位置倒退: frame=$frame $pos < $last")
            last = pos
        }
        assertTrue(abs(last - truePos) < 35.0, "松手恢复 1x 后未收敛回真实进度: $last vs $truePos")
    }

    @Test
    fun `跳转之后立刻对齐到读数`() {
        val source = SteppedClock()
        val clock = SmoothedDanmakuClock(source)
        for (frame in 0 until 120) {
            source.trueMillis = frame * 1000.0 / 120
            clock.positionAtFrame(frame * frameNanos120)
        }
        source.offsetMillis = 10_000L
        source.trueMillis = 120 * 1000.0 / 120
        assertEquals(source.positionMillis, clock.positionAtFrame(120 * frameNanos120))
    }

    @Test
    fun `高倍速下读数卡死遇上中等掉帧不会回退重同步`() {
        val source = SteppedClock(stepMillis = 10.0).apply {
            trueMillis = 1000.0
            playbackSpeed = 3f
        }
        val clock = SmoothedDanmakuClock(source)
        val p0 = clock.precisePositionAtFrame(0L)
        // t=16.67ms 读数未变,3x 外推到约 1050ms。
        val p1 = clock.precisePositionAtFrame(frameNanos60)
        // t=66.67ms 发生 50ms 掉帧且读数仍停在 1000ms:绝不能因外推越过 120ms 而回退到 1000ms。
        val p2 = clock.precisePositionAtFrame(frameNanos60 + 50_000_000L)
        assertTrue(p1 >= p0 && p2 >= p1, "高倍速卡顿遇到帧间隔抖动时发生了回退: $p0 -> $p1 -> $p2")
        assertTrue(p2 - 1000.0 < 120.0, "高倍速卡顿时外推未受停顿上限约束: $p2")
    }

    @Test
    fun `短暂停恢复不会把暂停墙钟时长外推进播放进度`() {
        // 覆盖两条路径:暂停期间被采样过,以及暂停期间帧循环挂起仅通知 onDiscontinuity。
        run {
            val source = SteppedClock(stepMillis = 10.0).apply { trueMillis = 1000.0 }
            val clock = SmoothedDanmakuClock(source)
            assertEquals(1000.0, clock.precisePositionAtFrame(0L))
            source.isPlaying = false
            assertEquals(1000.0, clock.precisePositionAtFrame(8_000_000L))
            source.isPlaying = true
            // 仅暂停了 72ms(< 100ms),恢复首帧仍停在 1000ms,不能外推成 1072ms。
            assertEquals(1000.0, clock.precisePositionAtFrame(80_000_000L))
        }

        run {
            val source = SteppedClock(stepMillis = 10.0).apply { trueMillis = 1000.0 }
            val clock = SmoothedDanmakuClock(source)
            assertEquals(1000.0, clock.precisePositionAtFrame(0L))
            // 模拟 DanmakuHostState 在短暂停期间挂起未调 precisePositionAtFrame,仅发出 onDiscontinuity。
            clock.onDiscontinuity()
            assertEquals(1000.0, clock.precisePositionAtFrame(80_000_000L))
        }
    }

    @Test
    fun `小于重同步阈值的短距离回退跳转立即对齐`() {
        val source = SteppedClock(stepMillis = 1.0).apply { trueMillis = 1000.0 }
        val clock = SmoothedDanmakuClock(source)
        assertEquals(1000.0, clock.precisePositionAtFrame(0L))
        // 往回 seek 50ms(< 120ms),不能被单调夹紧锁死在 1000ms。
        source.trueMillis = 950.0
        assertEquals(950.0, clock.precisePositionAtFrame(frameNanos60))
    }

    @Test
    fun `非法倍速不污染状态且恢复正常倍速后继续推进`() {
        val source = SteppedClock(stepMillis = 10.0).apply {
            trueMillis = 1000.0
            playbackSpeed = Float.NaN
        }
        val clock = SmoothedDanmakuClock(source)
        val p0 = clock.precisePositionAtFrame(0L)
        val p1 = clock.precisePositionAtFrame(frameNanos60)
        assertTrue(p0.isFinite() && p1.isFinite(), "NaN 倍速污染了时钟输出: p0=$p0 p1=$p1")

        source.playbackSpeed = 1f
        source.trueMillis = 1033.0
        val p2 = clock.precisePositionAtFrame(2 * frameNanos60)
        assertTrue(p2.isFinite() && p2 > p1, "倍速恢复正常后未能继续推进: p1=$p1 p2=$p2")
    }
}
