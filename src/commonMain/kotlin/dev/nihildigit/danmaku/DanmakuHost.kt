package dev.nihildigit.danmaku

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * host 与播放器之间唯一的耦合点。
 *
 * [positionMillis] 每次调用都会被 host 的帧循环重新读取(见 [DanmakuHostState.run]),host
 * 自己不做任何插值/外推——**调用方要负责这个读数在播放中是逐帧连续、不是只在心跳/轮询间隔
 * 才更新的粗粒度值**。这不是可以随意选的实现细节:host 曾经内置过一层"锚点位置 + 经过时间 ×
 * 倍速"的外推,理由是"实现方可能只做粗粒度轮询";后来发现 Media3 的
 * `MediaController.getCurrentPosition()` 本身就是这个模型(每次调用现算,连续),再叠一层等于
 * 两个各自外推的估计器同时存在——倍速变化时两边对"新倍速几时生效"的认知有短暂分歧,表现为
 * 位置抖一下。
 * 结论是**外推只能有一层**,而且必须放在离真实播放状态最近的那一层——也就是 [positionMillis]
 * 的实现本身,不是 host。如果接入的播放器只能提供真正粗粒度的读数(比如确实是定时轮询、
 * 调用之间返回同一个值不变),外推要在这个属性的实现里做,不要指望 host replay 一遍。
 *
 * 倍速跟着播放时钟走(2 倍速时弹幕也 2 倍速滚),排布本身跟倍速无关 —— 暂停/seek/变速的
 * 同步因此是免费的,host 不需要为这几种状态切换单独写分支。
 *
 * 三个属性都是轮询读取的普通值,不要求是 Compose `State`。host 的帧循环在没有可见弹幕时会
 * 挂起(见 [DanmakuHostState]),这期间如果 [isPlaying] 或播放位置发生了外部变化(恢复播放、
 * seek),调用方需要显式调 [DanmakuHostState.notifyChanged] 才能让 host 立刻醒来 —— 不调用
 * 也不会永远卡住,host 有一个兜底轮询间隔,只是响应会慢那一个间隔。
 */
interface DanmakuClock {
    val positionMillis: Long
    val isPlaying: Boolean
    val playbackSpeed: Float

    /**
     * 这一帧该画的播放位置。[frameTimeNanos] 是帧时钟给的 vsync 时刻,与 `System.nanoTime()`
     * 同一基准(Android 上就是 Choreographer 的帧时间)。
     *
     * 默认退回 [positionMillis]。播放器的位置若是按固定步长更新的(ExoPlayer 播放中每 10ms
     * 写一次),以 8.3ms 的帧间隔去采样,每隔几帧就有一帧位置不动,满帧率也看得出顿挫。这种
     * 读数用 [SmoothedDanmakuClock] 包一层。外推只在时钟这一层做,host 不做。
     */
    fun positionAtFrame(frameTimeNanos: Long): Long = positionMillis

    /**
     * 这一帧该画的亚毫秒连续播放位置估计(毫秒,含小数)。
     *
     * 默认退回 [positionAtFrame] 的整数值。120/144/165Hz 高刷屏或慢倍速下,整毫秒截断会让相邻两帧
     * 的时间步长在 `6ms`/`7ms` 之间交替跳变,乘上弹幕速度后变成肉眼可见的微抖;[SmoothedDanmakuClock]
     * 通过这个方法直接交出滤波器的浮点估计,把量化误差消在源头。
     */
    fun precisePositionAtFrame(frameTimeNanos: Long): Double = positionAtFrame(frameTimeNanos).toDouble()

    /** 暂停恢复或显式时间跳变时通知时钟丢弃历史平滑状态,下一帧直接对齐新读数。默认无状态。 */
    fun onDiscontinuity() {}
}

/**
 * 帧循环的更新上限。
 *
 * [DISPLAY] 不叫 UNLIMITED:它并非真正不受限,上限就是面板这一刻的刷新率,而系统只把刷新率
 * 请求当作偏好——面板能力、省电模式、更高优先级的 Surface 都可能让实际值低于请求值。
 */
