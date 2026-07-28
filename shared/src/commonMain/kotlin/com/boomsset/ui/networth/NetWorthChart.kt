package com.boomsset.ui.networth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart

/**
 * 净值趋势折线图。
 *
 * y 值传的是**元**（minorUnits / 100）而不是分 —— Vico 内部按 Double 处理，
 * 这里只是展示，不参与任何金额计算。真正的加总一律在 [com.boomsset.domain.Money] 上做。
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

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(),
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
