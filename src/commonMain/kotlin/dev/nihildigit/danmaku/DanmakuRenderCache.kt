package dev.nihildigit.danmaku

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 渲染层的可调外观参数。库不带主题(不引 material3),字体、颜色兜底、描边、透明度全部由
 * 宿主 app 注入。
 *
 * 行高不在这里:它同时决定轨道数和轨道 y,是排布输入,只能有一份,放在
 * [DanmakuLayoutConfig.trackHeightPx]。
 *
 * @param baseTextStyle 字号/字重/字体族的基准样式;逐条弹幕的颜色与字号(若非 null)会覆盖
 *   这里的 color/fontSize,其余属性(fontFamily/fontWeight 等)原样沿用。
 * @param globalFontSizeSp 弹幕自身 [Danmaku.fontSize] 为 null 时使用的字号;两者都缺省时
 *   退回 [baseTextStyle] 自带的字号。
 * @param strokeWidthPx 描边宽度,<= 0 时跳过整条描边绘制(不多画那一遍)。
 * @param strokeColor 描边颜色。
 * @param opacity 弹幕整体不透明度(用户设置项)。滚动、顶部、底部弹幕按整条乘在贴图上,描边
 *   不会从半透明的填充下透出来;重叠的弹幕之间照样互相透出,和主流播放器相同。**不套
 *   `Modifier.alpha`**:不透明度 < 1 时它强制整个 Canvas 分配全尺寸离屏 buffer。
 */
data class DanmakuRenderStyle(
    val baseTextStyle: TextStyle = TextStyle.Default,
    val globalFontSizeSp: Float? = null,
    val strokeWidthPx: Float = 3f,
    val strokeColor: Color = Color.Black,
    val opacity: Float = 1f,
)

/**
 * 渲染侧的缓存统计。和 [ProcessingReport] 分开是分层要求:那份是编排层的产物(纯 stdlib,
 * 要能整体搬进 commonMain),这份记的是平台排版与位图图集的行为,只在渲染层存在。属性名里的
 * layer 指一条弹幕在图集里的那一块位图。
 *
 * 计数器都是单调累加的,不在帧之间清零 —— 命中率要能跨整段播放看趋势,清零会把"刚 seek 完
 * 那几帧全是未命中"这种最值得看的现象洗掉。唯一的瞬时量是 [liveLayerCount]。
 *
 * 只在主线程(帧回调与绘制块)读写,不加同步。
 */
class DanmakuRenderStats {

    /** 排版缓存命中次数:准备一条弹幕时,同文本同排版属性的 `TextLayoutResult` 已经在表里。 */
    var layoutHitCount: Long = 0L
        private set

    /** 排版缓存未命中次数,每次对应一次真实的 `TextMeasurer.measure`。 */
    var layoutMissCount: Long = 0L
        private set

    /** 栅格化一条弹幕的次数,含所在页被回收后的重画。 */
    var layerCreatedCount: Long = 0L
        private set

    /** 直接贴已有位图的次数,每帧每条可见弹幕计一次。 */
    var layerReusedCount: Long = 0L
        private set

    /** 从缓存表里淘汰的条数。位图所占的页由图集整页回收,不在这里计。 */
    var layerReleasedCount: Long = 0L
        private set

    /** 缓存表当前的条数。这是瞬时量,不累加。 */
    var liveLayerCount: Int = 0
        private set

    /**
     * 绘制帧里不得已当场准备(排版 + 录制)的次数。预热窗口的意义就是让它保持为 0(除了刚
     * seek 完那一两帧),持续增长说明预热预算太小或者窗口太短 —— 这是这份统计里唯一需要报警
     * 的数。它不等于 [layoutMissCount]:当场准备时排版本身可能是命中的,贵的是录制那一步。
     */
    var latePrepareCount: Long = 0L
        private set

    val layoutHitRate: Float
        get() {
            val total = layoutHitCount + layoutMissCount
            return if (total == 0L) 0f else layoutHitCount.toFloat() / total
        }

    internal fun onLayoutHit() {
        layoutHitCount++
    }

    internal fun onLayoutMiss() {
        layoutMissCount++
    }

    internal fun onLayerCreated(live: Int) {
        layerCreatedCount++
        liveLayerCount = live
    }

    internal fun onLayerReused() {
        layerReusedCount++
    }

