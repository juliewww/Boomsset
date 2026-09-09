package com.boomsset.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.boomsset.domain.AssetClass

/**
 * 图表配色。**不是手挑的。**
 *
 * 大类颜色是「分类色」（编码身份，不编码大小），偏离度是「分歧色」（编码正负两侧）——
 * 两种职责的规则不同，混用会让读者把"哪一类"和"偏多还是偏少"看串。
 *
 * ## 大类：固定顺序的五个色相
 *
 * ⚠️ **「保障类」曾经是紫 `#585CA2`，现在是金黄 `#977E00`。**
 * 原因不是配色本身有问题，是**品牌色要用紫**（紫气东来），而紫和这五个色必须
 * ΔE ≥ 15 —— 保障类占着紫，品牌紫就没地方站。
 * 挪的方向是算出来的：先试过挪到洋红（H340），**那是错的** —— 洋红反而把
 * H300~330 的紫堵住，品牌紫被迫要 ≥0.19 的彩度（太艳）。保障类必须挪到
 * **离紫最远的一侧**（暖色/绿色），品牌紫的彩度下限才从 0.135 掉到 0.060，
 * 也就是才做得出低彩度的高级紫。
 *
 * ⚠️ 这个改动**会重建用户认知**：真机上"紫色 = 保障类"已经跑过一段时间。
 *
 * 顺序本身就是**色盲安全机制**，不是审美选择：候选顺序要逐一验证，只在通过的那些里挑。
 * 所以 [assetClassColors] 的顺序**不要重排**，也不要给第六个大类"顺手生成"一个颜色。
 *
 * ## 色相取自有知有行，但**必须 snap 过**
 *
 * 前四个色相照抄有知有行的 design token（blue `#4287CE` / green `#2EB88A` /
 * orange `#E5881E` / cyan `#66B5CC`），但**不能直接用**：
 * 它们是给小面积强调和文字用的，作为五路分类填色时 gold 和 pink 的亮度超出区间、
 * cyan 和 purple 的彩度低于下限（会读成灰）。
 *
 * 所以按 snap-to-passing 处理：**色相角不动**，只挪亮度和彩度到合规。
 * 在 2520 种组合里搜出 588 组通过，取**离原色最近**的那组 —— 五个色总偏离
 * 仅 ΔE 5.8，其中 green 一个像素都没改。gold 和 pink 被自动排除（它们的 snap 代价最大，
 * 各 ΔE 8.7 / 5.7）。
 *
 * 实测（OKLab ΔE ×100，protan/deuteran 模拟，门槛 8 / 15）：
 * - 浅色底 `#FFFFFF`（最不利的情况，实际卡片是 `#FAFAFA`）：最差相邻对 11.5，正常视力 20.4
 * - 深色底 `#1C1C1C`：最差相邻对 10.8，正常视力 16.6，**色块对比度全部 ≥ 3:1**
 *
 * ⚠️ **浅色模式下 green 和 orange 低于 3:1 的色块对比度。**
 * 这条不可豁免，必须有补偿通道 —— 我们的每一行都同时显示**大类名和百分比**，
 * 而且条形的**长度**本身就可读，不依赖颜色。所以补偿是结构性的：
 * **改版式时不要把那些标签去掉。**
 *
 * ## 偏离度：红 ↔ 蓝 + 中性灰
 *
 * 超配用红是产品要求。低配用蓝而不是绿：**绿色在中文理财语境里读作"跌"**，
 * 用在"低配"上会被理解成亏损。中间态（已达标）用中性灰 —— 分歧配色的中点
 * **不能是一个色相**，否则"没有偏离"看起来也像一种状态。
 *
 * 低配的蓝比大类槽 1 的蓝**更深一档**，这样和「流动资金」的色块分得开。
 * 这三个是**文字色**，按 WCAG 正文标准验的（≥ 4.5:1，实测浅色 4.89 / 5.34 / 6.69）。
 * 「超配」的红取自有知有行的 `#E5605C`，但那个在白底只有 3.41:1、正文不合格，
 * 所以浅色模式加深到 `#C5453F`；深色模式底够暗，可以用回原值。
 *
 * 偏离度**不复用 `error`**：超配不是错误，是和计划的差异。把它涂成错误色会让
 * 真正的错误（校验失败）失去分量。
 */
