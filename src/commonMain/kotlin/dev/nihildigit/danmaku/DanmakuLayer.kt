package dev.nihildigit.danmaku

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 弹幕层的可调项。
 *
 * @param fontSizeSp 字号。**绝对量,不是画布的函数** —— 它关乎眼睛到屏幕的距离,不关乎播放器
 *   窗口开了多大。反过来「轨道数定死、拿画布高度反推字号」会在小窗口上算出偏大的字。
 * @param scrollShowArea 滚动与顶部弹幕占画面高度的比例。底部弹幕不受它约束,理由见
 *   [DanmakuViewport]。
 * @param scrollDurationMillis 统一穿屏时长:**所有**滚动弹幕都在这个时间内走完「视口宽 +
 *   自身宽」,长弹幕因此更快。
 * @param lineHeightRatio 行高相对字号的倍数。轨道数由它和视口高度推出。
 * @param strokeWidthRatio 描边宽度相对字号的比例。描边沿字形轮廓居中,太粗会糊住细笔画。
 */
data class DanmakuOptions(
    val fontSizeSp: Float = 15f,
    val opacity: Float = 1f,
    val density: DanmakuDensity = DanmakuDensity.STANDARD,
    val frameRateCap: DanmakuFrameRateCap = DanmakuFrameRateCap.FPS_60,
    val scrollShowArea: Float = 0.75f,
    val scrollDurationMillis: Long = 6_500L,
    val lineHeightRatio: Float = 1.6f,
    val strokeWidthRatio: Float = 0.06f,
)

/**
 * 弹幕层的操作面。
 *
 * 它替调用方管住两件本该由库保证、却曾经写在文档里让调用方自己遵守的事:
 *
 * 1. **测量与渲染同源。** 排布用的宽度和真正画出来的宽度必须来自同一份字体与字号,否则排布
 *    按一套算、画面按另一套画。这里的测量器和 [DanmakuHost] 的渲染样式由同一份
 *    [DanmakuOptions] 推出,对不齐这件事在结构上不成立。
 * 2. **画布尺寸。** 排布要在知道画布多大之后才有意义,而画布尺寸只有布局跑完才知道。层内部
 *    接住这个回调并重编,调用方不必参与。
 *
 * 弹幕从哪来由调用方决定:整池已知就 [setPool],逐条到达就 [appendNow]。
 */
