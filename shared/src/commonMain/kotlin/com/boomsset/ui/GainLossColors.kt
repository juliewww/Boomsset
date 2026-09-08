package com.boomsset.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 涨跌用色。**不是** `MaterialTheme.colorScheme.primary`/`error`，也不是
 * [com.boomsset.ui.theme.ChartColors] 里的 `over`/`under` —— 那两组分别是品牌强调色
 * 和配置页"超配/低配"的分歧色，概念上和"这一段时间涨了还是跌了"无关，混用会在
 * 净值页和资产页同时出现盈亏数字时把语义读串。
 *
 * 中国股市语境**红涨绿跌**，和多数西方 App 的红跌绿涨相反 —— 按用户实际所在的
 * 市场语境来，不是全球通用配色。
 *
 * ## 为什么必须分深浅两组（旧版只有一组固定值）
 *
 * 这两个色是**正文文字**，判据是对**它实际压在的每一块底**都有 ≥ 4.5:1，
 * 而它们出现在三种底上：页面底、普通卡片（`surfaceContainer`）、
 * 以及净值页的 hero 卡片（`primaryContainer`）。
 * 旧值 `#C5453F` 压在暖沙 hero 卡片上只有 **3.34:1**，所以按模式各取一组，
 * 由 [com.boomsset.ui.theme.BoomssetTheme] 通过 [LocalGainLossColors] 提供 ——
 * 和 `LocalChartColors` 同一套做法，**不要在这里自己调 `isSystemInDarkTheme()`**：
 * 主题的深浅是可以被显式传参覆盖的，各读各的会不一致。
 *
 * ⚠️ **最不利的底在深浅两个模式里不是同一个，这里踩过坑。**
 * 浅色模式下 hero 卡片（`#E8D7B8`）比页面底**深**，所以它最不利；
 * 深色模式下 hero 卡片（`#5E4200`）反而比页面底**浅**，也是最不利的那个 ——
 * 但我第一版深色值只对着 `surfaceContainer`（`#201D17`）验，
 * 结果 `#CC6660`/`#43945D` 压在深色 hero 卡片上**只有 2.50:1**，
 * 真机（小米 15 Pro / Android 16）切到深色模式才看出来。
 * **改色值时把三种底逐个验一遍，别假设哪个"最不利"。**
 *
 * 实测：浅色 涨 4.58 / 跌 4.56（对 hero 卡片，另两种底更宽松）；
 * 深色 涨 4.53 / 跌 4.50（对 hero 卡片），对普通卡片 8.1、对页面底 9.0。
 *
 * 只在净值/资产两页的盈亏、涨跌数字上用；不用于配置页（配置页的红蓝是"超配/低配"，
 * 是另一套语义，见 [com.boomsset.ui.theme.ChartColors]）。
 */
data class GainLossColors(
    /** 涨 —— 中国语境用红。 */
    val rise: Color,
    /** 跌 —— 中国语境用绿。 */
    val fall: Color,
)

private val LightGainLoss = GainLossColors(
    rise = Color(0xFFA83634),
    fall = Color(0xFF0A6D37),
)

private val DarkGainLoss = GainLossColors(
    rise = Color(0xFFFD9A92),
    fall = Color(0xFF7CC490),
)

val LocalGainLossColors = staticCompositionLocalOf { LightGainLoss }

internal fun gainLossColorsFor(darkTheme: Boolean): GainLossColors =
    if (darkTheme) DarkGainLoss else LightGainLoss

@Composable
@ReadOnlyComposable
fun riseColor(): Color = LocalGainLossColors.current.rise

@Composable
@ReadOnlyComposable
fun fallColor(): Color = LocalGainLossColors.current.fall
