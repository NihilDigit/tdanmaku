package dev.nihildigit.danmaku

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 定位弹幕是时间的纯函数,这组测试断言的是那条曲线在几个**接缝**上的取值:寿命两端、位移延迟
 * 前后、位移结束之后。中间段的线性插值不再逐点复述——那是把实现里那行 lerp 抄一遍。
 */
class SpecialDanmakuMotionTest {

    private val sample = SpecialDanmaku(
        id = "1",
        text = "hi",
        color = 0xFFFFFF,
        startTimeMillis = 1000,
        durationMillis = 4000,
        fromX = 0f,
        fromY = 0f,
        toX = 1f,
        toY = 1f,
        translationDelayMillis = 1000,
        translationDurationMillis = 2000,
        fromAlpha = 1f,
        toAlpha = 0f,
    )

    @Test
    fun `寿命区间左闭右开`() {
        assertNull(sample.motionAt(999))
        assertEquals(1f, sample.motionAt(1000)!!.alpha, EPSILON)
        // 5000 是 start + duration,这一刻弹幕已经不在了——闭区间会让它多显示一帧,
        // 也会让"上一条刚走、下一条已到"的时刻同时存在两条。
        assertNull(sample.motionAt(5000))
    }

    @Test
    fun `延迟期内停在起点 但 alpha 已经在走`() {
        val motion = sample.motionAt(1500)!!
        assertEquals(0f, motion.x, EPSILON)
        assertEquals(0f, motion.y, EPSILON)
        // alpha 铺满整个寿命,不受位移延迟影响:4000ms 里过了 500ms。
        assertEquals(0.875f, motion.alpha, EPSILON)
    }

    @Test
    fun `位移结束后停在终点 不回弹也不继续外推`() {
        assertEquals(1f, sample.motionAt(4000)!!.x, EPSILON)
        assertEquals(1f, sample.motionAt(4999)!!.y, EPSILON)
    }

    @Test
    fun `位移时长为 0 时 延迟一到就跳到终点`() {
        val instant = sample.copy(translationDurationMillis = 0)
        assertEquals(0f, instant.motionAt(1999)!!.x, EPSILON)
        assertEquals(1f, instant.motionAt(2000)!!.x, EPSILON)
    }

    @Test
    fun `easeInCubic 起步慢于线性`() {
        // 位移过半时,easeInCubic 的进度是 0.5³ = 0.125,线性是 0.5。这两个数是判断
        // "缓动到底有没有接上"的唯一可观察差别。
        val eased = sample.copy(easing = SpecialDanmakuEasing.EASE_IN_CUBIC)
        assertEquals(0.125f, eased.motionAt(3000)!!.x, EPSILON)
        assertEquals(0.5f, sample.motionAt(3000)!!.x, EPSILON)
    }

    @Test
    fun `时长非正的弹幕在任何时刻都不存在`() {
        assertNull(sample.copy(durationMillis = 0).motionAt(1000))
        assertNull(sample.copy(durationMillis = -1).motionAt(1000))
    }

    @Test
    fun `亚毫秒浮点时间连续插值不截断`() {
        val atWhole = sample.motionAt(2500.0)!!
        val atHalfMs = sample.motionAt(2500.5)!!
        assertEquals(0.25f, atWhole.x, EPSILON)
        assertEquals(0.25025f, atHalfMs.x, EPSILON)
    }