enum class DanmakuFrameRateCap(val targetFps: Int) {
    FPS_30(30),
    FPS_60(60),

    /** 跟随屏幕:每个 Compose frame 都出一帧,并向系统请求高刷新率(见 [DanmakuHost])。 */
    DISPLAY(0),
    ;

    internal val frameIntervalNanos: Long
        get() = if (targetFps <= 0) 0L else 1_000_000_000L / targetFps
}

/**
 * 绝对时间轴上的 deadline 调度器:决定这一个 vsync 该不该出帧。
 *
 * **deadline 按固定步长推进,不从实际出帧时刻重新计时。** 上一版判的是"距离上次实际出帧是否
 * 超过目标间隔的 90%",那只在面板刷新率恰好是目标帧率整数倍时成立:144Hz 选 60fps 时两个
 * vsync 是 13.89ms(小于阈值,跳过)、三个是 20.83ms(通过),于是恒定按 3 个 vsync 出帧,
 * 实际只有约 48fps;90Hz 掉到 45,165Hz 掉到 55。误差来自"以实际出帧时刻为新的起点"——每次
 * 都把上一次的取整误差累加进去,永远追不回来。
 *
 * deadline 固定按 `interval` 推进之后,每次出帧的滞后不超过一个 vsync 且不累积,`T` 时间内的
 * 出帧数恒为 `T / interval ± 1`——144Hz 上表现为 2、2、3 个 vsync 交替,长期平均就是 60fps。
 */
internal class FrameDeadlineScheduler(private val intervalNanos: Long) {

    private var initialized = false
    private var nextDeadlineNanos = 0L
    private var lastFrameNanos = 0L

    /** 帧循环挂起过(暂停、屏上没有弹幕)之后调用:时间轴上出现了长空档,不该补画。 */
    fun reset() {
        initialized = false
    }

    fun shouldDraw(frameNanos: Long): Boolean {
        if (intervalNanos <= 0L) return true
        val toleranceNanos = intervalNanos / TOLERANCE_DIVISOR
        val lateResyncNanos = intervalNanos / LATE_RESYNC_DIVISOR
        if (
            !initialized ||
            frameNanos - lastFrameNanos < 0L ||
            frameNanos - nextDeadlineNanos > lateResyncNanos
        ) {
            // 首帧、时间源倒退,或者落后超过半个周期(挂起归来、掉帧):直接从当前帧重起一个完整周期,
            // 避免迟到帧(如 30ms 处)仅隔几毫秒又撞上下一个旧刻度而连续补帧。
            initialized = true
            lastFrameNanos = frameNanos
            nextDeadlineNanos = frameNanos + intervalNanos
            return true
        }
        lastFrameNanos = frameNanos
        if (nextDeadlineNanos - frameNanos > toleranceNanos) return false
        nextDeadlineNanos += intervalNanos
        return true
    }

    private companion object {
        /**
         * 允许帧时刻比刻度早到 1/4 周期(60fps 档约 4.17ms)。桌面端(Skiko/AWT)帧回调时间采自
         * 主线程唤醒时刻而非硬件 vsync:若首帧恰落在正抖动(+1.6~2ms)而下一帧落在负抖动(-1.6~2ms),
         * 相邻峰峰值可达 ~4ms;固定步长推进下常量提前窗只平移初始相位,不改变 90/144/165Hz 的长期均值。
         */
        const val TOLERANCE_DIVISOR = 4L

        /** 迟到超过半个目标周期(60fps 档约 8.33ms,大于 60~480Hz 正常取整滞后)视为掉帧并重锚相位。 */
        const val LATE_RESYNC_DIVISOR = 2L
    }
}

