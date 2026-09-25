package dev.nihildigit.danmaku

import kotlin.math.abs

/**
 * 把按固定步长更新的播放位置抹成逐帧连续的一条线。
 *
 * 弹幕的横坐标是位置的直接函数,位置抖多少弹幕就跳多少。多数播放器的位置读数是台阶:ExoPlayer
 * 播放中每 10ms 更新一格,以 120Hz 的帧间隔去采样,每隔几帧就有一帧不动,满帧率也看得出顿挫。
 *
 * 做法是一个 alpha-beta 滤波:每帧先按估计的速率外推,再把估计往读数拉一点,速率按残差修正。速率是从读数里估出来的,不是请求的倍速:变速之后的几百毫秒里,
 * 播放器还在放按旧倍速缓冲好的音频,请求值与实际速率不是同一个数,拿请求值外推会跑到画面前面
 * 再被拽回来。滤波器在这段里平滑地追上去,不跳。
 *
 * 为什么不拿视频帧的送显时刻当锚点:实测 ExoPlayer 在加速的那一刻就按新倍速排视频帧,而音频
 * 还按旧倍速放缓冲,画面先超前一百多毫秒、再往回跳,锚在视频帧上会把这一跳原样搬到弹幕上。
 * 音频驱动的位置才是主时钟。
 *
 * 读数与估计相差超过 [RESYNC_THRESHOLD_MILLIS] 视为跳转,直接对齐;暂停时直接用读数。
 */
class SmoothedDanmakuClock(private val source: DanmakuClock) : DanmakuClock {

    override val positionMillis: Long get() = source.positionMillis
    override val isPlaying: Boolean get() = source.isPlaying
    override val playbackSpeed: Float get() = source.playbackSpeed

    private var estimate = 0.0
    private var rate = 0.0
    private var lastFrameNanos = Long.MIN_VALUE
    private var lastOutput = 0L

    override fun positionAtFrame(frameTimeNanos: Long): Long {
        // 普通弹幕层与定位弹幕层共用一个时钟,同一帧会各问一次,第二次原样返回。
        if (frameTimeNanos == lastFrameNanos) return lastOutput
        val reported = source.positionAtFrame(frameTimeNanos)
        val previousFrame = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        val dtMillis = (frameTimeNanos - previousFrame) / 1_000_000.0
        if (!source.isPlaying || previousFrame == Long.MIN_VALUE || dtMillis <= 0.0 || dtMillis > MAX_FRAME_GAP_MILLIS) {
            return resync(reported)
        }
        val predicted = estimate + rate * dtMillis
        val residual = reported - predicted
        if (abs(residual) > RESYNC_THRESHOLD_MILLIS) return resync(reported)
        // 增益按 120Hz 的帧间隔标定。帧间隔变长时同样的时间里更新次数少,按比例放大,
        // 让收敛快慢以时间而不是帧数计。
        val scale = (dtMillis / REFERENCE_FRAME_MILLIS).coerceAtMost(1.0 / POSITION_GAIN)
        estimate = predicted + POSITION_GAIN * scale * residual
        rate += RATE_GAIN * scale * residual / dtMillis
        // 播放中弹幕不许往回走。增益选在不振荡的一侧,这一行是给没测到的情形兜底。
        lastOutput = maxOf(lastOutput, estimate.toLong())
        return lastOutput
    }

    private fun resync(reported: Long): Long {
        estimate = reported.toDouble()
        rate = source.playbackSpeed.toDouble()
        lastOutput = reported
        return reported
    }

    private companion object {
        /**
         * 两个增益是拿两段真机日志离线回放挑的(120Hz,ExoPlayer,各含一次 1 → 2.5 → 1 倍速的
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

        /** 超过它就是 seek、换条目这类跳转。变速过渡期的偏差实测在它一半以内。 */
        const val RESYNC_THRESHOLD_MILLIS = 120.0

        /** 帧与帧隔得太久(帧循环挂起过),估计已经没有意义,直接对齐。 */
        const val MAX_FRAME_GAP_MILLIS = 100.0
    }
}
