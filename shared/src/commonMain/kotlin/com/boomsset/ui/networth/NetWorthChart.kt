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
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * 净值趋势图 —— **柱状图 + 折线图**的组合，不是纯折线。
 *
 * 原来只有折线：第一次记完快照、`trimBeforeFirstSnapshot` 裁剪之后序列里只剩
 * 一个点，而 Vico 的折线需要 2 个以上的点才画得出线段 —— 结果用户看到的只有
 * 坐标轴，没有任何可见图形（实机反馈："只有一条虚线"）。柱状图不依赖相邻点，
 * 一个点也能画出一根柱子；多个点时柱子照常都在，折线再叠加在上面表示趋势。
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

    // 只有一个点时，Vico 会让那一根柱子撑满整条 x 轴，`thickness` 完全不起作用——
    // 实机验证过：把 thickness 从 10dp 改到 1dp，柱子宽度肉眼看不出任何变化，
    // 说明 Vico 是按"这个 x 位置能用的宽度"来画柱子，跟 LineComponent 声明的宽度无关。
    // 只有 1 个点时"能用的宽度"就是整个绘图区，柱子于是变成一整块实心矩形
    // （实机反馈原话："宽度太宽"）。
    //
    // 改 x 轴范围（比如强行把 minX 往左扩）修不了这个：试过之后 Vico 在测量坐标轴标签宽度时
    // 会对扩出来的那些"虚拟"x 位置也调一次 valueFormatter，而这些位置没有对应的日期、
    // formatter 只能返回空字符串——Vico 明确不允许 formatter 返回空串（会直接抛
    // IllegalStateException 崩溃，见其异常信息："改用 ItemPlacer，别用空字符串"）。
    // 而 ItemPlacer 的 spacing/offset 逻辑是按"真实点数"算的，不知道哪些 x 位置是刚扩出来的
    // 虚拟位置，没法简单地把这些位置从候选里摘出去。
    //
    // 改用**幽灵系列**：借 `MergeMode.Grouped` 把同一个 x 位置的宽度切成几份——
    // 真实数据只占其中一份，其余几份是全 0 值的占位系列（0 高度=不可见）。
    // 这个办法完全不碰 x 轴范围和坐标轴标签，只影响"一个 x 位置内部怎么分宽度"，
    // 不会重蹈上面那个崩溃。**只在恰好 1 个点时才加占位系列**——2 个点以上时，
    // 多个真实点本来就会自然分布在整个宽度上，不会出现"一整块"这种一眼看去像
    // 渲染错误的效果，不需要额外处理。
    //
    // ⚠️ Grouped 按 series() 的调用顺序从左到右排列子柱——第一次实现把真实系列放在
    // 最前面，结果真实那根柱子紧贴在这个 x 位置**最左边**，而坐标轴标签（"8月"）
    // 是按**整个位置的中心**画的，于是出现"柱子和它自己的月份标签对不上"（实机反馈）。
    // 改成左右各放两个占位系列、真实系列夹在正中间（5 个系列，下标 2 正好是中心），
    // 柱子的水平中心才会和标签的水平中心重合。
    val phantomColumnsPerSide = 2
    LaunchedEffect(series) {
        if (series.points.isEmpty()) return@LaunchedEffect
        val values = series.points.map { it.netWorth.minorUnits / 100.0 }
        modelProducer.runTransaction {
            columnModel {
                if (values.size == 1) {
                    repeat(phantomColumnsPerSide) { series(listOf(0.0)) }
                    series(values)
                    repeat(phantomColumnsPerSide) { series(listOf(0.0)) }
                } else {
                    series(values)
                }
            }
            lineModel { series(values) }
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

    val column = rememberLineComponent(
        fill = Fill(brand.copy(alpha = 0.5f)),
        thickness = 10.dp,
        shape = RoundedCornerShape(2.dp),
    )
    // 幽灵系列复用同一个 LineComponent 也没关系——它们的值是 0，画出来的高度是 0，
    // 用什么颜色都看不见。数量必须跟 columnModel 里 series() 调用的次数对上，
    // 否则 Vico 找不到对应下标的 column 会抛异常。
    val columnCount = if (series.points.size == 1) phantomColumnsPerSide * 2 + 1 else 1
    val columnProvider = remember(columnCount, column) {
        ColumnCartesianLayer.ColumnProvider.series(List(columnCount) { column })
    }

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                columnProvider = columnProvider,
                mergeMode = { ColumnCartesianLayer.MergeMode.Grouped(columnSpacing = 0.dp) },
            ),
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
