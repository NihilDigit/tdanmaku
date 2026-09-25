package dev.nihildigit.danmaku

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * 窗口化编排的性质。整池编排([DanmakuCompiler.compileAll])在这里当参照物用——它是唯一
 * 一个"结果只取决于弹幕池"的编排方式,窗口化能保住多少,就靠跟它逐条比出来。
 *
 * 这组测试刻意分成"能保住的"和"保不住的"两类,不含混:
 * - 顺播增量、同落点重进、窗口外零测量——断言逐条相等;
 * - seek 之后的轨道分配——只断言仍然无碰撞、仍然不多丢,**不**断言与整池相同,因为它确实
 *   不同(见 [DanmakuCompiler] 类文档里那段实测)。把它写成相等的断言只会得到一个红的、
 *   然后被人放宽掉的测试。
 */
class DanmakuWindowTest {

    private val charWidthPx = 10f

    private fun size(text: String) = DanmakuTextSize(text.length * charWidthPx, 20f)

    /** D = 1000ms,视口高 100 / 行高 20 = 5 条滚动轨道。 */
    private fun layout() = DanmakuLayoutConfig(
        canvasWidthPx = 400f,
        canvasHeightPx = 200f,
        trackHeightPx = 20f,
        viewport = DanmakuViewport.topAnchored(0.5f),
        scrollDurationMillis = 1_000L,
        fixedDurationMillis = 1_000L,
        minGapPx = 12f,
    )

    private fun newCompiler(
        cfg: DanmakuLayoutConfig,
        density: DanmakuDensity,
        onMeasure: (Danmaku) -> Unit = {},
    ) = DanmakuCompiler(cfg, density.createScheduler(cfg)) { danmaku ->
        onMeasure(danmaku)
        size(danmaku.text)
    }

    // ---- 能保住的三条 ----

    /**
     * 窗口起点之前超过 D 毫秒的历史对结果没有贡献——这条推论的可执行形式。
     *
     * 池子做成一簇一簇、簇间间隔大于 D:这样每一簇开始时所有轨道的 `remaining` 都已归零,
     * 整池编排在簇边界上的状态与"空轨道"不可区分,窗口从簇边界之前起步就必然逐条对上。
     * 反过来,把弹幕铺成连续密集流,这个断言就会失败——那不是实现有 bug,是窗口化本身只
     * 保这么多,[DanmakuCompiler] 类文档写了原因。
     */
    @Test
    fun `簇间隔大于 D 时 窗口编排与整池编排逐条相同`() {
        for (density in DanmakuDensity.entries) {
            val cfg = layout()
            val pool = burstyPool(bursts = 30, burstIntervalMillis = 3_000L)

            val whole = newCompiler(cfg, density)
            whole.setPool(pool)
            whole.compileAll()

            for (x in listOf(9_000L, 15_000L, 21_000L)) {
                val windowed = newCompiler(cfg, density)
                windowed.setPool(pool)
                windowed.advanceTo(x)

                val expected = whole.timeline.plans().filter { it.emitTimeMillis >= x }
                val actual = windowed.timeline.plans().filter { it.emitTimeMillis >= x }
                // 只比窗口盖到的那一段:窗口尾部之后整池还有条目,那不是不一致。
                val until = actual.last().emitTimeMillis
                assertEquals(
                    expected.filter { it.emitTimeMillis <= until },
                    actual,
                    "$density 档 seek 到 $x 之后的排布与整池不一致",
                )
            }
        }
    }

    /**
     * 顺播路径必须与整池编排**完全**相同。窗口只向前扩展、调度器状态连续,中间没有任何一次
     * 重建,所以这里不存在"窗口化近似"的余地——不相等就是实现漏了状态,不是性质的边界。
     */
    @Test
    fun `顺播增量推进与整池编排逐条相同`() {
        for (density in DanmakuDensity.entries) {
            val cfg = layout()
            val pool = randomPool(600, spanMillis = 60_000L, seed = 11)

            val whole = newCompiler(cfg, density)
            whole.setPool(pool)
            whole.compileAll()

            val stepped = newCompiler(cfg, density)
            stepped.setPool(pool)
            var t = 0L
            while (t <= 60_000L) {
                stepped.advanceTo(t)
                t += 1_000L
            }

            assertEquals(whole.timeline.plans(), stepped.timeline.plans(), "$density 档顺播结果与整池不一致")
        }
    }