/**
 * 弹幕帧循环的状态与调度。持有一个 [DanmakuClock] 和一份已编排的 [DanmakuTimeline],
 * 通过 [run] 驱动一个持续到协程被取消为止的帧循环,每次应该重绘时回调一次。
 *
 * 这一层是 emitter:只按全局播放时间查询 timeline,不判碰撞、不碰轨道状态。
 *
 * 帧循环在两种情况下整体挂起,不空转 vsync —— `while(true){ withFrameNanos }` 本身会形成
 * 自我维持的循环,而"UI surface 出帧 + 系统合成"这条链路很贵:
 * 1. 屏上没有可见弹幕(没什么可画);
 * 2. 暂停中,哪怕屏上还有弹幕 —— 暂停时位置冻结,每帧重画同一批一动不动的弹幕纯属浪费,
 *    而"暂停时屏上有弹幕"恰恰是最常见的情形(暂停本来就是为了看清楚屏上的东西)。
 * 这两条是"或"的关系,任意一条成立就挂起。挂起点等的是 [notifyChanged] 信号,或者(仅在
 * 播放中)时间轴上下一条已排定弹幕的到场时刻,谁先到算谁;详见 [run] 的实现注释。
 *
 * 时钟只轮询读值,不要求是 Compose `State`。外部导致可见性发生变化的事件 —— 恢复播放、
 * seek、在线追加了新弹幕 —— 需要调 [notifyChanged] 才能让挂起中的帧循环立刻醒来;忘记调用
 * 不会永远卡住,有一个 [IDLE_POLL_FALLBACK_MILLIS] 的兜底轮询兜底,只是响应会迟到那么久。
 */
