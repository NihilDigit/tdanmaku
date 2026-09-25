package dev.nihildigit.danmaku

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * 位移插值方式。只有这两种是因为数据源(B 站 mode 7)只表达得出这两种,不是这一层的能力上限。
 */
enum class SpecialDanmakuEasing {
    LINEAR,
    EASE_IN_CUBIC,
}

/**
 * 定位/运动弹幕:位置由**作者**写死,不由引擎排布。
 *
 * 它跟 [Danmaku] 是两种东西,不是 [Danmaku] 加几个字段。滚动/顶部/底部弹幕的位置是引擎排出来的
 * 结果(选轨、算速度、判碰撞);这一类的位置是内容的一部分,作者按某个参考画幅摆好了,引擎唯一
 * 该做的是把参考画幅映射到实际画布。两者放进同一个模型,滚动弹幕就要背上关键帧、旋转、alpha
 * 曲线这一堆永远为空的字段,调度器和渲染器还得为它开分支——所以这里独立成层,也独立成模型。
 *
 * **坐标已经归一化到 `[0, 1]`,参考画幅在这一层不存在。** 数据源那边是绝对像素(B 站按
 * 1920×1080 写),换算是 adapter 的事:库不该知道 1920 这个数字,正如它不该知道模式号 7。
 * 归一化值允许超出 `[0, 1]`——作者常让弹幕从画面外飞进来,把它夹回来就是改内容。
 *
 * **不受 [DanmakuViewport] 约束。** 判据跟底部弹幕那条完全一样:位置是引擎排的就该收进显示
 * 区域,是作者指定的就不能收。用户把显示区域调到 50% 的意图是"别让滚动弹幕糊住整个画面",
 * 不是"把作者摆在画面下半部的字挪上去"——收进 viewport 等于按比例揉烂整幅编排,原地毁掉
 * 作者的意图。
 *
 * @param id 列表 key 与去重用。这一层不做任何确定性随机(位置全部由作者给定),所以 id 的
 *   职责比 [Danmaku.id] 轻。
 * @param startTimeMillis 出现时刻的播放进度。
 * @param durationMillis 总寿命。位移可以早于寿命结束(见 [translationDurationMillis]),
 *   alpha 曲线则铺满整个寿命。
 * @param fromX 起点 x,画布宽度的比例。
 * @param fromY 起点 y,画布高度的比例。y 轴向下。
 * @param toX 终点 x。
 * @param toY 终点 y。
 * @param translationDelayMillis 出现后静止多久才开始位移。
 * @param translationDurationMillis 位移本身占用的时长。为 0 表示延迟结束的那一刻直接跳到终点。
 * @param fromAlpha 寿命起点的不透明度。
 * @param toAlpha 寿命终点的不透明度。
 * @param rotateZDegrees 绕 z 轴旋转,平面旋转。
 * @param rotateYDegrees 绕 y 轴旋转。**这是带透视的 3D 旋转**,不是平面斜切:mode 7 用它做
 *   "字幕向画面深处倒下去"这类效果,当成平面变换画出来是另一个图形。渲染层必须走
 *   `graphicsLayer` 并给出 cameraDistance。
 * @param fontSizeFraction 字号占画布**高度**的比例。不跟随用户的全局弹幕字号设置:作者是按
 *   一个确定的字号摆的版,字号一变,行宽跟着变,他排好的对齐关系就散了。按高度而不是宽度归一,
 *   是为了让字在不同宽高比下不被拉扁——位置按各自的轴归一,字号只能挑一个轴。
 * @param hasStroke 是否描边。作者可以关掉:mode 7 常见的用法是把弹幕当画面元素(色块上的标题、
 *   贴边的注释),描边在那些地方是脏东西。
 * @param easing 位移的插值方式,只作用于位移,不作用于 alpha。
 */
data class SpecialDanmaku(
    val id: String,
    val text: String,
    val color: Int,
    val startTimeMillis: Long,
    val durationMillis: Long,
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val translationDelayMillis: Long = 0L,
    val translationDurationMillis: Long = 0L,
    val fromAlpha: Float = 1f,
    val toAlpha: Float = 1f,
    val rotateZDegrees: Float = 0f,
    val rotateYDegrees: Float = 0f,
    val fontSizeFraction: Float = DEFAULT_FONT_SIZE_FRACTION,
    val hasStroke: Boolean = true,
    val easing: SpecialDanmakuEasing = SpecialDanmakuEasing.LINEAR,
) {
    /** 寿命终点(不含)。 */
    val endTimeMillis: Long get() = startTimeMillis + durationMillis

    companion object {
        /** 25/1080:B 站标准字号在 1080 高参考画幅里的占比,adapter 拿不到字号时的兜底。 */
        const val DEFAULT_FONT_SIZE_FRACTION: Float = 25f / 1080f
    }
}