    /**
     * 两次 seek 落到同一处,画面必须一样。播放器给的落点常常差几十毫秒(落到不同关键帧),
     * 窗口起点取整就是为了吃掉这点抖动。
     */
    @Test
    fun `落点相差几十毫秒时窗口结果相同`() {
        for (density in DanmakuDensity.entries) {
            val cfg = layout()
            val pool = randomPool(1_200, spanMillis = 120_000L, seed = 13)

            val first = newCompiler(cfg, density)
            first.setPool(pool)
            first.advanceTo(60_000L)

            val second = newCompiler(cfg, density)
            second.setPool(pool)
            second.advanceTo(60_037L)

            // 只比两个窗口都必然盖到的那一段:尾部边界差 37ms,可能多带一条,那不算不一致。
            // 窗口只往落点之后预留几秒,比较段不能超过它。
            val cut = 62_000L
            assertEquals(
                first.timeline.plans().filter { it.emitTimeMillis <= cut },
                second.timeline.plans().filter { it.emitTimeMillis <= cut },
                "$density 档同一落点两次进入排布不同,画面会跳",
            )
        }
    }

    /**
     * 这次改动的全部意义:窗口外的弹幕一次都不该被测量。测量占整池编排 94% 的时间,只要还有
     * 一条窗口外的弹幕被测,省下来的就不是这个量级。
     */
    @Test
    fun `窗口外的弹幕一次都不被测量`() {
        val cfg = layout()
        val pool = randomPool(5_000, spanMillis = 20 * 60_000L, seed = 17)
        val measured = mutableListOf<Danmaku>()

        val compiler = newCompiler(cfg, DanmakuDensity.STANDARD) { measured += it }
        compiler.setPool(pool)
        compiler.advanceTo(600_000L)

        assertTrue(measured.isNotEmpty(), "窗口内一条都没测,这个断言就没在验东西")
        // 预热起点 = 落点 - D 再向下取整,窗口尾 = 落点 + 预留量;两侧各放宽一格,验的是
        // "只测了落点附近的一小段",不是复刻取整公式。
        val slack = 10_000L
        val outside = measured.filter { it.playTimeMillis < 600_000L - slack || it.playTimeMillis > 600_000L + 30_000L + slack }
        assertEquals(emptyList<Danmaku>(), outside, "窗口外被测量了 ${outside.size} 条")
        assertTrue(measured.size < pool.size / 10, "测量量没降下来:${measured.size} / ${pool.size}")
    }

    /**
     * 免测量丢弃只能省测量,不能改结果。池子要密到大半被丢,否则这条路径根本没走到。改速度
     * 模型破坏了"越宽越快"的单调性时,这里会先红。
     */
    @Test
    fun `免测量丢弃与逐条测量的排布相同`() {
        val cfg = layout()
        val pool = randomPool(6_000, spanMillis = 60_000L, seed = 23)

        var measuredWithSkip = 0
        val withSkip = DanmakuCompiler(cfg, CollisionFreeScheduler(cfg)) {
            measuredWithSkip++
            size(it.text)
        }
        withSkip.setPool(pool)
        withSkip.compileAll()

        val alwaysMeasure = object : DanmakuScheduler by CollisionFreeScheduler(cfg) {
            override fun rejectsRegardlessOfSize(danmaku: Danmaku) = false
        }
        val reference = DanmakuCompiler(cfg, alwaysMeasure) { size(it.text) }
        reference.setPool(pool)
        reference.compileAll()

        assertEquals(reference.timeline.plans(), withSkip.timeline.plans())
        assertEquals(reference.report.droppedByLayoutCount, withSkip.report.droppedByLayoutCount)
        assertTrue(
            measuredWithSkip < pool.size / 2,
            "丢弃了 ${withSkip.report.droppedByLayoutCount} 条,却测了 $measuredWithSkip / ${pool.size} 条",
        )
    }

    // ---- 保不住、但必须仍然成立的两条 ----

    /**
     * seek 之后的排布与整池不同(见类文档),但仍然必须是一份合法排布:同轨相邻弹幕任意时刻
     * 不重叠。整池编排的这条性质由 `DanmakuSchedulerTest` 验,这里验的是"从池子中间起步之后
     * 它仍然成立"——空轨道起步不会让判据失效。
     */
    @Test
    fun `窗口内排布仍然无碰撞`() {
        val cfg = layout()
        val pool = randomPool(2_000, spanMillis = 120_000L, seed = 19)
        val compiler = newCompiler(cfg, DanmakuDensity.STANDARD)
        compiler.setPool(pool)
        compiler.advanceTo(60_000L)

        val scroll = compiler.timeline.plans().filter { it.mode == DanmakuMode.SCROLL }
        scroll.groupBy { it.track }.values.forEach { onTrack ->
            val sorted = onTrack.sortedBy { it.emitTimeMillis }
            for (i in 0 until sorted.size - 1) {
                val gap = minGapAfter(cfg, sorted[i], sorted[i + 1])
                assertTrue(gap >= cfg.minGapPx - GAP_EPSILON, "轨道 ${sorted[i].track} 上间距 $gap 小于 g")
            }
        }
    }