    @Test
    fun `非有限浮点时间安全返回空`() {
        assertNull(sample.motionAt(Double.NaN))
        assertNull(sample.motionAt(Double.POSITIVE_INFINITY))
        assertNull(sample.motionAt(Double.NEGATIVE_INFINITY))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `暂停等待可被 notifyChanged 或池更新立即唤醒`() = runTest {
        val clock = object : DanmakuClock {
            override val positionMillis: Long = 1000L
            override val isPlaying: Boolean = false
            override val playbackSpeed: Float = 1f
        }
        val state = SpecialDanmakuHostState(clock)

        var resumedByNotify = false
        val waiter1 = launch {
            state.awaitWakeUp(100L)
            resumedByNotify = true
        }
        runCurrent()
        advanceTimeBy(10L)
        state.notifyChanged()
        runCurrent()
        assertTrue(resumedByNotify)
        waiter1.join()
        assertEquals(10L, testScheduler.currentTime)

        var resumedByPoolUpdate = false
        val waiter2 = launch {
            state.awaitWakeUp(100L)
            resumedByPoolUpdate = true
        }
        runCurrent()
        advanceTimeBy(15L)
        state.danmaku = listOf(sample)
        runCurrent()
        assertTrue(resumedByPoolUpdate)
        waiter2.join()
        assertEquals(25L, testScheduler.currentTime)
    }

    @Test
    fun `同桶内已结束的弹幕不会进入可见快照`() {
        val endedEarly = sample.copy(id = "early", startTimeMillis = 0L, durationMillis = 500L)
        val clock = object : DanmakuClock {
            override val positionMillis: Long = 1000L
            override val isPlaying: Boolean = false
            override val playbackSpeed: Float = 1f
        }
        val state = SpecialDanmakuHostState(clock)
        state.danmaku = listOf(endedEarly)

        val visible = ArrayList<SpecialDanmaku>()
        state.collectVisibleAt(1000.0, visible)
        // 粗分桶仍包含 [0, 500),但绑定同一采样时刻的可见快照必须为空,绝不残留旧弹幕。
        assertEquals(1, state.activeDanmaku.size)
        assertTrue(visible.isEmpty())

        // seek 回寿命区间内时快照立即恢复,再换池清空时同步归零。
        state.collectVisibleAt(250.0, visible)
        assertEquals(listOf(endedEarly), visible)
        state.danmaku = emptyList()
        state.collectVisibleAt(250.0, visible)
        assertTrue(visible.isEmpty())
    }

    @Test
    fun `播放空档期精准计算预热与进场唤醒延迟并在临近时切回 vsync`() {
        var nowMillis = 1000L
        val clock = object : DanmakuClock {
            override val positionMillis: Long get() = nowMillis
            override val isPlaying: Boolean = true
            override val playbackSpeed: Float = 1f
        }
        val state = SpecialDanmakuHostState(clock)
        val invalidZero = sample.copy(id = "zero", startTimeMillis = 1200L, durationMillis = 0L)
        val nearItem = sample.copy(id = "near", startTimeMillis = 2000L, durationMillis = 1000L)
        val farItem = sample.copy(id = "far", startTimeMillis = 10_000L, durationMillis = 1000L)

        // 远期弹幕(10_000ms):下一唤醒目标是预热起点 8_500ms,单次睡眠受 500ms 兜底上限约束。
        state.danmaku = listOf(invalidZero, farItem)
        assertEquals(8_500L, state.nextWakePositionMillis(1000.0, prewarmLookaheadMillis = 1500L))
        assertEquals(500L, state.computeIdleDelayMillis(1000.0, prewarmLookaheadMillis = 1500L))

        // 已处于预热窗口内的弹幕(2_000ms):唤醒目标直指进场时刻 2_000ms,临近 20ms 内返回 0 交还 vsync。
        state.danmaku = listOf(invalidZero, nearItem, farItem)
        assertEquals(2_000L, state.nextWakePositionMillis(1000.0, prewarmLookaheadMillis = 1500L))
        assertEquals(10L, state.computeIdleDelayMillis(1970.0, prewarmLookaheadMillis = 1500L))
        assertEquals(0L, state.computeIdleDelayMillis(1985.0, prewarmLookaheadMillis = 1500L))

        // 全部播完之后返回 null,按 500ms 兜底轮询挂起而不空转 vsync。
        nowMillis = 20_000L
        assertNull(state.nextWakePositionMillis(20_000.0, prewarmLookaheadMillis = 1500L))
        assertEquals(500L, state.computeIdleDelayMillis(20_000.0, prewarmLookaheadMillis = 1500L))
    }

    private companion object {
        const val EPSILON = 1e-4f
    }
}