/** 某个播放时刻求值出来的即时状态。位置归一化,含义同 [SpecialDanmaku.fromX] / [SpecialDanmaku.fromY]。 */
data class SpecialDanmakuMotion(
    val x: Float,
    val y: Float,
    val alpha: Float,
)

/**
 * 给定播放进度求这条弹幕的即时状态;不在寿命区间内返回 null。
 *
 * **没有逐条动画状态。** 整条弹幕是时间的纯函数,求一次就出结果,seek、变速、暂停因此不需要
 * 任何同步逻辑。
 */
fun SpecialDanmaku.motionAt(playTimeMillis: Long): SpecialDanmakuMotion? {
    if (durationMillis <= 0L) return null
    val elapsed = playTimeMillis - startTimeMillis
    if (elapsed < 0L || elapsed >= durationMillis) return null

    val moved = elapsed - translationDelayMillis
    val rawProgress = when {
        moved < 0L -> 0f
        // 顺序不能跟下一条对调:位移时长为 0 时,延迟结束那一刻(moved == 0)就该在终点,
        // 按 `moved >= duration` 判也得到 1,但除法会先炸。
        translationDurationMillis <= 0L -> 1f
        moved >= translationDurationMillis -> 1f
        else -> moved.toFloat() / translationDurationMillis
    }
    val progress = when (easing) {
        SpecialDanmakuEasing.LINEAR -> rawProgress
        SpecialDanmakuEasing.EASE_IN_CUBIC -> rawProgress * rawProgress * rawProgress
    }

    val alphaProgress = elapsed.toFloat() / durationMillis
    return SpecialDanmakuMotion(
        x = lerp(fromX, toX, progress),
        y = lerp(fromY, toY, progress),
        alpha = lerp(fromAlpha, toAlpha, alphaProgress).coerceIn(0f, 1f),
    )
}

private fun lerp(from: Float, to: Float, progress: Float): Float = from + (to - from) * progress

/**
 * [SpecialDanmakuHost] 的状态:一份弹幕列表加一个时钟。
 *
 * 它跟 [DanmakuHostState] 是两个独立的东西,不共享调度器也不共享帧循环。这一层没有轨道、没有
 * 碰撞、没有溢出策略——位置全由内容给定,能被调度的东西一个都没有,所以没有"状态机"可言,
 * [danmaku] 换一份就是全部。
 *
 * [danmaku] 是普通的 Compose state:调用方直接赋值(比如新分段拉回来时追加),不需要通知函数。
 */
@Stable
class SpecialDanmakuHostState(internal val clock: DanmakuClock) {

    // 委托属性不能带自定义 setter(Kotlin 的限制),所以拆成后备属性 + 手写访问器。
    private var poolState by mutableStateOf<List<SpecialDanmaku>>(emptyList())

    /** 整池。赋值时按 [SpecialDanmaku.startTimeMillis] 排好序,[activeDanmaku] 靠这个顺序二分。 */
    var danmaku: List<SpecialDanmaku>
        get() = poolState
        set(value) {
            // **同一个实例反复赋值必须是空操作。** 调用方通常写成
            // `state.danmaku = pool`,那一行每次重组都会跑,而重组是很频繁的。
            if (value === poolState) return
            poolState = if (value.isSortedByStart()) value else value.sortedBy { it.startTimeMillis }
            // **不重置 activeBucket。** 这里曾经把它设成哨兵值想"强制重算",但哨兵会让
            // refreshActive() 把区间中心当成 0,活跃集合瞬间坍缩成视频最开头那几秒 —— 当前该
            // 显示的全部消失,下一帧帧循环才恢复。表现就是高级弹幕每次重组闪一下。
            // refreshActive() 本来就按当前桶重算,不需要任何强制。
            refreshActive()
        }

    /**
     * 当前时间附近可能在屏的那些,**不是整池**。绘制和预热都只扫这一段。
     *
     * 按 [ACTIVE_BUCKET_MILLIS] 分桶、跨桶才重算,是为了不在每帧上做一次过滤和分配。它不是
     * Compose state:绘制块每帧都因为帧版本号重画,读到的总是最新值,不需要再订阅一次。
     */
    internal var activeDanmaku: List<SpecialDanmaku> = emptyList()
        private set