    /**
     * 窗口化换的是"哪条弹幕落在哪条轨道",不该换成"哪些弹幕上得了屏"。丢弃率明显变差意味着
     * 空轨道起步把某一段挤没了,那是要查的;逐条相等则不作要求。
     */
    @Test
    fun `窗口编排不比整池编排多丢弹幕`() {
        val cfg = layout()
        val pool = randomPool(3_000, spanMillis = 120_000L, seed = 23)
        val x = 60_000L

        val whole = newCompiler(cfg, DanmakuDensity.STANDARD)
        whole.setPool(pool)
        whole.compileAll()

        val windowed = newCompiler(cfg, DanmakuDensity.STANDARD)
        windowed.setPool(pool)
        windowed.advanceTo(x)

        val until = windowed.timeline.plans().last().emitTimeMillis
        val wholeInRange = whole.timeline.plans().count { it.emitTimeMillis in x..until }
        val windowedInRange = windowed.timeline.plans().count { it.emitTimeMillis >= x }
        // 留 2% 的出入:起点那一瞬间的分歧会把边界上的个别条目挪到丢弃的另一侧,合成池上实测
        // 是零到个位数。这里要抓的是"空轨道起步把某一段整体挤没了",不是逐条对账。
        assertTrue(
            windowedInRange >= wholeInRange - wholeInRange / 50 - 1,
            "窗口内上屏 $windowedInRange 条,整池同区间 $wholeInRange 条,差得太多",
        )
    }

    // ---- 增量追加与裁剪 ----

    /**
     * 逐条 [DanmakuCompiler.append] 必须与整池 [DanmakuCompiler.setPool] 排出逐条相同的时间轴。
     *
     * 要抓的是同模式序号:append 不重扫池子,靠自己的计数器接着往下数,接错一位会让轮询档之后
     * 所有同模式弹幕的轨道整体错开——而那在画面上只表现为"弹幕挤到一块了",看不出是序号的事。
     */
    @Test
    fun `逐条追加与整池设置排出相同时间轴`() {
        for (density in DanmakuDensity.entries) {
            val cfg = layout()
            val pool = randomPool(600, spanMillis = 60_000L, seed = 29)
                .sortedWith(compareBy({ it.playTimeMillis }, { it.id }))

            val whole = newCompiler(cfg, density)
            whole.setPool(pool)
            whole.compileAll()

            val appended = newCompiler(cfg, density)
            pool.forEach { assertTrue(appended.append(it), "有序输入被拒绝:${it.id}") }
            appended.compileAll()

            assertEquals(
                whole.timeline.plans(),
                appended.timeline.plans(),
                "$density 档逐条追加与整池设置结果不同",
            )
        }
    }

    /**
     * **快照必须足以重建。** `DanmakuController` 在画布尺寸变化时丢掉旧编排器、拿
     * [DanmakuCompiler.danmaku] 建一个新的,所以这份快照要如实反映 [DanmakuCompiler.append] 和
     * [DanmakuCompiler.trimBefore] 做过的事。
     *
     * 它曾经不成立:controller 自己另存了一份池子,而那份只被 setPool 更新。转屏一次,直播追加
     * 进来的整屏弹幕消失、点播裁掉的旧弹幕复活 —— 两处都不会报错,只是画面不对。
     */
    @Test
    fun `快照反映追加与裁剪 据它重建得到同一个池子`() {
        val cfg = layout()
        val origin = newCompiler(cfg, DanmakuDensity.STANDARD)
        origin.setPool(randomPool(500, spanMillis = 60_000L, seed = 37))
        origin.compileAll()

        val tail = origin.danmaku.last().playTimeMillis
        repeat(20) { i ->
            val appended = Danmaku("live$i", tail + 1_000L + i, DanmakuMode.SCROLL, 0xFFFFFF, "追加$i")
            assertTrue(origin.append(appended), "有序追加被拒绝")
        }
        origin.compileAll()
        assertTrue(origin.danmaku.any { it.id == "live0" }, "追加的弹幕没有进入快照")

        val beforeTrim = origin.danmaku.size
        origin.trimBefore(40_000L)
        assertTrue(origin.danmaku.size < beforeTrim, "一条都没裁掉,这个断言就没在验东西")

        // 拿快照重建一个,池子必须逐条相同 —— 追加的还在,裁掉的没回来。
        val rebuilt = newCompiler(cfg, DanmakuDensity.STANDARD)
        rebuilt.setPool(origin.danmaku)
        assertEquals(origin.danmaku, rebuilt.danmaku)
    }

