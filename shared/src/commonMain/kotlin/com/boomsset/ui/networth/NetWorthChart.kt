package com.boomsset.ui.networth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boomsset.domain.NetWorthSeries
import com.boomsset.domain.Period
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart

/**
 * 净值趋势折线图。
 *
 * y 值传的是**元**（minorUnits / 100）而不是分 —— Vico 内部按 Double 处理，
 * 这里只是展示，不参与任何金额计算。真正的加总一律在 [com.boomsset.domain.Money] 上做。
 *
 * 配色：**只有一条序列，所以用品牌色**，不占用大类的分类色。
 * 单序列不需要图例 —— 标题已经说明它是什么；给它一个分类色反而会让人以为
 * 它和某个大类有关。线下方铺一层同色的淡填充，让趋势在小尺寸下更好读。
 */
@Composable
fun NetWorthChart(
    series: NetWorthSeries,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(series) {
        if (series.points.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineModel {
                series(series.points.map { it.netWorth.minorUnits / 100.0 })
            }
        }
    }

    val brand = MaterialTheme.colorScheme.primary

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(
                    LineCartesianLayer.rememberLine(
                        fill = LineCartesianLayer.LineFill.single(Fill(brand)),
                        areaFill = LineCartesianLayer.AreaFill.single(
                            Fill(brand.copy(alpha = 0.16f)),
                        ),
                    ),
                ),
            ),
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = { _, x, _ ->
                    series.dates.getOrNull(x.toInt())?.let { formatAxisLabel(it, series.period) }
                        ?: ""
                },
            ),
        ),
        modelProducer = modelProducer,
        modifier = modifier.fillMaxWidth().height(220.dp),
    )
}

private fun formatAxisLabel(
    date: kotlinx.datetime.LocalDate,
    period: Period,
): String = when (period) {
    Period.MONTH -> "${date.month.ordinal + 1}月"
    Period.QUARTER -> "Q${date.month.ordinal / 3 + 1}"
    Period.YEAR -> date.year.toString()
}
