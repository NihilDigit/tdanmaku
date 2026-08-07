package dev.nihildigit.danmaku

/**
 * **滚动与顶部**弹幕可以落在画布的哪一块,归一化到 `[0, 1]`。
 *
 * 这是一个**布局区域**,不是"轨道数倍率"。上一版只拿 0.5 去除行高算轨道数,画布仍然整块参与
 * 渲染,既不裁剪也不偏移;调成 25% 或 75% 时"只占上面这么多"这句话根本不成立。改成区域之后
 * 顶部弹幕锚区域顶边往下堆,滚动弹幕铺在区域内,两者一起按这个矩形裁剪。
 *
 * **底部弹幕不受它约束,仍然锚画布底边往上堆。** 这不是漏掉的:把底部弹幕收进 `[0, 75%]`,
 * 它就成了"画面四分之三处的弹幕",不再是底部弹幕。用户调这个比例的意图是"别让滚动弹幕糊住
 * 整个画面",不是"把底部弹幕挪上来"。底部弹幕的纵向上限是它自己的
 * [DanmakuLayoutConfig.bottomTrackFraction],跟这里无关。
 *
 * 由此**底部弹幕会和字幕、播放控件抢画面底部那条带**。这是底部弹幕固有的,正解是给它们配
 * 避让区([insets] 就是那个扩展位:把字幕条和控件的占位折算成 inset 灌进来,不必动
 * [DanmakuLayoutConfig] 和调度器),不是把底部弹幕往上推。
 *
 * 档位(25/50/75/100%)是 app 侧的事,这里不做成枚举:枚举一旦写死,加一档就得改库。
 */
data class DanmakuViewport(
    /** 纵向区间上沿,0 是画布顶边。 */
    val topFraction: Float = 0f,
    /** 纵向区间下沿,1 是画布底边。 */
    val bottomFraction: Float = 0.75f,
    val insets: DanmakuInsets = DanmakuInsets.None,
) {
    companion object {
        /**
         * 档位设置的直接表达:从**顶边**起占 [heightFraction] 那么高,余量全部留在下面。
         *
         * 不做成上下各留一半的居中区间:留白的用途是给画面底部让出一条不被滚动弹幕糊住的带,
         * 顶部没有这个需求,居中只会白白牺牲上面的可用高度。
         */
        fun topAnchored(heightFraction: Float, insets: DanmakuInsets = DanmakuInsets.None): DanmakuViewport =
            DanmakuViewport(0f, heightFraction.coerceIn(0f, 1f), insets)
    }
}

/** 四周安全边距,同样按画布尺寸归一化。 */
data class DanmakuInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    companion object {
        val None = DanmakuInsets()
    }
}

/**
 * [DanmakuViewport] 落到具体画布尺寸上的像素矩形。调度器算的每一个横向量(穿屏距离、间距、
 * slack)都以 [width] 为基准,渲染也在这个矩形里裁剪——两边引用同一个对象,不会各算一份。
 */