data class ChartColors(
    /** 五大类的分类色，**顺序固定**。索引对应 [AssetClass.displayOrder]。 */
    val assetClassColors: List<Color>,
    /** 条形的轨道色（未填充部分）。中性，让填充的长度读得出来。 */
    val track: Color,
    /** 超配。 */
    val over: Color,
    /** 低配。 */
    val under: Color,
    /** 已达标 —— 分歧配色的中性中点。 */
    val onTarget: Color,
    /**
     * 净值页**趋势图（总资产）**的区域填充。
     *
     * ⚠️ **不要用 `primaryContainer`。** 那是 hero 卡片的底色、整套里最浅的一档 ——
     * 压在页面底上浅色只有 **1.33:1**、深色 1.87:1，填了跟没填一样。
     * 实机反馈过"趋势图不是实心的"，而代码里其实早就没有 alpha 了 ——
     * **"实心"不只是没有透明度，还要色值本身够看得见。**
     *
     * ⚠️ **这个值跟着 primary 的亮度走，换品牌色必须重解**——它已经被换掉两次了：
     * 配深紫檀（L 0.40）时是浅紫 `#C398D6`；换亮玫瑰（L 0.68）后折线和填充糊成一片
     * （只剩 1.31:1），改成了很深的 `#8C0553`；现在 primary 是中玫瑰（L 0.59），
     * 那个深填充又反过来不行了（2.04:1）。
     *
     * **原理是一句话：填充和折线必须在亮度上分开，而折线的亮度由 primary 定死。**
     * primary 每换一次亮度，填充就得跑到另一头去。中玫瑰在中间，所以这次填充
     * 走**浅**的那一头：`#F1B8CD`（L 0.84，品牌同色相 H 355）。
     *
     * 现在浅色：填充对页面 **1.61:1**、折线对填充 **2.70:1**。
     * ⚠️ 填充对页面这个数字**比历版都低**，看起来像是违反了上面"不要用 primaryContainer
     * （1.33:1），填了跟没填一样"那条——**区别在于这次折线是深色且清楚**：
     * 图形的边界由那条 2.70:1 的折线承担，填充只负责"线以下是这一块"的柔和提示，
     * 不再需要自己扛起可见性。深色模式仍是老办法（深填充 + 亮折线），两边策略相反但都成立。
     */
    val trendArea: Color,
) {
    /**
     * 取某个大类的颜色。
     *
     * 按 [AssetClass.displayOrder] 的下标取，而**不是** enum 的 ordinal ——
     * 展示顺序是产品决定的，enum 声明顺序改动不该悄悄换掉全部颜色。
     */
    fun of(assetClass: AssetClass): Color {
        val index = AssetClass.displayOrder.indexOf(assetClass)
        // 下标越界只会是新增了大类而没给颜色。此时退回轨道色而不是崩，
        // 也不要"生成"一个颜色 —— 生成的颜色不受色盲安全验证保护。
        return assetClassColors.getOrElse(index) { track }
    }
}

/**
 * ⚠️ **调亮版：整体上移 0.05，梯度略收窄，彩度一个都没动**（反馈"颜色不要那么深"）。
 * 最沉的蓝和金从 L 0.599 抬到 0.649，橙和青从 0.715 到 0.743。
 *
 * ## 两条踩过的坑，都是官方验证器抓出来的
 *
 * 1. **不要动彩度。** 中间试过一版"彩度统一封顶 0.125 + 朝品牌色融 4%"，想让五个色
 *    读起来更淡、更贴近粉。结果：视觉变化几乎看不出来，**代价却是相邻色的色盲分离度
 *    从 11.5 掉到 8.6**（深色那套更糟，另类实物被压到彩度下限、直接 FAIL"读起来发灰"）。
 *    **用可见性接近零的"变淡"换掉实打实的分离度，是笔亏本买卖**——那版已回退。
 *    现在保留每个色原本的彩度，只搬亮度，分离度 **11.5 原样保住**。
 * 2. **不要把亮度压平成一条线。** 也试过"五个色统一到同一个亮度"来求齐整，
 *    protan/deutan 都过，但**蓝黄色盲（tritan）分离度从 9.6 崩到 3.3**——
 *    因为**蓝↔绿在 tritan 视角下的区分完全靠它们的亮度差撑着**，压平等于拆掉唯一的通道。
 *    所以这里是「保留梯度、整体上移」，不是「拉平」。
 *
 * ⚠️ **tritan 在验证器里是报告项、不是门槛**（protan/deutan 才是），很容易漏看；
 * 我自己那份镜像实现根本没算它，差点放过去。**改色值请跑官方验证器，别跑镜像。**
 *
 * ⚠️ **代价：对页面最低对比度 2.34 → 2.04。** 这条在验证器里是 WARN 不是 FAIL，
 * 靠的是既有约定「色块旁边永远有名字 + 百分比」。**调亮之后这条补偿更吃重了，
 * 改版式时更加不能把那些标签去掉。**
 */
