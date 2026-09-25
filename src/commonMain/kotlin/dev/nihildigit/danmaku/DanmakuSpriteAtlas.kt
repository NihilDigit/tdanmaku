package dev.nihildigit.danmaku

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

/**
 * 弹幕位图图集:整条弹幕(描边、填充、表情图)在 CPU 上画一次,落进几张共享的大位图里,之后每帧
 * 只从位图上取一块贴出去。
 *
 * 为什么不交给平台的字形图集:HWUI 给 Skia 的 A8 字形图集上限是窗口面积向上取 2 的幂(1264×2780
 * 的屏上是 4MB),应用改不了。不限密度下同屏两百多种中文文本,每个字形还要描边、填充各一份,
 * 图集每帧都在淘汰重传。整条弹幕做成位图就绕开了它:同一页上的弹幕共用一张纹理,Skia 能把它们
 * 合成一批画;也不再每条弹幕一个 RenderNode。
 *
 * 为什么不是每条一张独立位图:试过(每条一个 Offscreen 的 GraphicsLayer),两百多张各不相同的
 * 纹理合不了批,合批判断本身就吃掉 13% 的 RenderThread,还多占近 90MB 显存。
 *
 * 页按顺序写满,满了开新页;页数到上限后回收最久没画过的一页。弹幕几秒就穿过屏幕,页基本是
 * 先写先弃,整页回收足够,不需要逐块的空闲表。页的内容一改,平台就得把整页重新上传一次,所以
 * 写入要攒着做(预热按批进来),页也不能太大。
 */
internal class DanmakuSpriteAtlas(
    private val density: Density,
    private val layoutDirection: LayoutDirection,
) {
    class Page(val image: ImageBitmap, val pooled: Boolean) {
        val canvas = Canvas(image)
        var generation = 0
        var cursorX = 0
        var cursorY = 0
        var rowHeight = 0
        var lastUsedFrame = 0L
    }

    /** 页上的一块。页被回收后 [valid] 变假,持有它的一方要重新申请、重画。 */
    class Slot(val page: Page, private val generation: Int, val x: Int, val y: Int, val width: Int, val height: Int) {
        val valid: Boolean get() = page.generation == generation
    }

    private val pages = ArrayList<Page>()
    private val drawScope = CanvasDrawScope()
    private val clearPaint = Paint().apply { blendMode = BlendMode.Clear }

    fun allocate(width: Int, height: Int, frameId: Long): Slot {
        if (width > PAGE_WIDTH || height > PAGE_HEIGHT) {
            // 比一页还大的(超长弹幕、大表情)单独一张,不进池子,没人引用了就随它回收。
            val page = Page(ImageBitmap(width, height), pooled = false)
            page.lastUsedFrame = frameId
            return Slot(page, page.generation, 0, 0, width, height)
        }
        pages.lastOrNull()?.let { current -> place(current, width, height)?.let { return it } }
        val page = nextPage(frameId)
        return place(page, width, height) ?: error("空页放不下 ${width}x$height")
    }

    fun touch(slot: Slot, frameId: Long) {
        slot.page.lastUsedFrame = frameId
    }

    /** 清掉这一块再画。[block] 的坐标原点在块的左上角。 */
    fun render(slot: Slot, block: DrawScope.() -> Unit) {
        val canvas = slot.page.canvas
        canvas.drawRect(
            slot.x.toFloat(),
            slot.y.toFloat(),
            (slot.x + slot.width).toFloat(),
            (slot.y + slot.height).toFloat(),
            clearPaint,
        )
        canvas.save()
        canvas.clipRect(
            slot.x.toFloat(),
            slot.y.toFloat(),
            (slot.x + slot.width).toFloat(),
            (slot.y + slot.height).toFloat(),
        )
        drawScope.draw(density, layoutDirection, canvas, Size(slot.page.image.width.toFloat(), slot.page.image.height.toFloat())) {
            translate(slot.x.toFloat(), slot.y.toFloat()) { block() }
        }
        canvas.restore()
    }

    /**
     * 直接调画布的 `drawImageRect`,Paint 预先配好。`DrawScope.drawImage` 每次都把 alpha、混合
     * 模式、滤镜质量逐项写回一个共用的 Paint,每项一次 JNI;同屏两百多条时,光这一步就占了主线程
     * 23%。
     */
    fun draw(scope: DrawScope, slot: Slot, x: Int, y: Int, paint: Paint) {
        val size = IntSize(slot.width, slot.height)
        scope.drawContext.canvas.drawImageRect(
            image = slot.page.image,
            srcOffset = IntOffset(slot.x, slot.y),
            srcSize = size,
            dstOffset = IntOffset(x, y),
            dstSize = size,
            paint = paint,
        )
    }

    /** 贴图用的 Paint。样式不变,一个缓存实例从头用到尾。 */
    fun paint(alpha: Float): Paint = Paint().apply {
        this.alpha = alpha
        // 贴的位置是整像素、尺寸一比一,不需要插值。
        filterQuality = FilterQuality.None
    }

    fun release() {
        pages.clear()
    }

    private fun place(page: Page, width: Int, height: Int): Slot? {
        if (page.cursorX + width > PAGE_WIDTH) {
            page.cursorY += page.rowHeight
            page.cursorX = 0
            page.rowHeight = 0
        }
        if (page.cursorY + height > PAGE_HEIGHT) return null
        val slot = Slot(page, page.generation, page.cursorX, page.cursorY, width, height)
        page.cursorX += width
        if (height > page.rowHeight) page.rowHeight = height
        return slot
    }

    /** 新开一页,或回收一页本帧没画过、最久没用的。回收的页挪到末尾当作当前页。 */
    private fun nextPage(frameId: Long): Page {
        if (pages.size < MAX_PAGES) {
            return Page(ImageBitmap(PAGE_WIDTH, PAGE_HEIGHT), pooled = true).also { pages += it }
        }
        val victim = pages.filter { it.lastUsedFrame < frameId }.minByOrNull { it.lastUsedFrame }
            // 每一页本帧都在用:同屏装不下,临时多开一页,宁可超限也不擦掉正在画的。
            ?: return Page(ImageBitmap(PAGE_WIDTH, PAGE_HEIGHT), pooled = true).also { pages += it }
        pages.remove(victim)
        pages += victim
        victim.canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), clearPaint)
        victim.generation++
        victim.cursorX = 0
        victim.cursorY = 0
        victim.rowHeight = 0
        return victim
    }

    private companion object {
        /**
         * 一页 1024×160 ARGB,640KB,放两行弹幕。页每改一次,平台就上传一份新纹理,旧的留在
         * Skia 的资源缓存里直到预算用满才挤掉,所以页越小,每次改动留下的旧版本越小。实测不限
         * 密度:1024×512 时超过 12ms 的帧 21 个、常驻显存约 96MB;1024×160 时 13 个、约 48MB。
         */
        const val PAGE_WIDTH = 1024
        const val PAGE_HEIGHT = 160

        /** 池子上限约 30MB。 */
        const val MAX_PAGES = 48
    }
}