data class DanmakuViewportPx(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

fun DanmakuViewport.resolve(canvasWidthPx: Float, canvasHeightPx: Float): DanmakuViewportPx {
    val left = canvasWidthPx * insets.left
    val right = canvasWidthPx * (1f - insets.right)
    val top = canvasHeightPx * (topFraction + insets.top)
    val bottom = canvasHeightPx * (bottomFraction - insets.bottom)
    // 边距把区域挤成负数时收敛成零面积,不返回 right < left 的矩形——负宽会让速度公式算出
    // 负速度,弹幕反着飞,而画面上看不出"是边距配错了"。
    return DanmakuViewportPx(
        left = left,
        top = top,
        right = maxOf(left, right),
        bottom = maxOf(top, bottom),
    )
}

/**
 * 一条弹幕的中立尺寸。调度器只认这两个 float,不认 `TextLayoutResult`、`TextStyle` 或任何
 * 平台排版对象——平台 layout 由渲染准备层持有,进不了这一层。
 */
data class DanmakuTextSize(val widthPx: Float, val heightPx: Float)

/**
 * 排布的全部输入。画布尺寸、显示区域、行高、穿屏时长、最小间距——调度器不知道什么是字体、
 * 什么是屏幕密度,这些量全部由调用方算好注入。
 *
 * @param canvasWidthPx 弹幕画布的像素宽,渲染时必须与 Canvas 实际宽度一致(见 [DanmakuHost])。
 * @param canvasHeightPx 同上,像素高。底部弹幕的纵向范围以它为准,不是以 [viewport] 为准。
 * @param viewport 滚动与顶部弹幕的显示区域。**不覆盖底部弹幕**,理由见 [DanmakuViewport]。
 * @param trackHeightPx 一条轨道占用的行高,滚动与固定弹幕共用。
 * @param scrollDurationMillis 统一穿屏时长 D:任何长度的滚动弹幕都在这个时间内走完
 *   "视口宽 + 自身虚拟宽",长弹幕因此更快。slack 判据整个建立在这个量统一之上,见
 *   [CollisionFreeScheduler]。
 * @param fixedDurationMillis 顶/底固定弹幕停留时长。
 * @param minGapPx 同轨相邻弹幕的最小水平间距 g。
 * @param jitterFraction 尾部虚拟留白的上限比例。0 时所有弹幕严格同步,画面像队列;这里给的
 *   留白是**确定性**的(`hash(id)`),不是随机速度扰动——扰动会让轨道状态无法只用
 *   `(emitTime, speed)` 两个数表达,见 [CollisionFreeScheduler] 的说明。
 * @param bottomTrackFraction 底部弹幕最多占**画面**高度的比例。它需要自己的上限:底部弹幕不
 *   受 [viewport] 约束,没有这个数就能一路往上堆满整个画面。做成配置项而不是常量,是因为它
 *   将来要跟避让区(字幕条、播放控件)一起调整,那时它就是那个旋钮。
 */
data class DanmakuLayoutConfig(
    val canvasWidthPx: Float,
    val canvasHeightPx: Float,
    val trackHeightPx: Float,
    val viewport: DanmakuViewport = DanmakuViewport(),
    val scrollDurationMillis: Long = 8_000L,
    val fixedDurationMillis: Long = 4_000L,
    val minGapPx: Float = 12f,
    val jitterFraction: Float = 0.075f,
    val bottomTrackFraction: Float = 0.3f,
) {
    val viewportPx: DanmakuViewportPx = viewport.resolve(canvasWidthPx, canvasHeightPx)

    /**
     * 滚动轨道数,视口高度能放下几行就是几行。画布还没布局出来(尺寸为 0)时给 1 条占位,
     * 不除零——首帧过后立刻被真实值取代。
     */
    val scrollTrackCount: Int = trackCapacity(viewportPx.height)

    /**
     * 顶部轨道数。和滚动共用视口容量:顶部弹幕锚视口顶边往下堆,能堆到哪儿由视口说了算,
     * 不再另打折扣。
     *
     * 滚动与顶部彼此**不**互相占用,跨模式遮挡是已知的、这一版接受的行为(gap 分析 3.4
     * 「仍需定义的显示规则」第 1 条):真要互相避让得引入共享占用层,那是产品决定,不是这里
     * 顺手加的。
     */
    val topTrackCount: Int = scrollTrackCount

    /** 底部轨道数按**画面**高度算,不按视口——底部弹幕不在视口里。 */
    val bottomTrackCount: Int = trackCapacity(canvasHeightPx * bottomTrackFraction)

    fun trackCount(mode: DanmakuMode): Int = when (mode) {
        DanmakuMode.SCROLL -> scrollTrackCount
        DanmakuMode.TOP -> topTrackCount
        DanmakuMode.BOTTOM -> bottomTrackCount
    }

    private fun trackCapacity(availableHeightPx: Float): Int =
        if (trackHeightPx > 0f) (availableHeightPx / trackHeightPx).toInt().coerceAtLeast(1) else 1
}