    internal fun onLayerReleased(live: Int) {
        layerReleasedCount++
        liveLayerCount = live
    }

    internal fun onLatePrepare() {
        latePrepareCount++
    }

    override fun toString(): String =
        "DanmakuRenderStats(layout hit=$layoutHitCount miss=$layoutMissCount rate=$layoutHitRate, " +
            "layer created=$layerCreatedCount reused=$layerReusedCount released=$layerReleasedCount " +
            "live=$liveLayerCount, latePrepare=$latePrepareCount)"
}

/**
 * 排版缓存的 key。**颜色不在里面**:颜色不影响排版,同一句话的红蓝两份该共用一次测量。
 * 反过来,所有影响排版的属性都必须在里面,而它们全部住在 [style] 这一个对象里 —— 字体族、
 * 字重、字号、locale、字间距、textDecoration 一个不落 —— 所以这里直接把整份解析后的
 * [TextStyle] 当 key,而不是手抄一份"我认为重要的属性"清单。抄清单这条路走不通:漏一个属性
 * 表现为"换了字体但排版没变",而且以后每加一个可配样式都要记得回来补,没人会记得。
 */
internal data class DanmakuLayoutKey(val text: String, val style: TextStyle, val images: List<DanmakuImage>)

/**
 * 位图块的 key。比 [DanmakuLayoutKey] 多一个颜色(颜色画进了位图),
 * 少一整个 [TextStyle] —— 因为整个 [DanmakuRenderCache] 本身就是按 style 建的(style 一变
 * 整个缓存重建并释放),缓存内部只可能有一份 baseTextStyle,再把它塞进逐帧查询的 key 里就是
 * 白算一次 [TextStyle.hashCode]。这个哈希不便宜(要走遍 SpanStyle + ParagraphStyle 的几十个
 * 字段),而这个 key 是每帧每条弹幕都要算一次的东西,320 条同屏时那点开销正是我们要省的。
 */
internal data class DanmakuLayerKey(
    val text: String,
    val fontSizeSp: Float?,
    val colorRgb: Int,
    val images: List<DanmakuImage>,
)

/**
 * 按键取图。库不加载图片,只在栅格化一条弹幕时问一次;返回 null 表示还没到,那条先留空位,
 * 之后每帧再问,到了就重画。
 *
 * 图要能画进软件画布:弹幕在 CPU 上栅格化进图集。Android 上不能给硬件位图
 * (`Bitmap.Config.HARDWARE`,Coil 等加载库的默认解码结果),否则栅格化时抛异常。
 */
fun interface DanmakuImageSource {
    fun imageOrNull(key: String): ImageBitmap?

    companion object {
        val None: DanmakuImageSource = DanmakuImageSource { null }
    }
}

/**
 * 排版一条弹幕。**编排测宽与渲染录制都走这里**,两边的宽度因此必然一致:图的占位、字号解析
 * 任何一处只在一边做,排布就按一套宽度算、画面按另一套画。
 */
internal fun TextMeasurer.layoutDanmaku(danmaku: Danmaku, style: TextStyle): TextLayoutResult {
    val images = danmaku.validImages()
    if (images.isEmpty()) return measure(text = danmaku.text, style = style)
    return measure(
        text = AnnotatedString(danmaku.text),
        style = style,
        placeholders = images.map {
            AnnotatedString.Range(
                Placeholder(it.widthEm.em, it.heightEm.em, PlaceholderVerticalAlign.Center),
                it.start,
                it.end,
            )
        },
    )
}

/**
 * 滚动、顶部、底部弹幕的绘制准备,分两级:
 *
 * 1. **prepared layout** —— 弹幕进入预热窗口时测一次,`TextLayoutResult` 存下来。绘制帧
 *    **不调用** [TextMeasurer.measure];真的漏了(刚 seek 完那一两帧)会当场补测并记一次
 *    [DanmakuRenderStats.latePrepareCount],不是把这条弹幕吞掉。
 * 2. **位图图集** —— 描边、填充、表情图在 CPU 上画进 [DanmakuSpriteAtlas] 的一块,之后每帧
 *    一次贴图。
 *
 * 第二级上一版是每条一个 `GraphicsLayer`(一份 display list)。不限密度、同屏 250 条时,
 * RenderThread 八成以上的时间耗在每帧重画文字、往 Skia 字形图集里补字形,以及每个 RenderNode
 * 的固定开销上;换成图集后,同一视频同一位置超过 12ms 的帧从 29 个降到 13 个。缘由见
 * [DanmakuSpriteAtlas]。
 *
 * 透明度乘在贴图用的 Paint 上,样式变化时整个缓存重建。
 *
 * 调用方要用 `DisposableEffect` 保证 [release] 一定被调到。
 */
