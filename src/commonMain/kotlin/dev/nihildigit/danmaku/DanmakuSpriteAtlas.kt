package dev.nihildigit.danmaku

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 弹幕位图图集:整条弹幕(描边、填充、表情图)在 CPU 上画一次,落进几张共享的大位图里,之后每帧
 * 只从页图层的显示列表里裁一块贴出去。
 *
 * 为什么不交给平台的字形图集:HWUI 给 Skia 的 A8 字形图集上限是窗口面积向上取 2 的幂(1264×2780
 * 的屏上是 4MB),应用改不了。不限密度下同屏两百多种中文文本,每个字形还要描边、填充各一份,
 * 图集每帧都在淘汰重传。整条弹幕做成位图就绕开了它:同一页上的弹幕共用一张纹理,Skia 能把它们
 * 合成一批画;也不再每条弹幕一个 RenderNode。
 *
 * 为什么不是每条一张独立位图:试过(每条一个 Offscreen 的 GraphicsLayer),两百多张各不相同的
 * 纹理合不了批,合批判断本身就吃掉 13% 的 RenderThread,还多占近 90MB 显存。
 *
 * 为什么每页外包一层 [GraphicsLayer] 而不是每条直接 `Canvas.drawImageRect(page.image)`:
 * 在 Skiko(Desktop/iOS)上,`Page.image` 挂了 `Canvas(image)` 之后底层 `SkBitmap` 是可变位图;
 * `SkiaBackedCanvas.drawImageRect` 每次调用都会走 `Image.makeFromBitmap` 整页深拷贝像素并生成新的
 * `SkImage` uniqueID,200 条同屏每帧要白拷上百 MB 像素且无法复用 GPU 纹理。把整页录制进一个
 * `alpha = 1` 的页级 [GraphicsLayer](不触发 `saveLayer` 离屏缓冲),只在页内容变脏时录一次,
 * 转换出的不可变 `SkImage` 就常驻在页的 `RenderNode` 里,跨帧直接命中同一份 GPU 纹理;绘制单条时
 * 用外扩到整像素的 `clipRect` 配合 2px 透明边距裁出槽位,在常规无父级缩放旋转的画布上 Ganesh 的
 * `CropToRect` 可直接把整页 quad 裁成槽位大小以利同页合批。
 *
 * 页按顺序写满,满了开新页;页数到上限后回收最久没画过的一页。弹幕几秒就穿过屏幕,页基本是
 * 先写先弃,整页回收足够,不需要逐块的空闲表。页的内容一改,平台就得把整页重新上传一次,所以
 * 写入要攒着做(预热按批进来),页也不能太大。
 */