@Stable
class DanmakuController internal constructor(
    internal val clock: DanmakuClock,
    internal val options: DanmakuOptions,
    private val measurer: TextMeasurer,
    private val displayDensity: Density,
    baseTextStyle: TextStyle,
) {

    private val fontSizePx = options.fontSizeSp * displayDensity.density * displayDensity.fontScale

    internal val style = DanmakuRenderStyle(
        baseTextStyle = baseTextStyle,
        globalFontSizeSp = options.fontSizeSp,
        strokeWidthPx = fontSizePx * options.strokeWidthRatio,
        opacity = options.opacity.coerceIn(0.1f, 1f),
    )

    /**
     * 按文本缓存量出来的宽高。文字尺寸只取决于文本和样式,跟画布多大无关;而画布尺寸在进场
     * 那零点几秒里会变好几次(控件、insets 稳定的过程),每变一次都要重排。不缓存的话那几次
     * 重排会把同一批文本反复重量。
     *
     * 不靠 [TextMeasurer] 自带的 LRU:它默认只有 8 项,大池子命中率约等于零;而把它调大意味着
     * 缓存整份 `TextLayoutResult`,那是渲染路径才需要的东西,排布只要两个 float。
     *
     * **有上限。** 无界的话直播会一直涨:那边每条文本基本都是新的,而 `trimBefore` 丢的是
     * 弹幕和排布结果,丢不掉这张表里的字符串。点播的池子本来有界,这个上限碰不到。
     */
    private val sizeCache = LinkedHashMap<String, DanmakuTextSize>()

    private val measureStyle = style.baseTextStyle.copy(fontSize = options.fontSizeSp.sp)

    private fun measure(danmaku: Danmaku): DanmakuTextSize {
        // remove + put 把命中的挪到插入顺序的尾部,于是「尾部最新、头部最旧」,淘汰从头取。
        // Kotlin 的 LinkedHashMap 只承诺插入顺序,对已存在的 key 再 put 一次不改变它的位置。
        sizeCache.remove(danmaku.text)?.let {
            sizeCache[danmaku.text] = it
            return it
        }
        val size = measurer.measure(text = danmaku.text, style = measureStyle).size
        val measured = DanmakuTextSize(size.width.toFloat(), size.height.toFloat())
        if (sizeCache.size >= MAX_MEASURED_TEXTS) {
            val eldest = sizeCache.keys.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
        sizeCache[danmaku.text] = measured
        return measured
    }

    // 先按 1×1 建起来,而不是等布局跑完。这样第一帧就有一个真正的 host 挂上去,由它把真实
    // 画布尺寸经 onCanvasSizeMismatch 报回来并重建。占位那一帧算出 0 条轨道、什么都排不上,
    // 但它只存在一帧;换成「没有尺寸就不挂 host」会死锁 —— 没有 host 就永远量不到尺寸。
    internal var canvasWidthPx by mutableFloatStateOf(1f)
        private set
    internal var canvasHeightPx by mutableFloatStateOf(1f)
        private set

    /** 定位/运动弹幕。跟普通弹幕共用同一个时钟,不另起一个 —— 两层各走各的时钟会漂。 */
    internal val specialState = SpecialDanmakuHostState(clock)

    // 构造完就存在,不会是 null:上面那个 1×1 的占位尺寸保证了这一点。
    internal var session by mutableStateOf(buildSession(emptyList()))
        private set

    internal fun onCanvasSize(widthPx: Float, heightPx: Float) {
        if (widthPx == canvasWidthPx && heightPx == canvasHeightPx) return
        canvasWidthPx = widthPx
        canvasHeightPx = heightPx
        rebuild()
    }

    private fun rebuild() {
        session = buildSession(session.compiler.danmaku)
    }

    /**
     * 重建时从**上一个编排器**取池子,controller 自己不存一份。
     *
     * 存一份的代价是它只会被 [setPool] 更新:[appendNow] 和 [trimBefore] 改的是编排器里那份,
     * 于是转屏、分屏这类触发重建的事件一发生,新编排器就从一份过期的池子起步 —— 直播追加进来
     * 的整屏弹幕消失,点播裁掉的旧弹幕复活。
     */
    private fun buildSession(pool: List<Danmaku>): DanmakuSession {
        val layout = DanmakuLayoutConfig(
            canvasWidthPx = canvasWidthPx,
            canvasHeightPx = canvasHeightPx,
            trackHeightPx = fontSizePx * options.lineHeightRatio,
            viewport = DanmakuViewport.topAnchored(options.scrollShowArea),
            scrollDurationMillis = options.scrollDurationMillis,
        )
        val compiler = DanmakuCompiler(layout, options.density.createScheduler(layout), measure = ::measure)
        compiler.setPool(pool)
        compiler.advanceTo(clock.positionMillis)
        return DanmakuSession(compiler, DanmakuHostState(clock, compiler.timeline, options.frameRateCap))
    }

    /**
     * 换一份弹幕池。编排器自己判断新池子是不是旧池子的时间尾部扩展:是就接着排,不是就作废重建。
     */
    fun setPool(danmaku: List<Danmaku>) {
        session.compiler.setPool(danmaku)
        if (session.compiler.advanceTo(clock.positionMillis)) session.hostState.notifyChanged()
    }

    /** 定位/运动弹幕。位置由作者给定,不选轨、不判碰撞,所以整份赋值即可。 */
    fun setSpecial(danmaku: List<SpecialDanmaku>) {
        specialState.danmaku = danmaku
    }

    /**
     * 追加一条,时间戳取**此刻的播放位置**。给逐条到达的来源(直播)用。
     *
     * 服务端给的时间戳和播放器的时间轴不是同一根,对时是一整类问题;用播放器此刻的位置打戳,
     * 两者天然同轴。往后挪一点点是最小提前量:排在「此刻」的那一条已经错过当前帧。
     *
     * 返回 false 表示被拒 —— 时间早于池尾。调用方要么丢弃,要么在自己那层做重排缓冲。
     */
    fun appendNow(danmaku: Danmaku): Boolean {
        val position = clock.positionMillis
        val accepted = session.compiler.append(danmaku.copy(playTimeMillis = position + EMIT_LEAD_MILLIS))
        if (accepted) {
            session.compiler.advanceTo(position)
            session.hostState.notifyChanged()
        }
        return accepted
    }

    /**
     * 丢掉 `positionMillis` 之前已经用不到的东西。**开放式来源必须定期调它**,否则一场几小时的
     * 直播会把每条弹幕连同排布结果一直攒在内存里。不可逆。
     */
    fun trimBefore(positionMillis: Long): Int = session.compiler.trimBefore(positionMillis)

    /**
     * 叫醒帧循环。屏上没有可见弹幕时循环会挂起,而 seek、恢复播放、变速这些外部事件它看不见 ——
     * 不调也不会永远卡住(有兜底轮询),只是响应会迟到那么一下。
     */
    fun notifyChanged() {
        session.compiler.advanceTo(clock.positionMillis)
        session.hostState.notifyChanged()
    }

    /** 编排统计,给日志用。窗口内的,不是整池的。 */
    val report: ProcessingReport get() = session.compiler.report

    /** 峰值同屏条数。O(n log n) 的一次扫描,只在真要看它时调。 */
    fun peakConcurrency(): Int = session.compiler.timeline.peakConcurrency()

    private companion object {
        const val EMIT_LEAD_MILLIS = 100L

        /** 测量缓存的条数上限。同屏加预热窗口远小于这个数,留出的余量是给来回 seek 的。 */
        const val MAX_MEASURED_TEXTS = 4096
    }
}

/** 一次「重建编排」的产出:编排器 + host 状态。弹幕池的游标在编排器里,这里不再记一份。 */
internal class DanmakuSession(
    val compiler: DanmakuCompiler,
    val hostState: DanmakuHostState,
)

/**
 * 建一个 [DanmakuController]。
 *
 * [contentKey] 变化时整池重编:换一集、换一个直播间,旧时间轴里的一切都作废。为空表示内容不换。
 */
@Composable
fun rememberDanmakuController(
    clock: DanmakuClock,
    options: DanmakuOptions = DanmakuOptions(),
    baseTextStyle: TextStyle = TextStyle.Default,
    contentKey: Any? = null,
): DanmakuController {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(clock, options, baseTextStyle, contentKey, measurer, density) {
        DanmakuController(clock, options, measurer, density, baseTextStyle)
    }
}

/**
 * 画弹幕。压在画面之上、控件之下 —— 声明顺序即 z 序。
 *
 * 没有 pointerInput,不拦截手势;底下的点按与拖拽照常命中。不需要显示时**整个不进组合**,
 * 而不是画一个空的:帧循环由组合驱动,不进组合就没有那条协程。
 */
@Composable
fun DanmakuLayer(controller: DanmakuController, modifier: Modifier = Modifier) {
    // 编排窗口跟着播放位置往前推。每秒一次即可:窗口预留 30 秒,一秒的播放推进只会带进几条。
    // seek 不靠这个循环兜,那要等最坏一秒 —— 调用方在 seek 时调 notifyChanged。
    LaunchedEffect(controller) {
        while (true) {
            delay(WINDOW_ADVANCE_INTERVAL_MILLIS)
            val session = controller.session
            if (session.compiler.advanceTo(controller.clock.positionMillis)) {
                session.hostState.notifyChanged()
            }
        }
    }

    DanmakuHost(
        state = controller.session.hostState,
        style = controller.style,
        onCanvasSizeMismatch = controller::onCanvasSize,
        modifier = modifier.fillMaxSize(),
    )

    if (controller.specialState.danmaku.isNotEmpty()) {
        SpecialDanmakuHost(
            state = controller.specialState,
            style = controller.style,
            modifier = modifier.fillMaxSize(),
        )
    }
}

/** 窗口推进间隔。只需要远小于窗口预留量(30 秒),不必贴着帧率。 */
private const val WINDOW_ADVANCE_INTERVAL_MILLIS = 1_000L