class DanmakuHostState(
    private val clock: DanmakuClock,
    private val timeline: DanmakuTimeline,
    val frameRateCap: DanmakuFrameRateCap = DanmakuFrameRateCap.FPS_60,
) {
    private val wake = Channel<Unit>(capacity = Channel.CONFLATED)

    /**
     * 排布时用的画布尺寸与视口。`DanmakuHost` 一方面拿它画(轨道 y、裁剪矩形、滚动起点全部
     * 取自这里,不再各算一份),另一方面拿画布尺寸跟 Canvas 实际尺寸比对——两者不一致时所有
     * 位置都会算错,而画面上只表现成"位置怪怪的",指不出原因。
     */
    val layout: DanmakuLayoutConfig get() = timeline.layout

    /**
     * 遍历 `positionMillis` 时刻会在屏的弹幕。渲染层拿它做**预热**:提前一两秒把文字排版并
     * 画进图集,好让绘制帧一次测量都不做。
     *
     * 和 [run] 的帧回调分开是因为两者问的是不同的问题 —— 帧回调问"现在画什么",这里问"马上
     * 要用到什么"。把预热塞进帧回调的 `visible` 列表里做不到:那个列表按定义只含已经在屏的,
     * 而预热的全部意义是在上屏**之前**把贵的活干完。
     */
    fun forEachVisibleAt(positionMillis: Long, visitor: (DanmakuFlightPlan) -> Unit) {
        timeline.visibleAt(positionMillis, visitor)
    }

    /** 通知帧循环重新评估当前状态(如池更新、倍速切换)。见类注释:哪些事件需要调用这个。 */
    fun notifyChanged() {
        wake.trySend(Unit)
    }

    /**
     * 通知时间轴发生了不连续事件(暂停/恢复、seek、重播):在唤醒帧循环之前先重置平滑时钟状态,
     * 避免短暂停间隔被外推或小幅正向 seek 被当作连续残差滤波。
     */
    fun notifyDiscontinuity() {
        clock.onDiscontinuity()
        wake.trySend(Unit)
    }

    /**
     * 持续运行帧循环,每次应该重绘时回调 [onFrame],直到所在协程被取消(通常是宿主
     * Composable 离开组合,由 `LaunchedEffect` 负责取消)。[onFrame] 拿到的 `visible`
     * 列表每次调用前都会被清空重填,不在调用之间保留身份 —— 需要跨帧持有就自己拷贝。
     */
    suspend fun run(onFrame: (visible: List<DanmakuFlightPlan>, positionMillis: Long) -> Unit) {
        runPrecise { visible, positionMillis ->
            onFrame(visible, positionMillis.toLong())
        }
    }

    internal suspend fun runPrecise(onFrame: (visible: List<DanmakuFlightPlan>, positionMillis: Double) -> Unit) {
        val buffer = mutableListOf<DanmakuFlightPlan>()
        val frameScheduler = FrameDeadlineScheduler(frameRateCap.frameIntervalNanos)
        // 直接读 clock,不在这一层再插值。历史上这里有一个按锚点 + 经过时间 ×
        // 倍速做外推的 PositionInterpolator,理由是"positionMillis 通常是粗粒度轮询来源"——
        // 那个前提对 Media3 不成立:`clock` 通常包的是同进程 ExoPlayer 或 MediaController,
        // 两者的 `getCurrentPosition()` 本身就是每次调用现算,内部同样按"锚点位置 + 经过时间 ×
        // 倍速"做外推,也就是说这里再插值一层等于叠了第二个各推各的估计器——两层锚点在权威
        // 更新落地的时刻不同步,倍速刚变化那一小段会互相打架,表现为"抖一下"。既然下层已经
        // 连续,上层插值不会让位置更平滑,只会多引入一处分歧,删掉即可。
        var precisePosition = clock.positionMillis.toDouble()

        while (true) {
            val queryPosition = precisePosition.toLong()
            buffer.clear()
            timeline.visibleAt(queryPosition) { buffer.add(it) }

            // 两个挂起条件是"或":没什么可画,或者画了也不会变(暂停)。两者独立判断,
            // 不要合并成互斥分支 —— "暂停且有可见弹幕"这个组合本身就要求先 onFrame 一次
            // 把冻结的画面交出去,再挂起,和"没有可见弹幕"那条走的是同一段收尾代码。
            if (buffer.isEmpty() || !clock.isPlaying) {
                if (!clock.isPlaying) clock.onDiscontinuity()
                onFrame(buffer, precisePosition)
                awaitNextWakeUp(queryPosition)
                if (!clock.isPlaying) clock.onDiscontinuity()
                precisePosition = clock.positionMillis.toDouble()
                // 挂起期间时间轴上出现了一大段空档,相位要重新对齐,不能拿旧 deadline 补画。
                frameScheduler.reset()
                continue
            }

            withFrameNanos { frameNanos ->
                // 跳帧判断整个都在回调内部:不出帧就只更新 deadline,不重新采样、不回调。
                if (!frameScheduler.shouldDraw(frameNanos)) return@withFrameNanos

                precisePosition = clock.precisePositionAtFrame(frameNanos)
                buffer.clear()
                timeline.visibleAt(precisePosition.toLong()) { buffer.add(it) }
                onFrame(buffer, precisePosition)
            }
        }
    }

    /**
     * 挂起到"下一条已排定弹幕的到场时刻"或者 [notifyChanged] 信号,谁先到算谁。
     * 暂停中,或者时间轴上已经没有更多弹幕时,已排定到场时刻不存在,退化成
     * [IDLE_POLL_FALLBACK_MILLIS] 的兜底轮询 —— 见类注释,这是"忘调 notifyChanged"的代价
     * 上限,不是主要的唤醒路径。
     */
    private suspend fun awaitNextWakeUp(position: Long) {
        val next = timeline.nextEntryAfter(position)
        val scheduledDelay = if (next != null && clock.isPlaying) {
            ceil((next.emitTimeMillis - position) / clock.playbackSpeed.coerceAtLeast(MIN_PLAYBACK_SPEED)).toLong()
        } else {
            null
        }
        val timeoutMillis = (scheduledDelay ?: IDLE_POLL_FALLBACK_MILLIS)
            // 向上取整并以 1ms 兜底:向零截断会在倍速 > 1 时提前 1ms 醒来,再把不足 1ms 的余量截成 0;
            // 而 withTimeoutOrNull(0) 直接同步返回 null,既不挂起让出主线程,也不消费 wake。
            .coerceIn(1L, IDLE_POLL_FALLBACK_MILLIS)
        withTimeoutOrNull(timeoutMillis) { wake.receive() }
    }

    private companion object {
        const val IDLE_POLL_FALLBACK_MILLIS = 500L
        const val MIN_PLAYBACK_SPEED = 0.01f
    }
}