    private var activeBucket = Long.MIN_VALUE

    /** 进度推进时调用。返回是否真的换了区间(没换就不必让调用方做别的事)。 */
    internal fun onPositionChanged(positionMillis: Long): Boolean {
        val bucket = positionMillis.floorDiv(ACTIVE_BUCKET_MILLIS)
        if (bucket == activeBucket) return false
        activeBucket = bucket
        refreshActive()
        return true
    }

    private fun refreshActive() {
        val pool = poolState
        if (pool.isEmpty()) {
            activeDanmaku = emptyList()
            return
        }
        val center = if (activeBucket == Long.MIN_VALUE) 0L else activeBucket * ACTIVE_BUCKET_MILLIS
        // 区间两端各放宽一个桶:桶内进度会一直走到下一个桶边界,那期间新进场的必须已经在组合里。
        val from = center - ACTIVE_BUCKET_MILLIS
        val until = center + 2 * ACTIVE_BUCKET_MILLIS
        // 起点在 until 之后的一定没进场;起点在 from 之前但还没结束的仍然在屏上,所以从
        // 第一条"结束时间 >= from"的开始扫,不能直接用起点二分的下界。
        val end = pool.lowerBoundByStart(until)
        activeDanmaku = pool.subList(0, end).filter { it.endTimeMillis >= from }
    }
}

/** 第一个 `startTimeMillis >= target` 的下标。 */
private fun List<SpecialDanmaku>.lowerBoundByStart(target: Long): Int {
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].startTimeMillis < target) lo = mid + 1 else hi = mid
    }
    return lo
}

private fun List<SpecialDanmaku>.isSortedByStart(): Boolean {
    for (i in 1 until size) if (this[i - 1].startTimeMillis > this[i].startTimeMillis) return false
    return true
}

/**
 * 活跃区间的分桶粒度。取 2 秒:mode 7 的 `duration` 常见几秒量级,桶太小会频繁重新过滤、
 * 释放图层,太大又会让每帧扫一批还没进场的。
 */
private const val ACTIVE_BUCKET_MILLIS = 2_000L

/**
 * 定位/运动弹幕(mode 7 一类)的渲染层,独立于滚动/顶部/底部那一层。
 *
 * **它不受 [DanmakuViewport] 约束,也不参与 [DanmakuHost] 的裁剪。** 理由跟底部弹幕那条一样,
 * 见 [SpecialDanmaku] 的文档:位置是作者指定的,不是引擎排的,收进显示区域等于揉烂作者的编排。
 * 调用方应当把它叠在 [DanmakuHost] 之上、铺满整个画面。
 *
 * **整层只有一张 Canvas,每条弹幕是一份录好的 [GraphicsLayer],不是一个组合节点。** 上一版
 * 一条弹幕一个带 `graphicsLayer` 的节点,字符画类视频(同屏上千条 mode 7)在真机上主线程
 * 35% 耗在每帧逐个重算图层参数,RenderThread 43% 耗在逐条重画文字。后者是因为透明度设在了
 * 节点图层的 alpha 上:用户的弹幕不透明度默认小于 1,每条都因此走离屏合成。
 *
 * 现在的分工:
 * - 旋转与透视是弹幕的静态属性,录制时写进它自己那份图层的 `rotationY`/`rotationZ`/
 *   `cameraDistance`,之后不再改。`rotationY` 带透视,`DrawScope` 的变换表达不出来,这是
 *   每条仍要一份图层的原因。
 * - 位置每帧变,用画布平移表达,不碰图层属性。
 * - 透明度用 [CompositingStrategy.ModulateAlpha] 逐个绘制命令地乘,不开离屏,值没变就不写。
 *   它跟 [DanmakuRenderCache] 把 alpha 烤进绘制命令的结果逐像素相同,描边会从半透明的填充
 *   下透出来,两层弹幕的观感由此一致。
 *
 * @param style 复用普通弹幕的外观参数,但**只取 `baseTextStyle` 的字体族/字重、描边和
 *   `opacity`**:字号由 [SpecialDanmaku.fontSizeFraction] 决定,不跟随用户的全局字号设置;
 *   是否描边由 [SpecialDanmaku.hasStroke] 决定,`strokeWidthPx <= 0` 时仍然整体关闭。
 */
