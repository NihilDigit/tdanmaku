package dev.nihildigit.danmaku

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.abs
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

    private var nextDeadlineNanos = Long.MIN_VALUE

    /** 帧循环挂起过(暂停、屏上没有弹幕)之后调用:时间轴上出现了长空档,不该补画。 */
    fun reset() {
        nextDeadlineNanos = Long.MIN_VALUE
    }

    fun shouldDraw(frameNanos: Long): Boolean {
        if (intervalNanos <= 0L) return true
        if (nextDeadlineNanos == Long.MIN_VALUE || frameNanos - nextDeadlineNanos > intervalNanos) {
            // 首帧,或者落后了不止一个周期(挂起归来、掉帧)。补画追不回已经过去的时间,
            // 只会一连出好几帧,所以直接把相位对齐到当前帧重新起算。
            nextDeadlineNanos = frameNanos + intervalNanos
            return true
        }
        if (frameNanos < nextDeadlineNanos) return false
        nextDeadlineNanos += intervalNanos
        return true
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
     * 遍历 `positionMillis` 时刻会在屏的弹幕。渲染层拿它做**预热**:提前一两秒把文字排版和
     * display list 准备好,好让绘制帧一次测量都不做。
     *
     * 和 [run] 的帧回调分开是因为两者问的是不同的问题 —— 帧回调问"现在画什么",这里问"马上
     * 要用到什么"。把预热塞进帧回调的 `visible` 列表里做不到:那个列表按定义只含已经在屏的,
     * 而预热的全部意义是在上屏**之前**把贵的活干完。
     */
    fun forEachVisibleAt(positionMillis: Long, visitor: (DanmakuFlightPlan) -> Unit) {
        timeline.visibleAt(positionMillis, visitor)
    }

    /** 通知帧循环重新评估当前状态。见类注释:哪些事件需要调用这个。 */
    fun notifyChanged() {
        wake.trySend(Unit)
    }

    /**
     * 持续运行帧循环,每次应该重绘时回调 [onFrame],直到所在协程被取消(通常是宿主
     * Composable 离开组合,由 `LaunchedEffect` 负责取消)。[onFrame] 拿到的 `visible`
     * 列表每次调用前都会被清空重填,不在调用之间保留身份 —— 需要跨帧持有就自己拷贝。
     */
    suspend fun run(onFrame: (visible: List<DanmakuFlightPlan>, positionMillis: Long) -> Unit) {
        val buffer = mutableListOf<DanmakuFlightPlan>()
        val frameScheduler = FrameDeadlineScheduler(frameRateCap.frameIntervalNanos)
        // 直接读 clock.positionMillis,不在这一层再插值。历史上这里有一个按锚点 + 经过时间 ×
        // 倍速做外推的 PositionInterpolator,理由是"positionMillis 通常是粗粒度轮询来源"——
        // 那个前提对 Media3 不成立:`clock` 通常包的是同进程 ExoPlayer 或 MediaController,
        // 两者的 `getCurrentPosition()` 本身就是每次调用现算,内部同样按"锚点位置 + 经过时间 ×
        // 倍速"做外推,也就是说这里再插值一层等于叠了第二个各推各的估计器——两层锚点在权威
        // 更新落地的时刻不同步,倍速刚变化那一小段会互相打架,表现为"抖一下"。既然下层已经
        // 连续,上层插值不会让位置更平滑,只会多引入一处分歧,删掉即可。
        var coarsePosition = clock.positionMillis

        while (true) {
            buffer.clear()
            timeline.visibleAt(coarsePosition) { buffer.add(it) }

            // 两个挂起条件是"或":没什么可画,或者画了也不会变(暂停)。两者独立判断,
            // 不要合并成互斥分支 —— "暂停且有可见弹幕"这个组合本身就要求先 onFrame 一次
            // 把冻结的画面交出去,再挂起,和"没有可见弹幕"那条走的是同一段收尾代码。
            if (buffer.isEmpty() || !clock.isPlaying) {
                onFrame(buffer, coarsePosition)
                awaitNextWakeUp(coarsePosition)
                coarsePosition = clock.positionMillis
                // 挂起期间时间轴上出现了一大段空档,相位要重新对齐,不能拿旧 deadline 补画。
                frameScheduler.reset()
                continue
            }

            withFrameNanos { frameNanos ->
                // 跳帧判断整个都在回调内部:不出帧就只更新 deadline,不重新采样、不回调。
                if (!frameScheduler.shouldDraw(frameNanos)) return@withFrameNanos

                coarsePosition = clock.positionAtFrame(frameNanos)
                buffer.clear()
                timeline.visibleAt(coarsePosition) { buffer.add(it) }
                onFrame(buffer, coarsePosition)
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
            ((next.emitTimeMillis - position) / clock.playbackSpeed.coerceAtLeast(MIN_PLAYBACK_SPEED)).toLong()
        } else {
            null
        }
        val timeoutMillis = (scheduledDelay ?: IDLE_POLL_FALLBACK_MILLIS)
            .coerceIn(0L, IDLE_POLL_FALLBACK_MILLIS)
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
 * 弹幕进入预热窗口的那一次,结果录进 [DanmakuRenderCache] 持有的 display list;每帧对每条可见
 * 弹幕只做一次平移 + `drawLayer`。上一版是每帧每条查一次 `TextMeasurer` 缓存再提交两遍
 * `drawText`,320 条同屏就是 320 次缓存查询 + 640 次绘制命令,实测主线程每帧约 10ms
 * (120Hz 的预算是 8.3ms),而同期 GPU 只用了 3~5ms —— 瓶颈在提交侧,不在填充率侧。
 *
 * @param renderStats 传进来就能观测缓存行为(命中/未命中、layer 创建/复用/回收);不传就内部
 *   自己建一份,统计照常发生,只是没人读。
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

    // 缓存挂在样式和 density 上:字号、字体、颜色兜底、描边、不透明度任一变化,已录的
    // display list 全部作废(alpha 是烤进去的,见 DanmakuRenderCache 的类注释)。
    val cache = remember(measurer, graphicsContext, density, layoutDirection, style, stats, imageSource) {
        DanmakuRenderCache(measurer, graphicsContext, density, layoutDirection, style, stats, imageSource)
    }
    // GraphicsLayer 不还回去就是显存泄漏。remember 换实例和离开组合两条路都要走到 release,
    // DisposableEffect(cache) 两者都覆盖:key 变化时先 onDispose 旧的。
    DisposableEffect(cache) {
        onDispose { cache.release() }
    }

    val prewarmer = remember(cache) { DanmakuPrewarmer(cache) }

    // frame 是复用的普通 list,不是 SnapshotStateList——内容变化本身不会触发重组,
    // frameVersion 才是 Canvas 订阅的信号源,列表只是它背后的数据。
    val frame = remember { mutableListOf<DanmakuFlightPlan>() }
    var framePositionMillis by remember { mutableLongStateOf(0L) }
    var frameVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(state, prewarmer) {
        state.run { visible, positionMillis ->
            frame.clear()
            frame.addAll(visible)
            framePositionMillis = positionMillis
            frameVersion++
            // 预热放在帧回调而不是绘制块里:绘制块要保持"零测量",而这里已经在主线程的帧
            // 边界上,花掉的是本帧剩余预算,不是下一帧的。
            prewarmer.onFrame(state, positionMillis)
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
        cache.beginFrame()

        // 分两趟画,因为两趟的裁剪区不同。列表通常只有几十条,多扫一遍比先分组便宜,也不用
        // 为分组分配两个列表。
        //
        // 第一趟:滚动 + 顶部,裁到视口。没有这次裁剪,显示区域就退化成"轨道数少了几条",
        // 弹幕照样画到区域外面去。
        clipRect(left = viewport.left, top = viewport.top, right = viewport.right, bottom = viewport.bottom) {
            for (plan in frame) {
                if (plan.mode == DanmakuMode.BOTTOM) continue
                val y = viewport.top + plan.track * layout.trackHeightPx
                val x = if (plan.mode == DanmakuMode.SCROLL) {
                    viewport.right - (framePositionMillis - plan.emitTimeMillis) * plan.speedPxPerMillis
                } else {
                    centeredX(viewport.left, viewport.width, plan.widthPx)
                }
                cache.draw(this, plan.danmaku, x, y)
            }
        }

        // 第二趟:底部弹幕锚**画布**底边往上堆,不进视口的裁剪区——收进视口它就成了"画面
        // 四分之三处的弹幕",不再是底部弹幕(理由见 DanmakuViewport)。它因此会和字幕、
        // 播放控件抢画面底部那条带,这是底部弹幕固有的,将来靠避让区解决。
        for (plan in frame) {
            if (plan.mode != DanmakuMode.BOTTOM) continue
            val y = size.height - (plan.track + 1) * layout.trackHeightPx
            cache.draw(this, plan.danmaku, centeredX(0f, size.width, plan.widthPx), y)
        }

        cache.endFrame()
    }
}

/**
 * 预热驱动。每隔 [PREWARM_INTERVAL_MILLIS] 播放时间往前看 [PREWARM_LOOKAHEAD_MILLIS],把那时会
 * 在屏的弹幕提前准备好。
 *
 * 有预算上限是因为准备工作(排版 + 录制 display list)是主线程活儿:一次把上百条全准备了,
 * 省下的每帧成本会以一个几十毫秒的尖峰还回去,直方图上就是一个新的丢帧。分摊到多帧做,窗口
 * 有一两秒的余量,来得及。
 *
 * 用播放时间而不是帧数做节流,是因为倍速播放时"多久之后上屏"跟着倍速走,而帧数不跟。
 */
private class DanmakuPrewarmer(private val cache: DanmakuRenderCache) {

    private var lastPositionMillis = Long.MIN_VALUE

    fun onFrame(state: DanmakuHostState, positionMillis: Long) {
        // abs 而不是差值:seek 往回跳同样要立刻重新预热。
        if (abs(positionMillis - lastPositionMillis) < PREWARM_INTERVAL_MILLIS) return
        lastPositionMillis = positionMillis
        var budget = PREWARM_BUDGET_PER_PASS
        state.forEachVisibleAt(positionMillis + PREWARM_LOOKAHEAD_MILLIS) { plan ->
            // 预算用完不提前退出:visitor 没有中断口子,而扫剩下的部分只是每条一次哈希查询。
            if (budget > 0 && cache.prepare(plan.danmaku)) budget--
        }
    }

    private companion object {
        const val PREWARM_LOOKAHEAD_MILLIS = 1_500L
        const val PREWARM_INTERVAL_MILLIS = 100L
        const val PREWARM_BUDGET_PER_PASS = 24
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
