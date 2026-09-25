package dev.nihildigit.danmaku

/**
 * 弹幕的渲染位置。这一层只认三种,不认服务端的原始模式号(B 站 1/2/3/4/5/6/7/8/9 那一套) ——
 * 号到位置的映射是数据源相关的适配层逻辑,不属于这个中立模型。参照 B 站的映射规则:
 * 1/2/3 -> SCROLL,4 -> BOTTOM,5 -> TOP,6(逆向) 退化成 SCROLL。
 *
 * 7(定位/运动)不在这里,它是 [SpecialDanmaku],走独立的一层:那类弹幕的位置由作者写死,
 * 不选轨、不判碰撞、不受显示区域约束,跟这三种没有一个共同点。8/9(代码/BAS)不支持。
 */
enum class DanmakuMode {
    SCROLL,
    TOP,
    BOTTOM,
}

/**
 * 一条弹幕的中立数据模型,不携带任何数据源或渲染细节。
 *
 * @param id 列表 key、去重,同时是 [DanmakuTimeline] 里所有确定性随机(速度扰动、选轨)的
 *   唯一输入 —— 同一条弹幕(同一个 id)在任意平台、任意两次编译之间必须选出同样的轨道和速度。
 * @param playTimeMillis 弹幕出现的播放进度,单位毫秒。
 * @param color 24 位 RGB。
 * @param fontSize 可空,null 表示跟随调用方的全局字号配置。这一层保留该字段本身就是产品决策:
 *   B 站协议里这个字段一直都在,保留它意味着轨道高度、描边宽度这类布局参数将来能从常量变成
 *   逐条弹幕可变的量,不保留就再也拿不回来了。
 * @param isSelf 是否为本端发出的弹幕,渲染层用于高亮与兜底放置。当前没有任何数据源会产出
 *   `true`(发弹幕功能未实现),但字段本身要留着,不能等到发弹幕功能落地才补。
 * @param images 正文里画成图的那几段,见 [DanmakuImage]。
 */
data class Danmaku(
    val id: String,
    val playTimeMillis: Long,
    val mode: DanmakuMode,
    val color: Int,
    val text: String,
    val fontSize: Int? = null,
    val isSelf: Boolean = false,
    val images: List<DanmakuImage> = emptyList(),
)

/**
 * 正文 `[start, end)` 这一段画成一张图,文字本身不画。整条弹幕就是一张图时,范围盖住全文。
 *
 * 图从哪来由调用方决定,这里只有一个 [key],渲染时交给 `DanmakuImageSource` 去取。图还没到时
 * 按尺寸留出空位:宽度在编排时就定下来了,不能因为图晚到而变。
 *
 * 尺寸以字号为单位,不用像素:图要跟着文字一起缩放。高度超过行高比例
 * ([DanmakuOptions.lineHeightRatio])会压到相邻轨道上。
 *
 * 一条弹幕里的图要按起点排好、互不重叠、不越界,否则整条只画文字。
 */
data class DanmakuImage(
    val start: Int,
    val end: Int,
    val key: String,
    val widthEm: Float,
    val heightEm: Float,
)

/** [Danmaku.images] 能不能原样当排版占位用。不能就整条退回纯文字,不去猜调用方的意图。 */
internal fun Danmaku.validImages(): List<DanmakuImage> {
    var previousEnd = 0
    for (image in images) {
        if (image.start < previousEnd || image.end <= image.start || image.end > text.length) return emptyList()
        if (image.widthEm <= 0f || image.heightEm <= 0f) return emptyList()
        previousEnd = image.end
    }
    return images
}
