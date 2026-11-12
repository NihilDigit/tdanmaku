package dev.nihildigit.danmaku

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.sp
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.Test

/**
 * 缓存 key 的正确性。这是分级绘制后端里唯一在单测里抓得住的东西:逐帧绘制要真实 Canvas 和
 * GPU,单测里测不了,不硬写。
 *
 * 两个方向都要盯:key **多**了不影响排版的字段(颜色),命中率会随颜色数成倍下降,画面正确
 * 但性能白丢;key **少**了影响排版的字段(字号、字体族、字重、locale),会拿别的排版去画,
 * 表现是宽度错、位置错,而排布期算出来的 widthPx 还是对的 —— 那种错很难从画面反推。
 */
class DanmakuRenderKeyTest {

    private fun danmaku(text: String, color: Int = 0xFFFFFF, fontSize: Int? = null) =
        Danmaku(id = "$text-$color-$fontSize", playTimeMillis = 0L, mode = DanmakuMode.SCROLL, color = color, text = text, fontSize = fontSize)

    @Test
    fun `same text different color shares one layout`() {
        val style = DanmakuRenderStyle(globalFontSizeSp = 18f)
        val white = layoutKeyOf(danmaku("哈哈哈", color = 0xFFFFFF), style)
        val red = layoutKeyOf(danmaku("哈哈哈", color = 0xFF0000), style)
        assertEquals(white, red)
    }

    @Test
    fun `same text different color needs separate display lists`() {
        val style = DanmakuRenderStyle(globalFontSizeSp = 18f)
        val white = layerKeyOf(danmaku("哈哈哈", color = 0xFFFFFF), style)
        val red = layerKeyOf(danmaku("哈哈哈", color = 0xFF0000), style)
        assertNotEquals(white, red)
    }

    @Test
    fun `same text different font size are two layouts`() {
        val style = DanmakuRenderStyle(globalFontSizeSp = 18f)
        val base = layoutKeyOf(danmaku("哈哈哈"), style)
        val larger = layoutKeyOf(danmaku("哈哈哈", fontSize = 25), style)
        assertNotEquals(base, larger)
    }

    @Test
    fun `per danmaku font size wins over the global one`() {
        val style18 = DanmakuRenderStyle(globalFontSizeSp = 18f)
        val style25 = DanmakuRenderStyle(globalFontSizeSp = 25f)
        // 两条弹幕自带同一个字号,全局字号不同 —— 排版相同,必须是同一个 key。
        assertEquals(
            layoutKeyOf(danmaku("哈哈哈", fontSize = 20), style18),
            layoutKeyOf(danmaku("哈哈哈", fontSize = 20), style25),
        )
    }

    @Test
    fun `layout affecting style attributes are all in the key`() {
        val base = DanmakuRenderStyle(baseTextStyle = TextStyle(fontSize = 18.sp), globalFontSizeSp = null)
        val d = danmaku("哈哈哈")
        val baseKey = layoutKeyOf(d, base)

        val family = base.copy(baseTextStyle = base.baseTextStyle.copy(fontFamily = FontFamily.Monospace))
        val weight = base.copy(baseTextStyle = base.baseTextStyle.copy(fontWeight = FontWeight.Bold))
        val locale = base.copy(baseTextStyle = base.baseTextStyle.copy(localeList = LocaleList("ja-JP")))

        assertNotEquals(baseKey, layoutKeyOf(d, family))
        assertNotEquals(baseKey, layoutKeyOf(d, weight))
        assertNotEquals(baseKey, layoutKeyOf(d, locale))
    }

    @Test
    fun `reusable probe key matches snapshot and stays isolated after mutation`() {
        val styleWithGlobal = DanmakuRenderStyle(globalFontSizeSp = 18f)
        val styleWithoutGlobal = DanmakuRenderStyle(globalFontSizeSp = null)
        val first = danmaku("前方高能", color = 0x66CCFF)
        val second = danmaku("完结撒花", color = 0xFF0000, fontSize = 25)

        val probe = DanmakuLayerKey().set(first, styleWithGlobal)
        val snapshot = probe.snapshot()
        assertEquals(snapshot, probe)
        assertEquals(snapshot.hashCode(), probe.hashCode())
        assertEquals(18f, snapshot.fontSizeSp)

        // 复用探测实例去查下一条弹幕时,已入表的快照绝不能跟着变。
        probe.set(second, styleWithoutGlobal)
        assertNotEquals(snapshot, probe)
        assertEquals("前方高能", snapshot.text)
        assertEquals(18f, snapshot.fontSizeSp)
        assertEquals(25f, probe.fontSizeSp)

        // 未指定字号(null)与任意显式字号(含 0f / -0f / NaN)都必须可区分且哈希自洽。
        val unset = layerKeyOf(first, styleWithoutGlobal)
        assertNull(unset.fontSizeSp)
        assertNotEquals(unset, layerKeyOf(first, DanmakuRenderStyle(globalFontSizeSp = 0f)))
        assertNotEquals(unset, layerKeyOf(first, DanmakuRenderStyle(globalFontSizeSp = Float.NaN)))
    }

    @Test
    fun `lru cache map touches hit entries and evicts eldest without losing links`() {
        val cache = LruCacheMap<DanmakuLayerKey, String>(initialCapacity = 4)
        val style = DanmakuRenderStyle(globalFontSizeSp = 18f)
        val k1 = layerKeyOf(danmaku("一"), style)
        val k2 = layerKeyOf(danmaku("二"), style)
        val k3 = layerKeyOf(danmaku("三"), style)
        val probe = DanmakuLayerKey()

        cache[k1] = "1"
        cache[k2] = "2"
        cache[k3] = "3"
        assertEquals(3, cache.size)
        assertEquals("1", cache.head?.value)
        assertEquals("3", cache.tail?.value)

        // 纯查询 get 不改变顺序;getAndTouch 把 k1 挪到表尾,使 k2 成为最旧项。
        assertEquals("1", cache[probe.set(danmaku("一"), style)])
        assertEquals("1", cache.head?.value)
        assertEquals("1", cache.getAndTouch(probe.set(danmaku("一"), style)))
        assertEquals("2", cache.head?.value)
        assertEquals("1", cache.tail?.value)

        // 更新已有 key 的值同样刷新到表尾。
        cache[k2] = "2-updated"
        assertEquals("3", cache.head?.value)
        assertEquals("2-updated", cache.tail?.value)

        // 依次淘汰表头、表尾与最后一项,双向链表指针始终闭合。
        cache.removeNode(cache.head!!)
        assertEquals(2, cache.size)
        assertNull(cache[k3])
        assertEquals("1", cache.head?.value)

        cache.removeNode(cache.tail!!)
        assertEquals(1, cache.size)
        assertEquals("1", cache.head?.value)
        assertEquals("1", cache.tail?.value)

        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.head)
        assertNull(cache.tail)
    }
}
