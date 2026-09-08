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
 * 顺序本身就是**色盲安全机制**，不是审美选择：候选顺序要逐一验证，只在通过的那些里挑。
 * 所以 [assetClassColors] 的顺序**不要重排**，也不要给第六个大类"顺手生成"一个颜色。
 *
 * ## 色相取自有知有行，但**必须 snap 过**
 *
 * 色相角照抄有知有行的 design token（blue `#4287CE` / green `#2EB88A` /
 * orange `#E5881E` / cyan `#66B5CC` / purple `#595E99`），但**不能直接用**：
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
     * 净值页柱状图和底部导航指示条用的**品牌强调色**。
     *
     * ⚠️ **它故意不等于 `colorScheme.primary`。** primary 是奶黄（L 0.80），
     * 对页面底只有 1.78:1 —— 那对 FAB / 开关够用（它们靠投影和深色图标成立），
     * 但**柱子是数据标记、指示条是状态**，两者都必须自己就看得见（≥3:1）。
     * 这个值是同色相下满足 3:1 的最亮档（浅色 3.20:1 / 深色 11.90:1）。
     *
     * 放在 ChartColors 而不是 colorScheme：M3 没有"必须过 3:1 的品牌色"这个角色，
     * 而本项目已有的先例正是把"数据标记的规则和界面装饰分开"（见本文件和
     * [com.boomsset.ui.GainLossColors] 的类注释）。指示条借用它是因为**判据相同**。
     */
    val brand: Color,
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

private val LightChartColors = ChartColors(
    assetClassColors = listOf(
        Color(0xFF3E86D0), // 流动资金 — 蓝
        Color(0xFF2EB88A), // 固定收益 — 绿
        Color(0xFFE58A26), // 权益类 — 橙
        Color(0xFF3FB3D1), // 另类实物 — 青
        Color(0xFF585CA2), // 保障类 — 紫
    ),
    track = Color(0xFFE0E0E0),
    over = Color(0xFFC5453F),
    under = Color(0xFF2F6DB0),
    onTarget = Color(0xFF5C5C5C),
    brand = Color(0xFF9D8B5A),
)

/**
 * 深色模式是**另选的一组步进**，不是浅色的自动翻转 ——
 * 同样的五个色相，按深色底重新取亮度并单独验过。
 */
private val DarkChartColors = ChartColors(
    assetClassColors = listOf(
        Color(0xFF4186CE),
        Color(0xFF00AB79),
        Color(0xFFCF7600),
        Color(0xFF219FBC),
        Color(0xFF5F63AA),
    ),
    track = Color(0xFF3A3A3A),
    over = Color(0xFFE5605C),
    under = Color(0xFF8FBBE8),
    onTarget = Color(0xFFA8A8A8),
    brand = Color(0xFFFCC800),
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