internal class DanmakuSpriteAtlas(
    private val graphicsContext: GraphicsContext,
    private val density: Density,
    private val layoutDirection: LayoutDirection,
    opacity: Float,
) {
    class Page(val image: ImageBitmap, val layer: GraphicsLayer, val pooled: Boolean) {
        val canvas = Canvas(image)
        var generation = 0
        var cursorX = GUTTER_PX
        var cursorY = GUTTER_PX
        var rowHeight = 0
        var lastUsedFrame = 0L
        var dirty = false
        var released = false
    }

    /** 页上的一块。页被回收后 [valid] 变假,持有它的一方要重新申请、重画。 */
    class Slot(val page: Page, private val generation: Int, val x: Int, val y: Int, val width: Int, val height: Int) {
        val valid: Boolean get() = !page.released && page.generation == generation
    }

    private val pages = ArrayList<Page>()
    private val dirtyPages = ArrayList<Page>()
    private val drawScope = CanvasDrawScope()
    private val clearPaint = Paint().apply { blendMode = BlendMode.Clear }
    private val spritePaint = Paint().apply {
        alpha = opacity
        // 横向走亚像素浮点坐标消除高刷屏上的 1px 阶梯跳动,用双线性过滤;纵向在 draw 里对齐整像素,
        // 水平笔画不会发虚。每个槽位四周留有 GUTTER_PX 透明像素,插值与整像素外扩裁剪不会串到邻槽。
        filterQuality = FilterQuality.Low
    }

    fun allocate(width: Int, height: Int, frameId: Long): Slot {
        if (width > MAX_POOLED_SLOT_WIDTH || height > MAX_POOLED_SLOT_HEIGHT) {
            // 比一页可用区还大的(超长弹幕、大表情)单独一张,不进池子;缓存淘汰或释放时显式归还图层。
            val page = Page(
                image = ImageBitmap(width + GUTTER_PX * 2, height + GUTTER_PX * 2),
                layer = graphicsContext.createGraphicsLayer(),
                pooled = false,
            )
            page.lastUsedFrame = frameId
            return Slot(page, page.generation, GUTTER_PX, GUTTER_PX, width, height)
        }
        pages.lastOrNull()?.let { current ->
            place(current, width, height)?.let { slot ->
                current.lastUsedFrame = frameId
                return slot
            }
        }
        val page = nextPage(frameId)
        page.lastUsedFrame = frameId
        return place(page, width, height) ?: error("空页放不下 ${width}x$height")
    }

    fun touch(slot: Slot, frameId: Long) {
        slot.page.lastUsedFrame = frameId
    }

    /** 释放非池化超宽/超高页持有的 [GraphicsLayer];池化页随图集复用,统一在 [release] 归还。 */
    fun releaseUnpooled(slot: Slot) {
        val page = slot.page
        if (page.pooled || page.released) return
        page.released = true
        page.dirty = false
        graphicsContext.releaseGraphicsLayer(page.layer)
    }

    /** 清掉这一块(含四周透明隔离带)再画。[block] 的坐标原点在块的左上角。 */
    fun render(slot: Slot, block: DrawScope.() -> Unit) {
        val page = slot.page
        val canvas = page.canvas
        canvas.drawRect(
            (slot.x - GUTTER_PX).toFloat(),
            (slot.y - GUTTER_PX).toFloat(),
            (slot.x + slot.width + GUTTER_PX).toFloat(),
            (slot.y + slot.height + GUTTER_PX).toFloat(),
            clearPaint,
        )
        canvas.save()
        var failure: Throwable? = null
        try {
            canvas.clipRect(
                slot.x.toFloat(),
                slot.y.toFloat(),
                (slot.x + slot.width).toFloat(),
                (slot.y + slot.height).toFloat(),
            )
            // CanvasDrawScope.draw 内部的 canvas.save()/restore() 与 drawParams 恢复没有包在 finally 里;
            // 在闭包内接住异常让 draw 先正常弹栈收尾,出来后再重新抛出,避免异常污染复用页的画布裁剪栈。
            drawScope.draw(density, layoutDirection, canvas, Size(page.image.width.toFloat(), page.image.height.toFloat())) {
                try {
                    translate(slot.x.toFloat(), slot.y.toFloat()) { block() }
                } catch (t: Throwable) {
                    failure = t
                }
            }
        } finally {
            canvas.restore()
        }
        failure?.let { throw it }
        markDirty(page)
    }

    /** 把本帧改写过的页统一录制进各自的 [GraphicsLayer],保证开始贴图前所有页图层已是最新快照。 */
    fun syncDirtyPages() {
        if (dirtyPages.isEmpty()) return
        for (page in dirtyPages) {
            if (!page.released && page.dirty) {
                recordPage(page)
            }
        }
        dirtyPages.clear()
    }

    /**
     * 从页级 [GraphicsLayer] 裁出槽位贴到 `(x, y)`。
     *
     * 横坐标保留小数以支持亚像素平滑滚动,纵坐标对齐整像素保住汉字横画锐度;裁剪框向外取整到
     * 整像素边界,避免 Skiko `clipRect` 固定开启的 `antiAlias = true` 退化成抗锯齿遮罩裁剪。
     * 外扩出的不足 1px 区域落在槽位四周的 [GUTTER_PX] 透明隔离带内,不会露出邻槽内容。
     */
    fun draw(scope: DrawScope, slot: Slot, x: Float, y: Float) {
        val page = slot.page
        if (page.released) return
        if (page.dirty) recordPage(page)
        val dstY = y.roundToInt().toFloat()
        val clipLeft = floor(x)
        val clipRight = ceil(x + slot.width)
        val canvas = scope.drawContext.canvas
        canvas.save()
        try {
            canvas.clipRect(clipLeft, dstY, clipRight, dstY + slot.height)
            canvas.translate(x - slot.x, dstY - slot.y)
            scope.drawLayer(page.layer)
        } finally {
            canvas.restore()
        }
    }

    fun release() {
        dirtyPages.clear()
        for (page in pages) {
            if (!page.released) {
                page.released = true
                page.dirty = false
                graphicsContext.releaseGraphicsLayer(page.layer)
            }
        }
        pages.clear()
    }

    private fun markDirty(page: Page) {
        if (page.released || page.dirty) return
        page.dirty = true
        dirtyPages += page
    }

    private fun recordPage(page: Page) {
        val pageSize = IntSize(page.image.width, page.image.height)
        page.layer.record(density, layoutDirection, pageSize) {
            drawContext.canvas.drawImageRect(
                image = page.image,
                srcOffset = IntOffset.Zero,
                srcSize = pageSize,
                dstOffset = IntOffset.Zero,
                dstSize = pageSize,
                paint = spritePaint,
            )
        }
        page.dirty = false
    }

    private fun place(page: Page, width: Int, height: Int): Slot? {
        if (page.cursorX + width + GUTTER_PX > PAGE_WIDTH) {
            page.cursorY += page.rowHeight + GUTTER_PX
            page.cursorX = GUTTER_PX
            page.rowHeight = 0
        }
        if (page.cursorY + height + GUTTER_PX > PAGE_HEIGHT) return null
        val slot = Slot(page, page.generation, page.cursorX, page.cursorY, width, height)
        page.cursorX += width + GUTTER_PX
        if (height > page.rowHeight) page.rowHeight = height
        return slot
    }

    /** 新开一页,或回收一页本帧没画过、最久没用的。回收的页挪到末尾当作当前页。 */
    private fun nextPage(frameId: Long): Page {
        if (pages.size < MAX_PAGES) {
            return Page(
                image = ImageBitmap(PAGE_WIDTH, PAGE_HEIGHT),
                layer = graphicsContext.createGraphicsLayer(),
                pooled = true,
            ).also { pages += it }
        }
        val victim = pages.filter { it.lastUsedFrame < frameId }.minByOrNull { it.lastUsedFrame }
            // 每一页本帧都在用:同屏装不下,临时多开一页,宁可超限也不擦掉正在画的。
            ?: return Page(
                image = ImageBitmap(PAGE_WIDTH, PAGE_HEIGHT),
                layer = graphicsContext.createGraphicsLayer(),
                pooled = true,
            ).also { pages += it }
        pages.remove(victim)
        pages += victim
        victim.canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), clearPaint)
        victim.generation++
        victim.cursorX = GUTTER_PX
        victim.cursorY = GUTTER_PX
        victim.rowHeight = 0
        markDirty(victim)
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

        /** 槽位四周及槽位之间的透明像素宽度,供亚像素双线性采样与整像素外扩裁剪安全落脚。 */
        const val GUTTER_PX = 2

        const val MAX_POOLED_SLOT_WIDTH = PAGE_WIDTH - GUTTER_PX * 2
        const val MAX_POOLED_SLOT_HEIGHT = PAGE_HEIGHT - GUTTER_PX * 2

        /** 池子上限约 30MB。 */
        const val MAX_PAGES = 48
    }
}