/**
 * 弹幕渲染 host。持有一个 [DanmakuHostState],在 `LaunchedEffect` 里驱动它的帧循环,画布上
 * 把每次回调拿到的当帧计划逐条画出来。这一层只做投影:坐标、裁剪、绘制,不读也不改任何轨道
 * 状态。不引 material3——没有主题、没有默认配色,外观全部来自 [style]。
 *
 * **契约**:排布用的 [DanmakuLayoutConfig.canvasWidthPx] / [DanmakuLayoutConfig.canvasHeightPx]
 * 必须等于这个 Canvas 的像素尺寸。这里故意不重复接收一份尺寸参数去覆盖排布期用的那份配置——
 * `size` 是画布在绘制那一刻的真实尺寸,是唯一权威来源,重复传参只会引入"两处配置各传一份、
 * 可能对不齐"的新故障模式。不一致不会被默默吞掉:尺寸变化时(转屏、分屏、窗口尺寸调整)会
 * 跟 [DanmakuHostState.layout] 比对,超出容差就回调 [onCanvasSizeMismatch] ——库自己没法重编
 * 时间轴(它不持有弹幕池),只负责把"对不齐"这件事暴露出去,重新编排是调用方的事。
 *
 * 位置:
 * - 滚动:`x = viewport.right - (t - emitTime) * speed`,轨道自 viewport 顶边往下铺。
 * - 顶部:锚 viewport 顶边往下堆。
 * - 底部:锚 **画布**底边往上堆,不受 viewport 约束,也不进 viewport 的裁剪区(理由见
 *   [DanmakuViewport]:收进视口它就不是底部弹幕了)。它的纵向上限是
 *   [DanmakuLayoutConfig.bottomTrackFraction]。
 *
 * **绘制帧里没有文字排版,也没有文字绘制命令。** 排版和"描边 + 填充"两遍 `drawText` 都发生在
 * 弹幕进入预热窗口的那一次,结果画进 [DanmakuRenderCache] 持有的位图图集;每帧对每条可见弹幕
 * 只贴一次图。上一版是每帧每条查一次 `TextMeasurer` 缓存再提交两遍 `drawText`,320 条同屏就是
 * 320 次缓存查询 + 640 次绘制命令,实测主线程每帧约 10ms(120Hz 的预算是 8.3ms)。
 *
 * @param renderStats 传进来就能观测缓存行为(排版命中/未命中、位图的栅格化/复用/淘汰);不传就
 *   内部自己建一份,统计照常发生,只是没人读。
 * @param imageSource [Danmaku.images] 的图从这里取。
 */