    /** 时间早于池尾的追加必须被拒绝,而不是插进去把后面的序号整体顶歪。 */
    @Test
    fun `乱序追加被拒绝`() {
        val compiler = newCompiler(layout(), DanmakuDensity.STANDARD)
        assertTrue(compiler.append(Danmaku("a", 1_000L, DanmakuMode.SCROLL, 0xFFFFFF, "弹")))
        assertTrue(compiler.append(Danmaku("b", 1_000L, DanmakuMode.SCROLL, 0xFFFFFF, "弹")))
        assertFalse(compiler.append(Danmaku("c", 999L, DanmakuMode.SCROLL, 0xFFFFFF, "弹")))
    }

    /** 裁剪只能丢掉再也查不到的东西:裁剪点之后每个采样时刻的可见集合必须一字不差。 */
    @Test
    fun `裁剪不改变裁剪点之后的可见内容`() {
        val cfg = layout()
        val compiler = newCompiler(cfg, DanmakuDensity.STANDARD)
        compiler.setPool(randomPool(2_000, spanMillis = 120_000L, seed = 31))
        compiler.compileAll()

        val cut = 60_000L
        val sampleAt = (cut..120_000L step 2_500L).toList()
        val before = sampleAt.map { visibleAt(compiler, it) }
        val sizeBefore = compiler.timeline.plans().size

        val trimmed = compiler.trimBefore(cut)

        assertTrue(trimmed > 0, "一条都没裁掉,这个断言就没在验东西")
        assertTrue(compiler.timeline.plans().size < sizeBefore, "时间轴没有变短")
        assertEquals(before, sampleAt.map { visibleAt(compiler, it) }, "裁剪改变了裁剪点之后的画面")
    }

    private fun visibleAt(compiler: DanmakuCompiler, positionMillis: Long): List<DanmakuFlightPlan> {
        val visible = mutableListOf<DanmakuFlightPlan>()
        compiler.timeline.visibleAt(positionMillis) { visible += it }
        return visible
    }

    // ---- 工具 ----

    /** 一簇一簇的池子,簇间间隔远大于 D:每簇开始时所有轨道都已空出来。 */
    private fun burstyPool(bursts: Int, burstIntervalMillis: Long): List<Danmaku> =
        (0 until bursts).flatMap { burst ->
            val t = burst * burstIntervalMillis
            (0 until 6).map { i ->
                Danmaku("b$burst-$i", t, DanmakuMode.SCROLL, 0xFFFFFF, "弹".repeat(i + 1))
            } + Danmaku("b$burst-top", t, DanmakuMode.TOP, 0xFFFFFF, "顶") +
                Danmaku("b$burst-bottom", t, DanmakuMode.BOTTOM, 0xFFFFFF, "底")
        }

    private fun randomPool(count: Int, spanMillis: Long, seed: Int): List<Danmaku> {
        val random = Random(seed)
        val modes = DanmakuMode.entries
        return (0 until count).map { i ->
            Danmaku(
                id = "d$i",
                playTimeMillis = random.nextLong(0, spanMillis),
                mode = modes[random.nextInt(modes.size)],
                color = 0xFFFFFF,
                text = "弹".repeat(random.nextInt(1, 12)),
            )
        }
    }

    /** 与 `DanmakuSchedulerTest` 同一套几何:虚拟宽度从 `speed × D - W` 反推,不重写速度公式。 */
    private fun minGapAfter(
        cfg: DanmakuLayoutConfig,
        front: DanmakuFlightPlan,
        back: DanmakuFlightPlan,
    ): Float {
        val right = cfg.viewportPx.right
        val frontVirtualWidth = front.speedPxPerMillis * cfg.scrollDurationMillis - cfg.viewportPx.width
        var min = Float.MAX_VALUE
        var t = back.emitTimeMillis
        val until = front.emitTimeMillis + cfg.scrollDurationMillis
        while (t <= until) {
            val frontHead = right - (t - front.emitTimeMillis) * front.speedPxPerMillis
            val backHead = right - (t - back.emitTimeMillis) * back.speedPxPerMillis
            min = minOf(min, backHead - (frontHead + frontVirtualWidth))
            t++
        }
        return min
    }

    private companion object {
        const val GAP_EPSILON = 0.01f
    }
}