private val LightChartColors = ChartColors(
    assetClassColors = listOf(
        Color(0xFF4D95E0), // 流动资金 — 蓝
        Color(0xFF40C596), // 固定收益 — 绿
        Color(0xFFF29637), // 权益类 — 橙
        Color(0xFF4EBFDE), // 另类实物 — 青
        Color(0xFFA68D21), // 保障类 — 金黄
    ),
    track = Color(0xFFE0E0E0),
    over = Color(0xFFC5453F),
    under = Color(0xFF2F6DB0),
    onTarget = Color(0xFF5C5C5C),
    // 换成中玫瑰之后重新解过 —— 见下方 trendArea 文档。
    trendArea = Color(0xFFF1B8CD),
)

/**
 * 深色模式是**另选的一组步进**，不是浅色的自动翻转 ——
 * 同样的五个色相，按深色底重新取亮度并单独验过。
 */
private val DarkChartColors = ChartColors(
    // ⚠️ **深色这套没有跟着调亮，因为放不下。** 验证器对深色用的是**另一条更窄的亮度带**：
    // 浅色 [0.43, 0.77]，**深色只有 [0.48, 0.67]**。这五个色的最亮一档已经在 0.655，
    // 离天花板只剩 0.015，等于没有上移空间——硬抬会直接判越界。
    // ⚠️ 我的验证器镜像**两边都套了浅色那条带**，因此放行过一组越界的值；
    // 是官方验证器 `--mode dark` 把它拦下来的。**改深色色值必须跑 `--mode dark`。**
    //
    // 这里同时**回退了上一轮那次"彩度封顶 0.125 + 融粉 4%"**——它把另类实物压到彩度
    // 下限触发 FAIL（读起来发灰），还把色盲分离度从 10.8 拉到 8.5。现在是回退后的原值，
    // 官方验证器五项全 PASS。
    assetClassColors = listOf(
        Color(0xFF4186CE),
        Color(0xFF00AB79),
        Color(0xFFCF7600),
        Color(0xFF219FBC),
        Color(0xFF9B8100),
    ),
    track = Color(0xFF3A3A3A),
    over = Color(0xFFE5605C),
    under = Color(0xFF8FBBE8),
    onTarget = Color(0xFFA8A8A8),
    // 深色这个**没跟着改**：它对深色模式的 primary 仍有 2.73:1、对新的深色大类色 ΔE 20.1，
    // 两条都还成立。浅色那边必须改是因为 primary 从亮玫瑰压到了中玫瑰（见 trendArea 文档），
    // 深色模式的 primary 这轮没动，所以这里也不用动。
    trendArea = Color(0xFF664176),
)

/**
 * 由 [BoomssetTheme] 提供。
 *
 * 走 CompositionLocal 而不是让每个图表自己调 `isSystemInDarkTheme()` ——
 * 主题的深浅是可以被显式传参覆盖的，各自读一次系统设置会和主题不一致
 * （预览和测试里尤其容易出现）。
 */
val LocalChartColors = staticCompositionLocalOf { LightChartColors }

internal fun chartColorsFor(darkTheme: Boolean): ChartColors =
    if (darkTheme) DarkChartColors else LightChartColors

/** 图表配色的取用入口。 */
val chartColors: ChartColors
    @Composable @ReadOnlyComposable get() = LocalChartColors.current