@Composable
fun DanmakuHost(
    state: DanmakuHostState,
    style: DanmakuRenderStyle = DanmakuRenderStyle(),
    modifier: Modifier = Modifier,
    renderStats: DanmakuRenderStats? = null,
    imageSource: DanmakuImageSource = DanmakuImageSource.None,
    onCanvasSizeMismatch: (actualWidthPx: Float, actualHeightPx: Float) -> Unit = { _, _ -> },
) {
    // 这个 measurer 只在准备阶段用,每条文本最多进来一次,自带的 LRU 已经不是热路径 ——
    // 真正的排版缓存是 DanmakuRenderCache 里那张按 (文本, 解析后 TextStyle) 建的表。
    val measurer = rememberTextMeasurer()
    val graphicsContext = LocalGraphicsContext.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // remember 无条件调用再取,不写成 `renderStats ?: remember { ... }` —— 那样 remember 会被
    // elvis 短路成"有时调、有时不调",槽位表跟着 renderStats 的有无错位。
    val fallbackStats = remember { DanmakuRenderStats() }
    val stats = renderStats ?: fallbackStats

    // 缓存挂在样式、图形上下文和 density 上:任一变化,已画的位图与页图层全部作废并释放。
    val cache = remember(measurer, graphicsContext, density, layoutDirection, style, stats, imageSource) {
        DanmakuRenderCache(measurer, graphicsContext, density, layoutDirection, style, stats, imageSource)
    }
    // 图集的页要及时放掉。remember 换实例和离开组合两条路都要走到 release,
    // DisposableEffect(cache) 两者都覆盖:key 变化时先 onDispose 旧的。
    DisposableEffect(cache) {
        onDispose { cache.release() }
    }

    val prewarmer = remember(cache) { DanmakuPrewarmer(cache) }

    // frame 是复用的普通 list,不是 SnapshotStateList——内容变化本身不会触发重组,
    // frameVersion 才是 Canvas 订阅的信号源,列表只是它背后的数据。
    val frame = remember { mutableListOf<DanmakuFlightPlan>() }
    var framePositionMillis by remember { mutableDoubleStateOf(0.0) }
    var frameVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(state, cache, prewarmer) {
        var initialized = false
        state.runPrecise { visible, positionMillis ->
            val changed = !initialized ||
                (visible.isEmpty() && frame.isNotEmpty()) ||
                (visible.isNotEmpty() && (
                    positionMillis != framePositionMillis ||
                        visible != frame ||
                        cache.hasReadyPendingImages(visible)
                    ))
            if (changed) {
                initialized = true
                frame.clear()
                frame.addAll(visible)
                framePositionMillis = positionMillis
                frameVersion++
            }
            // 预热放在帧回调而不是绘制块里:绘制块要保持"零测量",而这里已经在主线程的帧
            // 边界上,花掉的是本帧剩余预算,不是下一帧的。
            prewarmer.onFrame(state, visible, positionMillis.toLong())
        }
    }

    val layout = state.layout

    Canvas(
        modifier = modifier
            // preferredFrameRate 是 DrawModifierNode,帧率偏好在每次 draw 时自动下发,不需要
            // 每帧重设;但它必须挂在**真正产生绘制**的节点上才生效,所以只能加在 Canvas 自己
            // 的 modifier 链里,不能由调用方在外层容器上挂。
            // 30/60 档不请求高刷:那两档的绘制频率由 FrameDeadlineScheduler 限制,再向系统要
            // 高刷新率只会让面板空转。
            .then(
                if (state.frameRateCap == DanmakuFrameRateCap.DISPLAY) {
                    Modifier.preferredFrameRate(FrameRateCategory.High)
                } else {
                    Modifier
                },
            )
            .onSizeChanged { size ->
                val actualWidthPx = size.width.toFloat()
                val actualHeightPx = size.height.toFloat()
                val mismatched = abs(actualWidthPx - layout.canvasWidthPx) > CANVAS_SIZE_TOLERANCE_PX ||
                    abs(actualHeightPx - layout.canvasHeightPx) > CANVAS_SIZE_TOLERANCE_PX
                if (actualWidthPx > 0f && actualHeightPx > 0f && mismatched) {
                    onCanvasSizeMismatch(actualWidthPx, actualHeightPx)
                }
            },
    ) {
        // 读一次 frameVersion,让这个绘制块订阅上面的 State 写入。
        @Suppress("UNUSED_EXPRESSION")
        frameVersion

        val viewport = layout.viewportPx
        cache.beginFrame(frame)

        // 分两趟画,因为两趟的裁剪区不同。列表通常只有几十条,多扫一遍比先分组便宜,也不用
        // 为分组分配两个列表。
        //
        // 第一趟:滚动 + 顶部,裁到视口。没有这次裁剪,显示区域就退化成"轨道数少了几条",
        // 弹幕照样画到区域外面去。
        clipRect(left = viewport.left, top = viewport.top, right = viewport.right, bottom = viewport.bottom) {
            for (i in 0 until frame.size) {
                val plan = frame[i]
                if (plan.mode == DanmakuMode.BOTTOM) continue
                val y = viewport.top + plan.track * layout.trackHeightPx
                val x = if (plan.mode == DanmakuMode.SCROLL) {
                    (viewport.right - (framePositionMillis - plan.emitTimeMillis) * plan.speedPxPerMillis).toFloat()
                } else {
                    centeredX(viewport.left, viewport.width, plan.widthPx)
                }
                cache.drawPrepared(this, i, x, y)
            }
        }

        // 第二趟:底部弹幕锚**画布**底边往上堆,不进视口的裁剪区——收进视口它就成了"画面
        // 四分之三处的弹幕",不再是底部弹幕(理由见 DanmakuViewport)。它因此会和字幕、
        // 播放控件抢画面底部那条带,这是底部弹幕固有的,将来靠避让区解决。
        for (i in 0 until frame.size) {
            val plan = frame[i]
            if (plan.mode != DanmakuMode.BOTTOM) continue
            val y = size.height - (plan.track + 1) * layout.trackHeightPx
            cache.drawPrepared(this, i, centeredX(0f, size.width, plan.widthPx), y)
        }

        cache.endFrame()
    }
}

