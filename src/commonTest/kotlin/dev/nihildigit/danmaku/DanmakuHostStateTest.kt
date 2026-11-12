package dev.nihildigit.danmaku

import androidx.compose.runtime.BroadcastFrameClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DanmakuHostStateTest {
    private class Clock : DanmakuClock {
        override var positionMillis = 372_833L
        override var isPlaying = true
        override val playbackSpeed = 3f
    }

    private fun timeline() = DanmakuTimeline(
        DanmakuLayoutConfig(canvasWidthPx = 400f, canvasHeightPx = 200f, trackHeightPx = 20f),
    ).apply {
        add(
            DanmakuFlightPlan(
                danmaku = Danmaku("pending", 372_834L, DanmakuMode.SCROLL, 0xffffff, "pending"),
                mode = DanmakuMode.SCROLL,
                track = 0,
                emitTimeMillis = 372_834L,
                durationMillis = 6_472L,
                visibleUntilMillis = 379_306L,
                speedPxPerMillis = 0.1f,
                widthPx = 70f,
                heightPx = 20f,
            ),
        )
    }

    @Test
    fun subMillisecondWaitYieldsAndResumesRendering() = runTest {
        val clock = Clock()
        val state = DanmakuHostState(clock, timeline())
        val frames = BroadcastFrameClock()
        var callbacks = 0
        var visibleIds = emptyList<String>()
        val job = launch(frames) {
            state.run { visible, _ ->
                // Fail a synchronous spin promptly instead of hanging the test runner.
                check(++callbacks < 20) { "Host loop did not suspend" }
                visibleIds = visible.map { it.danmaku.id }
            }
        }
        runCurrent()
        assertEquals(1, callbacks)
        advanceTimeBy(5)
        runCurrent()
        assertEquals(6, callbacks)
        assertTrue(visibleIds.isEmpty())

        clock.positionMillis = 372_834L
        advanceTimeBy(1)
        runCurrent()
        frames.sendFrame(6_000_000L)
        runCurrent()
        assertEquals(listOf("pending"), visibleIds)
        job.cancelAndJoin()
    }

    @Test
    fun wakeSignalIsConsumedAndCancellationCompletes() = runTest {
        val state = DanmakuHostState(Clock(), timeline())
        var callbacks = 0
        state.notifyChanged()
        val job = launch {
            state.run { _, _ ->
                check(++callbacks < 20) { "Host loop did not suspend" }
            }
        }
        runCurrent()
        assertEquals(2, callbacks)
        state.notifyChanged()
        runCurrent()
        assertEquals(3, callbacks)
        job.cancelAndJoin()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(3, callbacks)
    }

    @Test
    fun fractionalWallDelayRoundsUpInsteadOfWakingEarly() = runTest {
        // 334ms 媒体时间在 3x 下是 111.33ms 墙钟时间;向下取整会在 111ms(372_833ms)提前醒来多刷一帧空画布。
        val clock = Clock().apply { positionMillis = 372_500L }
        val state = DanmakuHostState(clock, timeline())
        var callbacks = 0
        val job = launch {
            state.run { _, _ ->
                callbacks++
            }
        }
        runCurrent()
        assertEquals(1, callbacks)

        advanceTimeBy(111)
        runCurrent()
        assertEquals(1, callbacks)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, callbacks)
        job.cancelAndJoin()
    }

    @Test
    fun runPrecisePreservesSubMillisecondClockEstimate() = runTest {
        val clock = object : DanmakuClock {
            override var positionMillis = 372_834L
            override var isPlaying = true
            override val playbackSpeed = 1f
            override fun precisePositionAtFrame(frameTimeNanos: Long): Double = 372_840.375
        }
        val state = DanmakuHostState(clock, timeline(), DanmakuFrameRateCap.DISPLAY)
        val frames = BroadcastFrameClock()
        val observed = mutableListOf<Double>()
        val job = launch(frames) {
            state.runPrecise { _, positionMillis ->
                observed += positionMillis
            }
        }
        runCurrent()
        frames.sendFrame(8_333_333L)
        runCurrent()
        assertEquals(372_840.375, observed.last())
        job.cancelAndJoin()
    }

    @Test
    fun notifyDiscontinuityResetsSmoothedClockAcrossShortPauseAndSmallForwardSeek() = runTest {
        val rawClock = object : DanmakuClock {
            override var positionMillis = 372_834L
            override var isPlaying = true
            override val playbackSpeed = 1f
        }
        val smoothed = SmoothedDanmakuClock(rawClock)
        val state = DanmakuHostState(smoothed, timeline(), DanmakuFrameRateCap.DISPLAY)
        val frames = BroadcastFrameClock()
        val observed = mutableListOf<Double>()
        val job = launch(frames) {
            state.runPrecise { _, positionMillis ->
                observed += positionMillis
            }
        }
        runCurrent()
        frames.sendFrame(0L)
        runCurrent()
        assertEquals(372_834.0, observed.last())

        // 两次 vsync 之间发生极短暂停并恢复(未被帧回调观察到暂停态),依赖 notifyDiscontinuity 截断外推。
        rawClock.isPlaying = false
        state.notifyDiscontinuity()
        rawClock.isPlaying = true
        state.notifyDiscontinuity()
        frames.sendFrame(60_000_000L)
        runCurrent()
        assertEquals(372_834.0, observed.last())

        // 小幅正向 seek(+50ms < 120ms),notifyDiscontinuity 后首帧立即对齐新读数而非平滑残差。
        rawClock.positionMillis = 372_884L
        state.notifyDiscontinuity()
        frames.sendFrame(76_000_000L)
        runCurrent()
        assertEquals(372_884.0, observed.last())
        job.cancelAndJoin()
    }
}
