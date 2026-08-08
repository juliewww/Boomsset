package com.boomsset.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 涨跌用色。**不是** `MaterialTheme.colorScheme.primary`/`error`，也不是
 * [com.boomsset.ui.theme.ChartColors] 里的 `over`/`under` —— 那两组分别是品牌强调色
 * 和配置页"超配/低配"的分歧色，概念上和"这一段时间涨了还是跌了"无关，混用会在
 * 净值页和资产页同时出现盈亏数字时把语义读串（Theme.kt 里留了这条预告注释）。
 *
 * 中国股市语境**红涨绿跌**，和多数西方 App 的红跌绿涨相反 —— 按用户实际所在的
 * 市场语境来，不是全球通用配色。
 *
 * 只在净值/资产两页的盈亏、涨跌数字上用；不用于配置页（配置页的红蓝是"超配/低配"，
 * 是另一套语义，见 [com.boomsset.ui.theme.ChartColors]）。
 */
@Composable
fun riseColor(): Color = Color(0xFFC5453F)

@Composable
fun fallColor(): Color = Color(0xFF2E9E5B)