internal class DanmakuRenderCache(
    private val measurer: TextMeasurer,
    private val density: Density,
    private val layoutDirection: LayoutDirection,
    private val style: DanmakuRenderStyle,
    val stats: DanmakuRenderStats,
    private val imageSource: DanmakuImageSource = DanmakuImageSource.None,
    private val maxLayers: Int = MAX_LAYERS,
    private val maxLayouts: Int = MAX_LAYOUTS,
) {

    private val strokeStyle: Stroke? = if (style.strokeWidthPx > 0f) {
        Stroke(width = style.strokeWidthPx, miter = 3f, join = StrokeJoin.Round)
    } else {
        null
    }

    /**
     * 位图块四周的余量。[Stroke] 沿字形轮廓**居中**描,向外溢出半个线宽,而排版尺寸是按文字
     * bounds 报的 —— 不留余量的话,描边的外半边会被块的边界裁掉。留一圈之后栅格化时把文字画在
     * `(pad, pad)`,贴图时反向平移回去,位置不变。
     */
    private val padPx: Int = if (strokeStyle == null) 0 else ceil(style.strokeWidthPx / 2f).toInt() + 1

    /** 按字号缓存解析后的 [TextStyle]。字号实际只有几档,这张表最多几个条目。 */
    private val styleByFontSize = HashMap<Float, TextStyle>()

    // 查询即"最近使用",淘汰从表头开始 —— 靠 [getAndTouch] 维持,见那里的注释。
    private val layouts = LinkedHashMap<DanmakuLayoutKey, TextLayoutResult>(INITIAL_CAPACITY, LOAD_FACTOR)
    private val layers = LinkedHashMap<DanmakuLayerKey, LayerEntry>(INITIAL_CAPACITY, LOAD_FACTOR)

    private var frameId = 0L
    private var released = false

    private val atlas = DanmakuSpriteAtlas(density, layoutDirection)
    private val spritePaint = atlas.paint(style.opacity)

    /** [imagesPending] 为真时,栅格化那一刻有图还没到,[draw] 每帧检查一次,到齐就重画。 */
    private class LayerEntry(
        var slot: DanmakuSpriteAtlas.Slot,
        var lastUsedFrame: Long,
        val danmaku: Danmaku,
        var imagesPending: Boolean,
    )

    /** 绘制一帧的开始。帧号是[endFrame] 判断"这条本帧还在用、不能回收"的依据。 */
    fun beginFrame() {
        frameId++
    }

    /**
     * 准备一条弹幕:排版 + 栅格化进图集。已经准备过就只更新 LRU 位置。
     * 返回是否发生了真实的准备工作,预热循环用它扣预算。
     */
    fun prepare(danmaku: Danmaku): Boolean {
        if (released) return false
        val key = layerKeyOf(danmaku, style)
        val existing = layers.getAndTouch(key)
        if (existing != null) {
            existing.lastUsedFrame = frameId
            return false
        }
        create(key, danmaku)
        return true
    }

    /**
     * 画一条已准备好的弹幕。没准备过就当场补一份 —— 掉一条弹幕比多花一帧的钱糟糕得多,
     * 但这条路径会记进 [DanmakuRenderStats.latePrepareCount],让"预热没跟上"是可观测的。
     */
    fun draw(scope: DrawScope, danmaku: Danmaku, x: Float, y: Float) {
        if (released) return
        val key = layerKeyOf(danmaku, style)
        val entry = layers.getAndTouch(key) ?: run {
            stats.onLatePrepare()
            create(key, danmaku)
        }
        entry.lastUsedFrame = frameId
        if (!entry.slot.valid) {
            // 所在的页被回收了(长时间没画,又回到了屏上,比如回退 seek)。
            val layout = layoutOf(entry.danmaku)
            entry.slot = allocate(layout)
            entry.imagesPending = rasterize(entry.slot, layout, entry.danmaku)
            stats.onLayerCreated(layers.size)
        } else if (entry.imagesPending && entry.danmaku.validImages().all { imageSource.imageOrNull(it.key) != null }) {
            entry.imagesPending = rasterize(entry.slot, layoutOf(entry.danmaku), entry.danmaku)
        }
        stats.onLayerReused()
        atlas.touch(entry.slot, frameId)
        // 贴到整像素上:位图一比一贴,落在小数像素上就要插值,字会发虚。
        atlas.draw(scope, entry.slot, (x - padPx).roundToInt(), (y - padPx).roundToInt(), spritePaint)
    }

    /** 绘制一帧的结束:把超出上限的条目淘汰掉。本帧用过的绝不回收。 */
    fun endFrame() {
        trimLayers()
        trimLayouts()
    }

    /** 放掉图集。调用后这个实例不再可用。 */
    fun release() {
        if (released) return
        released = true
        layers.clear()
        layouts.clear()
        styleByFontSize.clear()
        atlas.release()
    }

    private fun create(key: DanmakuLayerKey, danmaku: Danmaku): LayerEntry {
        val layout = layoutOf(danmaku)
        val slot = allocate(layout)
        val pending = rasterize(slot, layout, danmaku)
        val entry = LayerEntry(slot, frameId, danmaku, pending)
        layers[key] = entry
        stats.onLayerCreated(layers.size)
        return entry
    }

    private fun allocate(layout: TextLayoutResult): DanmakuSpriteAtlas.Slot =
        atlas.allocate(layout.size.width + padPx * 2, layout.size.height + padPx * 2, frameId)

    /**
     * 画描边 + 填充两遍,再画表情图。返回是否有图还没到。
     *
     * **第二遍必须显式传 `drawStyle = Fill`,不能省略。** `drawText` 的 `drawStyle` 默认值是
     * `null`,语义是"不覆盖底层 paragraph 已经设过的绘制方式",不是"用 Fill" —— 两遍共享同一份
     * [TextLayoutResult],第一遍把 `Stroke` 设进了底层 paragraph,第二遍不传就会继续描边,
     * 肉眼看是空心字。凡是共享 [TextLayoutResult] 做多遍绘制的地方都有这个坑。
     */
    private fun rasterize(slot: DanmakuSpriteAtlas.Slot, layout: TextLayoutResult, danmaku: Danmaku): Boolean {
        val topLeft = Offset(padPx.toFloat(), padPx.toFloat())
        val stroke = strokeStyle
        // danmaku.color 是不带 alpha 的 24 位 RGB,直接塞进 Color(Int) 会被当成 0x00RRGGBB
        // (alpha=0,全透明),必须先把 alpha 字节填满。
        val color = Color(danmaku.color or ALPHA_OPAQUE_MASK)
        var imagesPending = false
        atlas.render(slot) {
            if (stroke != null) {
                drawText(
                    textLayoutResult = layout,
                    color = style.strokeColor,
                    topLeft = topLeft,
                    drawStyle = stroke,
                )
            }
            drawText(
                textLayoutResult = layout,
                color = color,
                topLeft = topLeft,
                drawStyle = Fill,
            )
            // 图画在占位上,不描边:描边是给文字在任意底色上保持可读的,图自带轮廓。
            danmaku.validImages().forEachIndexed { index, image ->
                val rect = layout.placeholderRects.getOrNull(index) ?: return@forEachIndexed
                val bitmap = imageSource.imageOrNull(image.key)
                if (bitmap == null) {
                    imagesPending = true
                    return@forEachIndexed
                }
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset((rect.left + topLeft.x).roundToInt(), (rect.top + topLeft.y).roundToInt()),
                    dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                )
            }
        }
        return imagesPending
    }

    private fun layoutOf(danmaku: Danmaku): TextLayoutResult {
        val key = layoutKeyOf(danmaku, style, styleByFontSize)
        layouts.getAndTouch(key)?.let {
            stats.onLayoutHit()
            return it
        }
        stats.onLayoutMiss()
        val layout = measurer.layoutDanmaku(danmaku, key.style)
        layouts[key] = layout
        return layout
    }

    private fun trimLayers() {
        if (layers.size <= maxLayers) return
        val iterator = layers.entries.iterator()
        while (iterator.hasNext() && layers.size > maxLayers) {
            val entry = iterator.next().value
            // 本帧画过的不能回收:上限低于同屏条数时才会撞到这条,那时宁可暂时超限。
            if (entry.lastUsedFrame >= frameId) continue
            iterator.remove()
            stats.onLayerReleased(layers.size)
        }
    }

    /**
     * 排版表按纯 LRU 淘汰,不看帧号:位图画好之后就不再需要排版对象 —— 淘汰只意味着下次同
     * 文本要重测一次。
     */
    private fun trimLayouts() {
        if (layouts.size <= maxLayouts) return
        val iterator = layouts.keys.iterator()
        while (iterator.hasNext() && layouts.size > maxLayouts) {
            iterator.next()
            iterator.remove()
        }
    }

    companion object {
        /**
         * 缓存表上限。要明显大于峰值同屏条数(实测 1264×2780 面板、不限密度档的峰值是 320),
         * 否则每帧都在淘汰刚画过的东西。预热窗口里还有一批未上屏的,所以按峰值的一倍多给。表项
         * 只是图集里一块位置的引用,位图的内存由图集的页数上限管。
         */
        const val MAX_LAYERS = 768

        /** 排版表上限。同屏 + 预热之外还留一段最近使用窗口,让来回 seek 不必反复重测。 */
        const val MAX_LAYOUTS = 1024

        private const val INITIAL_CAPACITY = 128
        private const val LOAD_FACTOR = 0.75f
        private const val ALPHA_OPAQUE_MASK = 0xFF000000.toInt()
    }
}

