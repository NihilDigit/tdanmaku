package dev.nihildigit.danmaku

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 把按固定步长更新的播放位置抹成逐帧连续的一条线。
 *
 * 弹幕的横坐标是位置的直接函数,位置抖多少弹幕就跳多少。多数播放器的位置读数是台阶:ExoPlayer
 * 播放中每 10ms 更新一格,桌面端 mpv 的 `time-pos` 常按视频帧间隔(24fps 为 41.7ms、30fps 为
 * 33.3ms)跳一格;以 60/120/144Hz 的帧间隔去采样,直接读会出现连续多帧位置不动再猛跳一格的顿挫。
 *
 * 做法是采样感知的 alpha-beta 滤波:
 * - 在两次新读数之间的中间显示帧(读数未变且仍在正常刻度窗口内),只按当前估计速率平滑外推,
 *   不把尚未刷新的旧读数当作新测量喂回残差 —— 否则中间几帧会持续产生负残差把估计和速率往下拽,
 *   新刻度一到又跳回去,形成锯齿感。
 * - 当读数真正推进时,按本次观测距上次观测的真实间隔 `obsDtMillis` 缩放 120Hz 标定极点增益,
 *   并将等效步数上限收在 [MAX_GAIN_SCALE_Q],在保证动态收敛的同时压平 24/30fps 粗刻度与显示刷新率
 *   异步采样产生的拍频纹波。
 * - 播放中若读数超出正常视频帧刻度窗口仍一动不动(如网络缓冲卡住但 `isPlaying` 尚未翻假),转入
 *   停顿保持态冻结外推,避免一路跑到 [RESYNC_THRESHOLD_MILLIS] 后触发回退跳变。
 *
 * 速率是从读数里估出来的,不是请求的倍速:变速之后的几百毫秒里,播放器还在放按旧倍速缓冲好的音频,
 * 请求值与实际速率不是同一个数,拿请求值外推会跑到画面前面再被拽回来。滤波器在这段里平滑地追上去,不跳。
 *
 * 为什么不拿视频帧的送显时刻当锚点:实测 ExoPlayer 在加速的那一刻就按新倍速排视频帧,而音频
 * 还按旧倍速放缓冲,画面先超前一百多毫秒、再往回跳,锚在视频帧上会把这一跳原样搬到弹幕上。
 * 音频驱动的位置才是主时钟。
 *
 * 读数与估计相差超过 [RESYNC_THRESHOLD_MILLIS] 或读数倒退视为跳转,直接对齐;暂停时直接用读数。
 */
class SmoothedDanmakuClock(private val source: DanmakuClock) : DanmakuClock {

    override val positionMillis: Long get() = source.positionMillis
    override val isPlaying: Boolean get() = source.isPlaying
    override val playbackSpeed: Float get() = source.playbackSpeed

    private var estimate = 0.0
    private var rate = 0.0
    private var lastFrameNanos = 0L
    private var lastOutputMillis = 0.0
    private var lastObservedMillis = 0L
    private var lastObservationNanos = 0L
    private var initialized = false
    private var wasPlaying = false
    private var stalled = false

    override fun onDiscontinuity() {
        initialized = false
        wasPlaying = false
        stalled = false
    }

    override fun positionAtFrame(frameTimeNanos: Long): Long =
        advanceToFrame(frameTimeNanos).toLong()

    override fun precisePositionAtFrame(frameTimeNanos: Long): Double =
        advanceToFrame(frameTimeNanos)

