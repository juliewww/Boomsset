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
 * 这两个色是**正文文字**，判据是对**它实际压在的那块底**有 ≥ 4.5:1。
 * 最不利的底是净值页的 hero 卡片（`primaryContainer`）—— 它比页面底更深。
 * 旧值 `#C5453F` 压在新的暖沙卡片 `#E6D7BC` 上只有 **3.34:1**，不合格；
 * 深色模式下压在 `#201D17` 上又太暗。所以按模式各取一组，
 * 由 [com.boomsset.ui.theme.BoomssetTheme] 通过 [LocalGainLossColors] 提供 ——
 * 和 `LocalChartColors` 同一套做法，**不要在这里自己调 `isSystemInDarkTheme()`**：
 * 主题的深浅是可以被显式传参覆盖的，各读各的会不一致。
 *
 * 实测：浅色 涨 4.57:1 / 跌 4.55:1（对 hero 卡片），对页面底更宽松；
 * 深色 涨 4.51:1 / 跌 4.51:1（对 `surfaceContainer`）。
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
    rise = Color(0xFFCC6660),
    fall = Color(0xFF43945D),
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
