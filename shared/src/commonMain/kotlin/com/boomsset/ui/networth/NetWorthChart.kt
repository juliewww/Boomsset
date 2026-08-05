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

    // 标签数量超过这个值就会在 220dp 宽的图上挤到重叠/截断
    // （实测：12 个月标签挤在一起，"10月"/"11月"/"12月" 被截断成"10…"/"11…"/"12…"）。
    // Vico 的默认 itemPlacer 是"每个点都放一个标签"，点数超过这个阈值就每隔几个点标一次。
    val maxLabels = 6
    val labelSpacing = remember(series.dates.size) {
        if (series.dates.size <= maxLabels) 1 else (series.dates.size + maxLabels - 1) / maxLabels
    }
    // `aligned` 默认从 minX（下标 0）开始数，每隔 spacing 个点标一次 ——
    // 点数不是 spacing 的整数倍时，**最后一个点（今天所在的周期）可能刚好落不到标签上**。
    // 而这正是用户最关心的那个点。把 offset 取成"最后一个下标 mod spacing"，
    // 让标签序列反过来从最后一个点对齐，永远把最新的点标出来。
    val labelOffset = remember(series.dates.size, labelSpacing) {
        if (labelSpacing <= 1) 0 else (series.dates.size - 1) % labelSpacing
    }

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
                // spacing 按点数动态算，而不是固定值 —— trim 之后点数会变
                // （几个月的历史 vs 满 12 个月），写死会在点少的时候白白稀疏、
                // 点多的时候还是不够稀疏。
                //
                // ⚠️ Vico 3.2.3（项目锁定版本）的 `aligned()` 只有 spacing / offset /
                // shiftExtremeLines / addExtremeLabelPadding 四个参数 —— 没有
                // shiftExtremeLabels（那是我第一次查文档时从 GitHub master 分支查到的，
                // 不是这个锁定版本真正有的参数，编译直接报"找不到参数"）。
                // 教训和 Vico 包路径那条一样：**必须对着 pinned 的 tag 查，不能信 master**，
                // 用 `gh api repos/.../git/refs/tags` 找到版本对应的 commit sha 再查源码。
                itemPlacer = remember(labelSpacing, labelOffset) {
                    HorizontalAxis.ItemPlacer.aligned(
                        spacing = { labelSpacing },
                        offset = { labelOffset },
                    )
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