    private fun advanceToFrame(frameTimeNanos: Long): Double {
        // 普通弹幕层与定位弹幕层共用一个时钟,同一帧会各问一次(甚至分别调 Long 与 Double 接口),
        // 第二次原样返回同帧估计,绝不重复推进状态。
        if (initialized && frameTimeNanos == lastFrameNanos) return lastOutputMillis
        val reported = source.positionAtFrame(frameTimeNanos)
        if (!source.isPlaying) {
            return resync(reported, frameTimeNanos, playing = false)
        }
        if (!initialized || !wasPlaying || !estimate.isFinite() || !rate.isFinite()) {
            return resync(reported, frameTimeNanos, playing = true)
        }
        val deltaNanos = frameTimeNanos - lastFrameNanos
        if (deltaNanos <= 0L) {
            return resync(reported, frameTimeNanos, playing = true)
        }
        val frameDtMillis = deltaNanos / 1_000_000.0
        if (frameDtMillis > MAX_FRAME_GAP_MILLIS) {
            return resync(reported, frameTimeNanos, playing = true)
        }
        lastFrameNanos = frameTimeNanos

        // 正常正向播放中媒体读数不会倒退;短距离回退 seek(如 < 120ms)或回拨必须当场对齐,
        // 否则会被单调夹紧锁在旧进度上。
        if (reported < lastObservedMillis) {
            return resync(reported, frameTimeNanos, playing = true)
        }

        if (reported == lastObservedMillis) {
            if (stalled) return lastOutputMillis
            if (sanitizedSpeed() == 0.0) {
                rate = 0.0
                return lastOutputMillis
            }
            val unclampedPredicted = estimate + rate * frameDtMillis
            val obsDtMillis = (frameTimeNanos - lastObservationNanos) / 1_000_000.0
            if (obsDtMillis > STALL_WALL_MILLIS && unclampedPredicted - reported > STALL_MEDIA_LEAD_MILLIS) {
                stalled = true
                return lastOutputMillis
            }
            // 未收到新观测前绝不允许外推冲破 RESYNC_THRESHOLD_MILLIS,否则读数没动却先触发回退重同步。
            estimate = unclampedPredicted.coerceAtMost(maxOf(estimate, reported + MAX_UNOBSERVED_LEAD_MILLIS))
            lastOutputMillis = maxOf(lastOutputMillis, estimate)
            return lastOutputMillis
        }

        if (stalled) {
            // 从停顿恢复:卡顿期间经过的墙钟间隔不代表播放推进时长,重置观测起点与速率后温和衔接。
            stalled = false
            if (abs(reported - estimate) > RESYNC_THRESHOLD_MILLIS) {
                return resync(reported, frameTimeNanos, playing = true)
            }
            lastObservedMillis = reported
            lastObservationNanos = frameTimeNanos
            rate = sanitizedSpeed()
            val predicted = estimate + rate * frameDtMillis
            estimate = predicted + POSITION_GAIN * (reported - predicted)
            lastOutputMillis = maxOf(lastOutputMillis, estimate)
            return lastOutputMillis
        }

        val predicted = estimate + rate * frameDtMillis
        val residual = reported - predicted
        if (abs(residual) > RESYNC_THRESHOLD_MILLIS) {
            return resync(reported, frameTimeNanos, playing = true)
        }
        val obsDtMillis = ((frameTimeNanos - lastObservationNanos) / 1_000_000.0).coerceAtLeast(frameDtMillis)
        lastObservedMillis = reported
        lastObservationNanos = frameTimeNanos

        // 按真实观测间隔放大增益,但把等效步数上限卡在 1.5:24fps 视频帧刻度(41.7ms)与 60Hz 显示帧
        // (16.7ms)异步拍频时,每次新刻度落地仍带有最高半个显示帧(±8.3ms)的采样相位差;若无上限放大到
        // alpha ≈ 0.47,会把拍频抖动一把打进单帧位移里造成忽快忽慢,卡在 1.5(alpha ≈ 0.15)既压平拍频
        // 纹波,又保住二阶极点的阻尼比。
        val q = (obsDtMillis / REFERENCE_FRAME_MILLIS).coerceIn(MIN_GAIN_SCALE_Q, MAX_GAIN_SCALE_Q)
        val rPow = POLE_RADIUS.pow(q)
        val rSq = rPow * rPow
        val alpha = 1.0 - rSq
        val beta = 1.0 + rSq - 2.0 * rPow * cos(q * POLE_ANGLE)
        estimate = predicted + alpha * residual
        rate = (rate + beta * residual / obsDtMillis).coerceAtLeast(0.0)
        // 播放中弹幕不许往回走。
        lastOutputMillis = maxOf(lastOutputMillis, estimate)
        return lastOutputMillis
    }

    private fun sanitizedSpeed(): Double {
        val raw = source.playbackSpeed.toDouble()
        return if (raw.isFinite() && raw >= 0.0) raw else 0.0
    }

    private fun resync(reported: Long, frameTimeNanos: Long, playing: Boolean): Double {
        estimate = reported.toDouble()
        rate = if (playing) sanitizedSpeed() else 0.0
        lastFrameNanos = frameTimeNanos
        lastOutputMillis = estimate
        lastObservedMillis = reported
        lastObservationNanos = frameTimeNanos
        initialized = true
        wasPlaying = playing
        stalled = false
        return lastOutputMillis
    }

    private companion object {
        /**
         * 基准增益是拿两段真机日志离线回放挑的(120Hz,ExoPlayer,各含一次 1 → 2.5 → 1 倍速的
         * 长按):逐帧速率变化的标准差从读数本身的 1.37 和 0.95 降到 0.080 和 0.071,变速过渡里
         * 估计最多落后读数 75ms,全程连续、不回退。
         *
         * 速率增益再大就欠阻尼:0.05 时减速之后估计先冲过读数 50ms,速率掉到负值,弹幕往回挪。
         * 位置增益再大更贴读数,台阶就透出来。对最近一段读数做线性回归也试过,没有反馈就不会
         * 振荡,但同样的滞后下要粗糙一倍。
         */
        const val POSITION_GAIN = 0.1
        const val RATE_GAIN = 0.01
        const val REFERENCE_FRAME_MILLIS = 1000.0 / 120

        val POLE_RADIUS = sqrt(1.0 - POSITION_GAIN)
        val POLE_ANGLE = acos((2.0 - POSITION_GAIN - RATE_GAIN) / (2.0 * POLE_RADIUS))

        const val MIN_GAIN_SCALE_Q = 0.25
        const val MAX_GAIN_SCALE_Q = 1.5

        /**
         * 读数未变时的停顿判定双阈值:同时要求墙钟超过 28ms 且外推领先媒体读数超过 75ms(大于 24fps
         * 的 41.7ms 单帧步长,同时小于 [RESYNC_THRESHOLD_MILLIS]),兼顾 0.25× 慢放(墙钟长但媒体领先
         * 不超一帧)与 3× 快放(媒体领先快但正常帧间隔 < 15ms)。
         */
        const val STALL_WALL_MILLIS = 28.0
        const val STALL_MEDIA_LEAD_MILLIS = 75.0
        const val MAX_UNOBSERVED_LEAD_MILLIS = 100.0

        /** 超过它就是 seek、换条目这类跳转。变速过渡期的偏差实测在它一半以内。 */
        const val RESYNC_THRESHOLD_MILLIS = 120.0

        /** 帧与帧隔得太久(帧循环挂起过),估计已经没有意义,直接对齐。 */
        const val MAX_FRAME_GAP_MILLIS = 100.0
    }
}
