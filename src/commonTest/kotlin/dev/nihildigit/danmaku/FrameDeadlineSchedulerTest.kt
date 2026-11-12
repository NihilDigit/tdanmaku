package dev.nihildigit.danmaku

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * 帧率上限的验收标准是"长期平均接近目标",不是"每两帧出一帧"——面板刷新率不是目标帧率的
 * 整数倍时,任何"隔 N 帧"或"距上次输出够久了"的写法都会稳定偏低(144Hz 选 60 实际约 48,
 * 90Hz 约 45,165Hz 约 55)。所以这里喂真实刷新率的 vsync 序列,断言平均值。
 */
class FrameDeadlineSchedulerTest {

    private fun vsyncSeries(refreshHz: Double, seconds: Double): List<Long> {
        val periodNanos = 1_000_000_000.0 / refreshHz
        val count = (refreshHz * seconds).toInt()
        return (0 until count).map { (it * periodNanos).toLong() }
    }

    private fun averageFps(refreshHz: Double, cap: DanmakuFrameRateCap, seconds: Double = 10.0): Double {
        val scheduler = FrameDeadlineScheduler(cap.frameIntervalNanos)
        val frames = vsyncSeries(refreshHz, seconds)
        val drawn = frames.count { scheduler.shouldDraw(it) }
        return drawn / seconds
    }

    @Test
    fun `非整数倍面板上长期平均帧率接近目标`() {
        // 容差 1fps:相位对齐后每秒的取整误差最多一帧。
        listOf(90.0, 144.0, 165.0, 120.0, 60.0).forEach { hz ->
            val fps60 = averageFps(hz, DanmakuFrameRateCap.FPS_60)
            assertTrue(kotlin.math.abs(fps60 - 60.0) <= 1.0, "${hz}Hz 上 60fps 档实际只有 $fps60")

            val fps30 = averageFps(hz, DanmakuFrameRateCap.FPS_30)
            assertTrue(kotlin.math.abs(fps30 - 30.0) <= 1.0, "${hz}Hz 上 30fps 档实际只有 $fps30")
        }
    }

    @Test
    fun `面板刷新率低于目标时出满每一帧`() {
        val scheduler = FrameDeadlineScheduler(DanmakuFrameRateCap.FPS_60.frameIntervalNanos)
        val frames = vsyncSeries(30.0, seconds = 1.0)
        assertEquals(frames.size, frames.count { scheduler.shouldDraw(it) })
    }

    @Test
    fun `跟随屏幕档不跳帧`() {
        val scheduler = FrameDeadlineScheduler(DanmakuFrameRateCap.DISPLAY.frameIntervalNanos)
        val frames = vsyncSeries(144.0, seconds = 1.0)
        assertEquals(frames.size, frames.count { scheduler.shouldDraw(it) })
    }

    @Test
    fun `挂起归来不补画积压的帧`() {
        val scheduler = FrameDeadlineScheduler(DanmakuFrameRateCap.FPS_60.frameIntervalNanos)
        scheduler.shouldDraw(0L)

        // 帧循环挂起了 5 秒(暂停、屏上没有弹幕),回来后按 144Hz 继续。
        val resumeNanos = 5_000_000_000L
        val periodNanos = (1_000_000_000.0 / 144.0).toLong()
        val drawn = (0 until 144).count { scheduler.shouldDraw(resumeNanos + it * periodNanos) }

        assertTrue(drawn <= 61, "挂起归来后连出了 $drawn 帧,把空档当成了欠账")
    }

    @Test
    fun `60Hz 唤醒时刻抖动下不会退化成 30fps`() {
        val periodNanos = 1_000_000_000.0 / 60.0
        // 同时验证首帧零偏移和首帧非零相位(+1.6ms 先到、下一帧 -1.6ms 后到)两种常见桌面端 EDT/D3D 抖动。
        val jitterPatterns = listOf(
            longArrayOf(0L, -1_600_000L, 1_500_000L, -1_200_000L, 1_400_000L, -900_000L, 800_000L),
            longArrayOf(1_600_000L, -1_600_000L, 1_500_000L, -1_500_000L, 1_200_000L, -1_200_000L),
        )
        val totalFrames = 600
        for (pattern in jitterPatterns) {
            val scheduler = FrameDeadlineScheduler(DanmakuFrameRateCap.FPS_60.frameIntervalNanos)
            val drawn = (0 until totalFrames).count { index ->
                val ideal = (index * periodNanos).toLong()
                val jitter = pattern[index % pattern.size]
                scheduler.shouldDraw(ideal + jitter)
            }
            assertEquals(totalFrames, drawn, "60Hz 带 ±1.6ms 抖动时漏帧: $drawn / $totalFrames")
        }
    }

    @Test
    fun `偶发掉帧后跨过过期刻度且不连续补帧`() {
        val period144 = (1_000_000_000.0 / 144.0).toLong()
        // 覆盖 30ms(刚跨过 1.8 个周期的中等卡顿)与 45ms(跨过 2.7 个周期)两档掉帧,
        // 恢复后的紧邻 144Hz vsync(仅隔 6.94ms)绝不能为了偿还旧网格立刻双连画。
        for (hiccupNanos in listOf(30_000_000L, 45_000_000L)) {
            val scheduler = FrameDeadlineScheduler(DanmakuFrameRateCap.FPS_60.frameIntervalNanos)
            assertTrue(scheduler.shouldDraw(0L))
            assertTrue(scheduler.shouldDraw(hiccupNanos))
            assertEquals(false, scheduler.shouldDraw(hiccupNanos + period144), "${hiccupNanos / 1_000_000}ms 卡顿后发生了紧邻补帧")
        }
    }

    @Test
    fun `重复帧时间戳与极值边界安全处理`() {
        val scheduler = FrameDeadlineScheduler(DanmakuFrameRateCap.FPS_60.frameIntervalNanos)
        assertTrue(scheduler.shouldDraw(Long.MIN_VALUE))
        assertEquals(false, scheduler.shouldDraw(Long.MIN_VALUE))
        assertTrue(scheduler.shouldDraw(Long.MIN_VALUE + 17_000_000L))
    }

    @Test
    fun `超高刷面板提前容差触发后不误判时间倒退`() {
        listOf(240.0, 480.0).forEach { hz ->
            val fps60 = averageFps(hz, DanmakuFrameRateCap.FPS_60)
            assertTrue(kotlin.math.abs(fps60 - 60.0) <= 1.0, "${hz}Hz 上 60fps 档偏离目标: $fps60")
        }
    }
}