@Composable
fun SpecialDanmakuHost(
    state: SpecialDanmakuHostState,
    style: DanmakuRenderStyle = DanmakuRenderStyle(),
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val graphicsContext = LocalGraphicsContext.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val hasContent = state.danmaku.isNotEmpty()

    // 字号按画布高度换算,画布一变全部重录。
    val cache = remember(measurer, graphicsContext, density, layoutDirection, style, canvasSize) {
        SpecialDanmakuRenderCache(measurer, graphicsContext, density, layoutDirection, style, canvasSize)
    }
    DisposableEffect(cache) {
        onDispose { cache.release() }
    }

    var framePositionMillis by remember { mutableLongStateOf(0L) }
    var frameVersion by remember { mutableIntStateOf(0) }

    // 列表为空时不跑帧循环。绝大多数视频一条 mode 7 都没有,那种情况下这一层应该完全不产生
    // 每帧工作量。
    LaunchedEffect(state, hasContent, cache) {
        if (!hasContent) return@LaunchedEffect
        var lastPosition = Long.MIN_VALUE
        while (true) {
            val now = withFrameNanos { state.clock.positionAtFrame(it) }
            if (now == lastPosition && !state.clock.isPlaying) {
                // 暂停时画面不变,不必每个 vsync 都要一帧;恢复播放最多迟到这一个间隔。
                delay(PAUSED_POLL_MILLIS)
                continue
            }
            lastPosition = now
            // 跨桶才重算活跃区间,见 SpecialDanmakuHostState.activeDanmaku。
            state.onPositionChanged(now)
            cache.prewarm(state.activeDanmaku, now)
            framePositionMillis = now
            frameVersion++
        }
    }

    // **必须裁到自己的边界。** 作者常把坐标写在画外让弹幕飞进来,溢出是常态不是异常。全屏时
    // 画布铺满屏幕,溢出看不出来;退出全屏后它只有视频那一块,不裁的话弹幕会画到视频外面、
    // 盖在页面其它内容上。
    Canvas(modifier = modifier.clipToBounds().onSizeChanged { canvasSize = it }) {
        // 读一次 frameVersion,让绘制块订阅帧循环的写入。
        @Suppress("UNUSED_EXPRESSION")
        frameVersion

        if (canvasSize.width == 0 || canvasSize.height == 0) return@Canvas
        cache.beginFrame()
        for (item in state.activeDanmaku) {
            val motion = item.motionAt(framePositionMillis) ?: continue
            // 归一化坐标指的是文字**左上角**在画布上的落点,不是中心。
            cache.draw(this, item, motion, motion.x * size.width, motion.y * size.height)
        }
        cache.endFrame(state.activeDanmaku)
    }
}

/**
 * 定位弹幕的图层缓存:每条一份录好描边与填充的 [GraphicsLayer],按 [SpecialDanmaku.id] 索引。
 *
 * 生命周期跟着活跃区间走:条目离开 [SpecialDanmakuHostState.activeDanmaku] 就释放。定位弹幕
 * 不像滚动弹幕那样会大量重复同一句话,按文本共享图层省不下什么,还得处理同文本不同旋转。
 */
