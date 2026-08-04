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
 * 实测（OKLab ΔE ×100，protan/deuteran 模拟）：
 * - 浅色底 `#F7EEE1`：最差相邻对 ΔE 9.1，正常视力最差对 19.6（门槛 8 / 15）
 * - 深色底 `#241F17`：最差相邻对 ΔE 8.4，正常视力最差对 19.3
 *
 * ⚠️ **浅色模式下有四个色低于 3:1 的色块对比度**（暖米色底本来就亮）。
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
 * 这三个是**文字色**，按 WCAG 正文标准验的（≥ 4.5:1，实测 4.86 / 5.77 / 5.04）。
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
        Color(0xFF2A78D6), // 流动资金 — 蓝
        Color(0xFFEB6834), // 固定收益 — 橙
        Color(0xFF1BAF7A), // 权益类 — 青绿
        Color(0xFFEDA100), // 另类实物 — 黄
        Color(0xFFE87BA4), // 保障类 — 洋红
    ),
    track = Color(0xFFE4D8C8),
    over = Color(0xFFC0332F),
    under = Color(0xFF1C5CAB),
    onTarget = Color(0xFF6B6558),
)

/**
 * 深色模式是**另选的一组步进**，不是浅色的自动翻转 ——
 * 同样的五个色相，按深色底重新取亮度并单独验过。
 */
private val DarkChartColors = ChartColors(
    assetClassColors = listOf(
        Color(0xFF3987E5),
        Color(0xFFD95926),
        Color(0xFF199E70),
        Color(0xFFC98500),
        Color(0xFFD55181),
    ),
    track = Color(0xFF4E4536),
    over = Color(0xFFE88A86),
    under = Color(0xFF8FB6F2),
    onTarget = Color(0xFFA8A698),
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