/**
 * 取值,并把这条移到插入顺序的尾部 —— 于是"尾部最新、头部最旧",淘汰照旧从头扫。
 *
 * JVM 上这件事本来由 `java.util.LinkedHashMap(capacity, loadFactor, accessOrder = true)` 做,
 * 但那个三参构造是 `java.util` 专有的,commonMain 里不存在:Kotlin 的 [LinkedHashMap] 只承诺
 * 插入顺序,而且对已存在的 key 再 `put` 一次不会改变它的位置,所以必须先 `remove` 再 `put`。
 *
 * 代价是命中路径上多一次哈希查找。这条路径每帧每条弹幕走一次,实测远小于它省下的一次重排版。
 */
private fun <K, V> LinkedHashMap<K, V>.getAndTouch(key: K): V? {
    val value = remove(key) ?: return null
    put(key, value)
    return value
}

/** 弹幕自身字号优先,其次全局字号,都没有就回落到 `baseTextStyle` 自带的字号(返回 null)。 */
internal fun fontSizeSpOf(danmaku: Danmaku, style: DanmakuRenderStyle): Float? =
    danmaku.fontSize?.toFloat() ?: style.globalFontSizeSp

/**
 * 解析出这条弹幕真正用来排版的 [TextStyle]。[styleByFontSize] 是可选的复用表:`copy` 本身不贵,
 * 但字号实际只有几档,存下来就把准备路径上的这点分配清零了;不传(测试里)只是每次新建一份,
 * 结果完全相同 —— 这一点是 key 正确性的前提,[TextStyle] 的相等性不看身份。
 */
internal fun resolvedTextStyleOf(
    danmaku: Danmaku,
    style: DanmakuRenderStyle,
    styleByFontSize: MutableMap<Float, TextStyle>? = null,
): TextStyle {
    val fontSizeSp = fontSizeSpOf(danmaku, style) ?: return style.baseTextStyle
    val build = { style.baseTextStyle.copy(fontSize = fontSizeSp.sp) }
    return styleByFontSize?.getOrPut(fontSizeSp, build) ?: build()
}

internal fun layoutKeyOf(
    danmaku: Danmaku,
    style: DanmakuRenderStyle,
    styleByFontSize: MutableMap<Float, TextStyle>? = null,
): DanmakuLayoutKey =
    DanmakuLayoutKey(danmaku.text, resolvedTextStyleOf(danmaku, style, styleByFontSize), danmaku.validImages())

internal fun layerKeyOf(danmaku: Danmaku, style: DanmakuRenderStyle): DanmakuLayerKey =
    DanmakuLayerKey(danmaku.text, fontSizeSpOf(danmaku, style), danmaku.color, danmaku.validImages())