internal class SpecialDanmakuRenderCache(
    private val measurer: TextMeasurer,
    private val graphicsContext: GraphicsContext,
    private val density: Density,
    private val layoutDirection: LayoutDirection,
    private val style: DanmakuRenderStyle,
    private val canvasSize: IntSize,
) {
    private class Entry(val layer: GraphicsLayer, val padPx: Float, var alpha: Float)

    private val entries = HashMap<String, Entry>()
    private var released = false

    /** 上一次按它清理过的活跃列表。活跃列表按桶整体替换,身份变了才需要再清一次。 */
    private var sweptActive: List<SpecialDanmaku>? = null

    private val strokeStyle: Stroke? = if (style.strokeWidthPx > 0f) {
        Stroke(width = style.strokeWidthPx, miter = 3f, join = StrokeJoin.Round)
    } else {
        null
    }

    fun beginFrame() {}

    /**
     * 提前录好即将进场的那些。字符画类视频的几百条常在同一毫秒进场,不提前录就会全部挤进
     * 进场那一帧;按时间预算分摊到前面的帧,预算用完就留给下一帧。
     */
    fun prewarm(active: List<SpecialDanmaku>, positionMillis: Long) {
        if (released) return
        val start = TimeSource.Monotonic.markNow()
        val horizon = positionMillis + PREWARM_LOOKAHEAD_MILLIS
        for (item in active) {
            if (item.startTimeMillis > horizon) break
            if (item.endTimeMillis <= positionMillis || item.id in entries) continue
            entries[item.id] = create(item)
            if (start.elapsedNow() >= PREWARM_BUDGET) return
        }
    }

    fun draw(scope: DrawScope, item: SpecialDanmaku, motion: SpecialDanmakuMotion, x: Float, y: Float) {
        if (released) return
        val alpha = motion.alpha * style.opacity
        if (alpha <= 0f) return
        // 没预热到的当场录:掉一条比多花这一帧糟糕。
        val entry = entries.getOrPut(item.id) { create(item) }
        if (entry.alpha != alpha) {
            entry.layer.alpha = alpha
            entry.alpha = alpha
        }
        scope.translate(x - entry.padPx, y - entry.padPx) {
            drawLayer(entry.layer)
        }
    }

    /** 活跃区间换过之后,释放不在新区间里的图层。 */
    fun endFrame(active: List<SpecialDanmaku>) {
        if (released || active === sweptActive) return
        sweptActive = active
        val keep = HashSet<String>(active.size * 2)
        for (item in active) keep += item.id
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (id, entry) = iterator.next()
            if (id in keep) continue
            graphicsContext.releaseGraphicsLayer(entry.layer)
            iterator.remove()
        }
    }

    fun release() {
        if (released) return
        released = true
        for (entry in entries.values) graphicsContext.releaseGraphicsLayer(entry.layer)
        entries.clear()
    }

    private fun create(item: SpecialDanmaku): Entry {
        val fontSizePx = item.fontSizeFraction * canvasSize.height
        val textStyle = style.baseTextStyle.copy(fontSize = with(density) { fontSizePx.toSp() })
        // 不传约束:一条比画面还宽的弹幕(作者常这么干,让它从画外飞进来)要是被压回画面宽度,
        // 文字会被迫折行,那是排版被改了,不是位置被改了。
        val layout = measurer.measure(text = item.text, style = textStyle)
        val stroke = strokeStyle.takeIf { item.hasStroke }
        // 描边沿字形轮廓居中,向外溢出半个线宽,录制画布四周留出这圈余量,理由同 DanmakuRenderCache。
        val pad = if (stroke == null) 0 else ceil(style.strokeWidthPx / 2f).toInt() + 1
        val layer = graphicsContext.createGraphicsLayer()
        // 不用 Offscreen:它让 HWUI 为每条留一张持久纹理,实测字符画视频上 RenderThread 没省下,
        // 主线程反而多出一成,超过 12ms 的帧从 3 个涨到 14 个。
        layer.compositingStrategy = CompositingStrategy.ModulateAlpha
        layer.rotationZ = item.rotateZDegrees
        layer.rotationY = item.rotateYDegrees
        // cameraDistance 的默认值是 8 像素,对一个几百像素宽的图层来说相当于把相机贴在字面上:
        // 稍一转 rotationY 就会有极端的透视畸变,甚至部分转到相机背后出现绘制瑕疵。按画面高度取,
        // 画得出透视又不失真。
        layer.cameraDistance = canvasSize.height * CAMERA_DISTANCE_FACTOR
        val topLeft = Offset(pad.toFloat(), pad.toFloat())
        val color = Color(item.color or ALPHA_OPAQUE_MASK)
        layer.record(density, layoutDirection, IntSize(layout.size.width + pad * 2, layout.size.height + pad * 2)) {
            if (stroke != null) {
                drawText(textLayoutResult = layout, color = style.strokeColor, topLeft = topLeft, drawStyle = stroke)
            }
            // 必须显式传 Fill:两遍共享同一份 TextLayoutResult,省略参数会沿用上一遍设进底层
            // paragraph 的 Stroke,画出空心字。理由详见 DanmakuRenderCache.record。
            drawText(textLayoutResult = layout, color = color, topLeft = topLeft, drawStyle = Fill)
        }
        return Entry(layer, pad.toFloat(), alpha = 1f)
    }
}

private const val ALPHA_OPAQUE_MASK = 0xFF000000.toInt()

private const val PAUSED_POLL_MILLIS = 100L

/** 预热提前量。比进场早这么久开始录,够几百条同时进场的字符画在前面的帧里分摊完。 */
private const val PREWARM_LOOKAHEAD_MILLIS = 1_500L

/** 每帧用于预热的时间上限。120Hz 一帧 8.3ms,留给预热的不能超过它的一小半。 */
private val PREWARM_BUDGET = 3.milliseconds

/** 相机距离取画面高度的这个倍数;2 倍在 1080p 上约 2160px,跟常见 mode 7 实现的透视强度接近。 */
private const val CAMERA_DISTANCE_FACTOR = 2f