/**
 * 预热驱动。每隔 [PREWARM_INTERVAL_MILLIS] 播放时间往前看 [PREWARM_LOOKAHEAD_MILLIS],把那时会
 * 在屏的弹幕提前准备好。
 *
 * 同时受条数与墙钟耗时双重预算约束:桌面端 CJK 描边 + 填充栅格化单条耗时可能比移动端略波动,
 * 单看条数仍可能在密集进场时吃穿单帧主线程预算。若本轮因任一预算截断而没把窗口内的条目备完,
 * 不更新节流锚点,下一帧接着把剩余条目分摊做完;一旦整窗备齐,则恢复按 [PREWARM_INTERVAL_MILLIS]
 * 成批攒写,减少图集脏页重录次数。
 *
 * 用播放时间而不是帧数做节流,是因为倍速播放时"多久之后上屏"跟着倍速走,而帧数不跟。
 */
internal class DanmakuPrewarmer(private val cache: DanmakuRenderCache) {

    private var hasCompletedPass = false
    private var lastCompletedPositionMillis = 0L

    fun onFrame(state: DanmakuHostState, visible: List<DanmakuFlightPlan>, positionMillis: Long) {
        if (hasCompletedPass) {
            val delta = positionMillis - lastCompletedPositionMillis
            if (delta > -PREWARM_INTERVAL_MILLIS && delta < PREWARM_INTERVAL_MILLIS) return
        }
        cache.protectVisible(visible)
        val start = TimeSource.Monotonic.markNow()
        var budget = PREWARM_BUDGET_PER_PASS
        var scanned = 0
        var cutShort = false
        state.forEachVisibleAt(positionMillis + PREWARM_LOOKAHEAD_MILLIS) { plan ->
            if (cutShort) return@forEachVisibleAt
            if ((++scanned and SCAN_TIME_CHECK_MASK) == 0 && start.elapsedNow() >= PREWARM_TIME_BUDGET) {
                cutShort = true
                return@forEachVisibleAt
            }
            if (cache.prepare(plan.danmaku)) {
                budget--
                if (budget <= 0 || start.elapsedNow() >= PREWARM_TIME_BUDGET) {
                    cutShort = true
                }
            }
        }
        if (!cutShort) {
            hasCompletedPass = true
            lastCompletedPositionMillis = positionMillis
        }
    }

    private companion object {
        const val PREWARM_LOOKAHEAD_MILLIS = 1_500L
        const val PREWARM_INTERVAL_MILLIS = 100L
        const val PREWARM_BUDGET_PER_PASS = 24
        const val SCAN_TIME_CHECK_MASK = 31
        val PREWARM_TIME_BUDGET = 2_500.microseconds
    }
}

/**
 * 固定弹幕的居中 x。宽度用的是**排布期**测出的 [textWidthPx],不是绘制期重新量的宽度:
 * 两者本该相等,不等就说明两条测量路径的字体/字号已经不同源,那时该暴露成位置错位,而不是
 * 让画面自己对齐、把问题藏回排布里。
 */
private fun centeredX(leftPx: Float, availableWidthPx: Float, textWidthPx: Float): Float =
    leftPx + (availableWidthPx - textWidthPx) / 2f

/** 容差:亚像素级的取整误差不算"对不齐",超过一个像素才值得打扰调用方。 */
private const val CANVAS_SIZE_TOLERANCE_PX = 1f
