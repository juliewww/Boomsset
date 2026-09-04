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
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
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
        val labels = axisLabels(series)
        modelProducer.runTransaction {
            // 标签必须和这一批数据点**同一个 transaction** 落地 —— 见 AxisLabelsKey 的注释
            extras { it[AxisLabelsKey] = labels }
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
                // x 和标签的对应关系一律从**正在绘制的那个 model** 上取，不从 composition
                // 捕获的 series 上取 —— 见 AxisLabelsKey 的注释，那是一次真实崩溃的修复。
                valueFormatter = { context, x, _ ->
                    axisLabelAt(context.model.extraStore.getOrNull(AxisLabelsKey), x)
                },
                // spacing 按点数动态算，而不是固定值 —— trim 之后点数会变
                // （几个月的历史 vs 满 12 个月），写死会在点少的时候白白稀疏、
                // 点多的时候还是不够稀疏。点数同样从 model 的 ExtraStore 上取：
                // Vico 把 `model.extraStore` 传给这两个 lambda，所以它和 valueFormatter
                // 看到的是同一批标签，不会一个新一个旧。
                //
                // ⚠️ Vico 3.2.3（项目锁定版本）的 `aligned()` 只有 spacing / offset /
                // shiftExtremeLines / addExtremeLabelPadding 四个参数 —— 没有
                // shiftExtremeLabels（那是我第一次查文档时从 GitHub master 分支查到的，
                // 不是这个锁定版本真正有的参数，编译直接报"找不到参数"）。
                // 教训和 Vico 包路径那条一样：**必须对着 pinned 的 tag 查，不能信 master**，
                // 用 `gh api repos/.../git/refs/tags` 找到版本对应的 commit sha 再查源码。
                itemPlacer = remember {
                    HorizontalAxis.ItemPlacer.aligned(
                        spacing = { extras -> axisLabelSpacing(extras.labelCount()) },
                        offset = { extras -> axisLabelOffset(extras.labelCount()) },
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

/**
 * x 轴标签**跟着 model 一起走**，通过 [ExtraStore] 塞进同一次 `runTransaction`。
 *
 * 修的是一次实机崩溃：**按月切到按季/按年直接闪退**。
 * `modelProducer` 是 `remember {}` 出来的、跨 period 切换一直活着，而模型更新是
 * `LaunchedEffect` 里的 **suspend transaction**（还带过渡动画）。切 period 的那一帧，
 * composition 已经拿到新的 `series`（比如按年只有 1 个点），Vico 手里却还是旧模型
 * （按月 12 个点）—— 之前 formatter 直接闭包捕获 `series.dates`，于是被问到 x=1..11 时
 * `getOrNull` 返回 null、formatter 返回空串，而 Vico 对每个标签都有
 * `check(isNotBlank())`，**空串直接抛 IllegalStateException**。
 * 反过来（按年切按月）点数变多，取不到 null，所以只有切到粗粒度才崩 —— 这正是反馈的现象。
 *
 * `itemPlacer` 的 spacing/offset 也是同一个坑的另一半：Vico 把 `model.extraStore`
 * 传给这两个 lambda，而 `getFirstLabelValue()` 会用 `minX + offset * xStep` 去问 formatter，
 * **这个 x 不做范围裁剪**。旧模型点少、新算出来的 offset 偏大时，一样会问到越界的 x。
 *
 * 所以判据是：**凡是 formatter/ItemPlacer 需要的东西，都必须和数据点在同一个 transaction 里，
 * 不能从 composition 捕获。** 否则"UI 状态"和"图表模型"之间必然有一帧不一致。
 */
private val AxisLabelsKey = ExtraStore.Key<List<String>>()

private fun ExtraStore.labelCount(): Int = getOrNull(AxisLabelsKey)?.size ?: 0

internal fun axisLabels(series: NetWorthSeries): List<String> =
    series.dates.map { formatAxisLabel(it, series.period) }

/**
 * 标签数量超过这个值就会在 220dp 宽的图上挤到重叠/截断
 * （实测：12 个月标签挤在一起，"10月"/"11月"/"12月" 被截断成"10…"/"11…"/"12…"）。
 */
private const val MAX_AXIS_LABELS = 6

/** Vico 的默认 itemPlacer 是"每个点都放一个标签"，点数超过阈值就每隔几个点标一次。 */
internal fun axisLabelSpacing(labelCount: Int): Int =
    if (labelCount <= MAX_AXIS_LABELS) 1
    else (labelCount + MAX_AXIS_LABELS - 1) / MAX_AXIS_LABELS

/**
 * `aligned` 默认从 minX（下标 0）开始数，每隔 spacing 个点标一次 ——
 * 点数不是 spacing 的整数倍时，**最后一个点（今天所在的周期）可能刚好落不到标签上**，
 * 而那正是用户最关心的点。取"最后一个下标 mod spacing"，让标签序列反过来从最后一个点对齐。
 */
internal fun axisLabelOffset(labelCount: Int): Int {
    val spacing = axisLabelSpacing(labelCount)
    return if (spacing <= 1 || labelCount == 0) 0 else (labelCount - 1) % spacing
}

/**
 * 取不到标签时给一个占位符而**不是空串** —— Vico 对每个轴标签都做 `check(isNotBlank())`，
 * 返回空串会抛异常把 App 打崩。标签现在随 model 走，正常情况下不会走到这个分支；
 * 留着是因为"崩掉"和"多一个占位点"完全不对等，兜底必须便宜且安全。
 */
internal const val MISSING_AXIS_LABEL = "·"

internal fun axisLabelAt(labels: List<String>?, x: Double): String =
    labels?.getOrNull(x.toInt())?.takeIf { it.isNotBlank() } ?: MISSING_AXIS_LABEL
