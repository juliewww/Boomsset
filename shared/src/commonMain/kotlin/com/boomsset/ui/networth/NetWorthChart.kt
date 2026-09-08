package com.boomsset.ui.networth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.boomsset.domain.AllocationSeries
import com.boomsset.domain.AssetClass
import com.boomsset.domain.Money
import com.boomsset.domain.NetWorthSeries
import com.boomsset.domain.Period
import com.boomsset.domain.PortfolioCalculator
import com.boomsset.ui.fallColor
import com.boomsset.ui.riseColor
import com.boomsset.ui.theme.chartColors
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.datetime.LocalDate

/** 图表看的是哪个口径。 */
enum class ChartMode {
    /** 一根柱子 = 整体净值。 */
    TOTAL,

    /** 一根柱子按五大类堆叠，每个周期仍然只有一根。 */
    ALLOCATION,
}

/** 图表画成什么样子。 */
enum class ChartStyle {
    /** 柱状图：每个周期一根，柱顶上方标出相对前一根的增长率。 */
    COLUMN,

    /** 趋势图：面积/折线，看的是形状而不是逐期的涨跌幅。 */
    TREND,
}

private val CHART_HEIGHT = 220.dp

/**
 * 净值页的图表。四种组合：总资产/按大类 × 柱状图/趋势图。
 *
 * ⚠️ **每种组合都套在 `key(mode, style)` 里，让切换时整个 host 离开 composition。**
 * 这不是风格问题，是 Vico 的一条硬约束：`CartesianChartModelProducer.collectAsState`
 * 里有一句 `check(previousHashCode == null || hashCode == previousHashCode)`，
 * 给同一个 host 换一个 producer 会直接抛异常。而柱状图和趋势图需要的 model 类型不同
 * （`columnModel` / `lineModel`），一个 producer 里同时塞两种也不行 ——
 * 图表的 y 轴范围是按 `model.models` **全体**算的，用不到的那份 model 会把范围一起撑开。
 * `key()` 让每种组合各自持有一个干净的 producer，互不干扰。
 *
 * @param visibleClasses 按大类看时要画哪几类，**必须按 [AssetClass.displayOrder] 排好** ——
 *   堆叠顺序和颜色顺序都依赖它，乱序会让同一类在柱状图和趋势图里换位置。
 * @param hideAmounts 眼睛图标藏起金额时为 true —— **纵轴刻度整条不画**。
 *   柱子/曲线的形状和顶上的增长率都留着：那些是相对量，藏了反而把这一页变成一张白图。
 */
@Composable
fun NetWorthChart(
    series: NetWorthSeries,
    allocationSeries: AllocationSeries,
    mode: ChartMode,
    style: ChartStyle,
    visibleClasses: List<AssetClass>,
    hideAmounts: Boolean = false,
    modifier: Modifier = Modifier,
) {
    key(mode, style) {
        when {
            style == ChartStyle.COLUMN && mode == ChartMode.TOTAL ->
                TotalColumnChart(series, hideAmounts, modifier)

            style == ChartStyle.COLUMN ->
                AllocationColumnChart(allocationSeries, visibleClasses, hideAmounts, modifier)

            mode == ChartMode.TOTAL -> TotalTrendChart(series, hideAmounts, modifier)

            else -> AllocationTrendChart(allocationSeries, visibleClasses, hideAmounts, modifier)
        }
    }
}

/**
 * 趋势图至少要两个点才画得出线段 —— 只有一个点时 Vico 什么都不画，
 * 用户看到的是一张只有坐标轴的空图（这就是当初把净值图从纯折线改成柱状图的原因，
 * 见 AGENTS.md 教训 10）。趋势图现在是用户主动选的，所以点数不够时要**明说**，
 * 而不是给一张空图让人以为是坏了。
 */
fun canDrawTrend(pointCount: Int): Boolean = pointCount >= 2

// ---------------------------------------------------------------- 柱状图

/**
 * 总资产柱状图。
 *
 * 只有一个点时，Vico 会把那一根柱子放大到撑满整条 x 轴，`thickness` 完全不起作用——
 * 实机验证过：把 thickness 从 10dp 改到 1dp，柱子宽度肉眼看不出任何变化。原因是
 * `CartesianChartHost` 默认会把内容缩放到填满视口，只有 1 个 x 位置时缩放系数就变得很大。
 *
 * 改 x 轴范围（把 minX 往左扩）修不了这个：Vico 在测量坐标轴标签宽度时会对扩出来的
 * "虚拟" x 位置也调一次 `valueFormatter`，而那些位置没有对应日期、formatter 只能返回
 * 空串 —— Vico 明确不允许（`IllegalStateException`，异常信息就写着"改用 ItemPlacer"）。
 *
 * 用的是**幽灵系列**：借 `MergeMode.Grouped` 把同一个 x 位置的宽度切成 5 份，
 * 真实数据占中间那份，其余 4 份是全 0 值的占位系列（0 高度 = 不可见）。
 * ⚠️ Grouped 按 `series()` 的调用顺序从左到右排子柱，所以占位系列必须**左右各两个**、
 * 真实系列夹在正中间 —— 否则柱子贴在这个 x 位置最左边，而坐标轴标签是按整个位置的
 * 中心画的，看起来就是"柱子对错了日期"（实机反馈过一次）。
 */
@Composable
private fun TotalColumnChart(series: NetWorthSeries, hideAmounts: Boolean, modifier: Modifier) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val values = remember(series) { series.points.map { it.netWorth.toYuan() } }
    val labels = remember(series) { axisLabels(series.period, series.dates) }
    val growth = remember(series) { growthLabels(series.points.map { it.netWorth }) }

    LaunchedEffect(series) {
        if (values.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            // 标签必须和这一批数据点**同一个 transaction** 落地 —— 见 [AxisLabelsKey]
            extras {
                it[AxisLabelsKey] = labels
                it[GrowthLabelsKey] = growth
            }
            columnModel {
                if (values.size == 1) {
                    repeat(PHANTOM_COLUMNS_PER_SIDE) { series(listOf(0.0)) }
                    series(values)
                    repeat(PHANTOM_COLUMNS_PER_SIDE) { series(listOf(0.0)) }
                } else {
                    series(values)
                }
            }
        }
    }

    // 柱子直接用 `colorScheme.primary`。
    // ⚠️ 这一条**和 primary 的亮度强耦合**：奶黄那一版 primary 对页面底只有 1.78:1，
    // 当时不得不把柱子拆出一个单独的深色（`ChartColors.brand`）。现在 primary 是
    // 深紫檀，对页面底 9.31:1，柱子可以回到单一品牌色。**换浅色品牌色时要重新量这条。**
    val brand = MaterialTheme.colorScheme.primary
    // **实色，不加 alpha。** 这里原本是 `alpha = 0.5f` —— 那是柱子和折线叠画那一版的
    // 遗留（半透明才能让折线透出来），折线删掉之后它只剩"把柱子变淡"这一个效果：
    // 实测 0.5 alpha 下柱子对页面底只有 **1.95:1**，而柱子是这一页的主数据标记。
    // 实色是 9.31:1。（这个 alpha 一直都偏低 —— 配旧的 `#BD4D03` 是 2.08:1、
    // 配莫兰迪 `#918163` 更是 1.77:1，只是那时没人量过。）
    // 趋势图的区域填充也已经改成不透明，见 TotalTrendChart。
    val column = rememberLineComponent(
        fill = Fill(brand),
        thickness = 10.dp,
        shape = RoundedCornerShape(2.dp),
    )
    // 幽灵系列复用同一个 LineComponent 没关系 —— 它们的值是 0，画出来高度是 0，
    // 用什么颜色都看不见。
    val columnCount = if (values.size == 1) PHANTOM_COLUMNS_PER_SIDE * 2 + 1 else 1
    val columnProvider = remember(columnCount, column) {
        ColumnCartesianLayer.ColumnProvider.series(List(columnCount) { column })
    }

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                columnProvider = columnProvider,
                mergeMode = { ColumnCartesianLayer.MergeMode.Grouped(columnSpacing = 0.dp) },
            ),
            startAxis = rememberAmountAxis(hideAmounts),
            topAxis = rememberGrowthAxis(),
            bottomAxis = rememberPeriodAxis(),
        ),
        modelProducer = modelProducer,
        // 只用 Zoom.Content（不夹 Zoom.x）：1 个点时正好让那一个 x 位置铺满视口，
        // 而真实柱子只占其中 1/5，幽灵系列的效果就是这么来的。
        zoomState = rememberFittingZoomState(remember { Zoom.Content }),
        modifier = modifier.fillMaxWidth().height(CHART_HEIGHT),
    )
}

/**
 * 柱状图一律「内容铺满、不横向滚动」。
 *
 * Vico 默认的 `initialZoom` 是 `Zoom.max(Zoom.fixed(), Zoom.Content)` —— 柱子按 1:1
 * 排下来比视口宽时它取 1:1，图表变成可横向滚动，而**默认停在最左边**：
 * 最新那一期在屏幕外，还没有任何滚动提示。实机 12 根堆叠柱就是这样，9 月那根被切掉了。
 * 净值图最多 12 个点，全塞进一屏永远好过藏起最新一期。
 *
 * `minZoom` 必须一起给：它默认是 `Zoom.Content`，而 Vico 取 `max(minZoom, initialZoom)`，
 * 只改 `initialZoom` 会被拉回去。
 */
@Composable
private fun rememberFittingZoomState(zoom: Zoom) =
    rememberVicoZoomState(zoomEnabled = false, initialZoom = zoom, minZoom = zoom)

/**
 * 按大类堆叠的柱状图 —— 每个周期仍然只有一根柱子，柱子内部按五大类分段。
 *
 * 段的口径是**净敞口**（该类资产 − 归属到该类的负债），和配置页完全一致。
 * 负敞口会被 Vico 画到零线**下方**（`MergeMode.Stacked` 原生支持），
 * 也就是说这时候"一根柱子"会变成上下两截 —— 这是真实情况，不做隐藏，
 * 页面上另有一行说明。
 *
 * ⚠️ 单点时柱子撑满的问题在这里**不能**用幽灵系列解决：Stacked 不切分同一个 x 位置的
 * 宽度（那是 Grouped 才做的事），加多少条 0 值系列柱子都不会变窄。改用 `Zoom.x(n)`
 * 让视口里至少容得下 n 个 x 单位，柱子自然只占 1/n。
 * `minZoom` 默认是 `Zoom.Content`，**必须一起改**，否则 Vico 取两者较大的那个，
 * 又把柱子放大回去。
 */
@Composable
private fun AllocationColumnChart(
    series: AllocationSeries,
    classes: List<AssetClass>,
    hideAmounts: Boolean,
    modifier: Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val labels = remember(series) { axisLabels(series.period, series.dates) }
    // 增长率按**可见那几类的合计**算 —— 勾掉几类之后柱子矮了一截，
    // 此时拿全量合计算出来的百分比和眼前的柱子对不上。
    val growth = remember(series, classes) { growthLabels(series.totals(classes)) }
    val values = remember(series, classes) {
        classes.map { assetClass -> series.netExposures(assetClass).map { it.toYuan() } }
    }

    LaunchedEffect(values) {
        if (values.isEmpty() || values.first().isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            extras {
                it[AxisLabelsKey] = labels
                it[GrowthLabelsKey] = growth
            }
            columnModel { values.forEach { series(it) } }
        }
    }

    val palette = chartColors
    val columns = classes.map { assetClass ->
        rememberLineComponent(
            fill = Fill(palette.of(assetClass)),
            thickness = 16.dp,
            shape = RoundedCornerShape(2.dp),
        )
    }
    val columnProvider = remember(columns) {
        ColumnCartesianLayer.ColumnProvider.series(columns)
    }

    // 一个表达式同时管住两头：点多时 Content 更小（缩到刚好装下），
    // 只有 1 个点时 Content 会把那根柱子拉宽到整屏、此时 Zoom.x 更小 —— min 取到的正是想要的那个。
    val fit = remember { Zoom.min(Zoom.Content, Zoom.x(SINGLE_POINT_SLOTS)) }

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                columnProvider = columnProvider,
                mergeMode = { ColumnCartesianLayer.MergeMode.Stacked },
            ),
            startAxis = rememberAmountAxis(hideAmounts),
            topAxis = rememberGrowthAxis(),
            bottomAxis = rememberPeriodAxis(),
        ),
        modelProducer = modelProducer,
        zoomState = rememberFittingZoomState(fit),
        modifier = modifier.fillMaxWidth().height(CHART_HEIGHT),
    )
}

// ---------------------------------------------------------------- 趋势图

/** 总资产趋势：一条线 + 线下的淡填充。单序列用品牌色，不占用大类的分类色。 */
@Composable
private fun TotalTrendChart(series: NetWorthSeries, hideAmounts: Boolean, modifier: Modifier) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val values = remember(series) { series.points.map { it.netWorth.toYuan() } }

    LaunchedEffect(series) {
        if (values.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction { lineModel { series(values) } }
    }

    val brand = MaterialTheme.colorScheme.primary
    // 区域填充：不透明，且**色值要有分量**。这里踩过两次 ——
    // 先是 `brand.copy(alpha = 0.16f)`（16% 几乎等于没填），改成不透明之后
    // 又选了 `primaryContainer`，那是 hero 卡片的底、整套里最浅的一档，
    // 对页面底只有 1.33:1，实机上照样被反馈"不是实心的"。
    // 现在用 `chartColors.trendArea`（2.29:1），和按大类那版的堆叠面积同一档。
    val area = chartColors.trendArea
    TrendChartFrame(series.dates, modifier) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(
                        LineCartesianLayer.rememberLine(
                            fill = LineCartesianLayer.LineFill.single(Fill(brand)),
                            areaFill = LineCartesianLayer.AreaFill.single(Fill(area)),
                        ),
                    ),
                ),
                startAxis = rememberAmountAxis(hideAmounts),
            ),
            modelProducer = modelProducer,
            modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT),
        )
    }
}

/**
 * 趋势图 + 图下的首末日期。
 *
 * 趋势图看的是形状，中间那些刻度对判断走势没帮助、反而挤；但"这段曲线从哪天到哪天"
 * 必须交代 —— 没有它，一条上扬的曲线可能是三个月也可能是三年。
 *
 * ⚠️ **这两个日期是自己画的，没有用 Vico 的底部坐标轴。** 试过了，画不出来：
 * `AlignedHorizontalAxisItemPlacer.getLabelValues` 会跳过正好落在 x 范围端点上的值
 * （`potentialValue == fullXRange.endInclusive` 直接 break），而折线层两端不留白、
 * 最后一个点恰好就是那个端点，于是**末尾日期永远不画**（实机 12 个点时只有起始日期，
 * 连它那根竖向网格线都没有）。按文档给 `layerPadding` 加留白本该把端点让开，
 * 实机 1dp / 32dp / 150dp 三档试下来**画面一模一样**，那条参数在这个组合下不起作用。
 * 两个字符串的说明行不值得再跟布局引擎较劲，自己画反而每次都对。
 */
@Composable
private fun TrendChartFrame(
    dates: List<LocalDate>,
    modifier: Modifier,
    chart: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        chart()
        val labels = dateLabels(dates)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 空列表在这里画不出图，上层已经拦掉（canDrawTrend），兜底给空串也无妨 ——
            // 这不是 Vico 的坐标轴，空串只是不显示，不会抛异常
            TrendDateLabel(labels.firstOrNull())
            TrendDateLabel(labels.lastOrNull())
        }
    }
}

@Composable
private fun TrendDateLabel(text: String?) {
    Text(
        text = text.orEmpty(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 按大类的趋势图 —— 正常情况下是**堆叠面积**。
 *
 * Vico 没有原生的堆叠面积图。这里是用「累计边界 + 不透明填充 + 后画的盖前画的」拼出来的：
 * 第 k 条线画的是前 k+1 类的累计值、各自填到 0，从最高的那条开始画，
 * 后面每条盖住前面一截，剩下的可见部分正好就是那一类的带子。
 *
 * ⚠️ 这个拼法**要求每一段都非负**。净敞口是可以为负的（车贷超过车值、
 * 信用卡欠款超过流动资金），一旦有负值，累计就不再单调、边界互相穿插，
 * 画出来是一张看着正常、其实分层错位的图。所以先问
 * [AllocationSeries.hasNegativeExposure]，命中就**退回各类独立曲线**（不填充），
 * 页面上同时给出说明 —— 宁可换一种表达，也不给一张静默画错的图。
 */
@Composable
private fun AllocationTrendChart(
    series: AllocationSeries,
    classes: List<AssetClass>,
    hideAmounts: Boolean,
    modifier: Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val stacked = remember(series, classes) { !series.hasNegativeExposure(classes) }
    val lineValues = remember(series, classes, stacked) {
        val perClass = classes.map { assetClass ->
            series.netExposures(assetClass).map { it.minorUnits }
        }
        val bands = if (stacked) stackedBands(perClass) else perClass
        bands.map { band -> band.map { it / 100.0 } }
    }
    // 堆叠时从最高的那条开始画（后画的盖前画的）；退回独立曲线时顺序无所谓，
    // 保持大类展示顺序，图例读起来和配置页一致。
    val order = remember(lineValues, stacked) {
        if (stacked) lineValues.indices.reversed().toList() else lineValues.indices.toList()
    }

    LaunchedEffect(lineValues, order) {
        if (lineValues.isEmpty() || lineValues.first().isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineModel { order.forEach { index -> series(lineValues[index]) } }
        }
    }

    val palette = chartColors
    val lines = order.map { index ->
        val color = palette.of(classes[index])
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(color)),
            areaFill = if (stacked) LineCartesianLayer.AreaFill.single(Fill(color)) else null,
        )
    }

    TrendChartFrame(series.dates, modifier) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(lines),
                ),
                startAxis = rememberAmountAxis(hideAmounts),
            ),
            modelProducer = modelProducer,
            modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT),
        )
    }
}

// ---------------------------------------------------------------- 坐标轴

/**
 * 顶部的增长率标签带 —— 每根柱子上方一个"相对前一根涨跌多少"。
 *
 * **为什么是一条坐标轴，而不是 Vico 自带的 `dataLabel`：** dataLabel 的 formatter
 * 只拿得到 y 值、拿不到 x，只能靠"y 值 → 标签"的映射表反查。而这个 App 的结转语义
 * 让**相邻周期净值完全相同**很常见（没记新快照就沿用上次估值，有测试锁着），
 * 两根一样高的柱子会共用同一个键，增长率就会标反 —— 静默算错，不能接受。
 * 坐标轴的 formatter 拿得到 x，按下标取值，不存在这个问题。
 *
 * 颜色逐个标签不同（红涨绿跌）靠的是返回 [AnnotatedString]：Vico 的 TextComponent
 * 会把 `CharSequence` 原样交给 `TextMeasurer`，是 AnnotatedString 就走带 span 的重载。
 */
@Composable
private fun rememberGrowthAxis(): HorizontalAxis<Axis.Position.Horizontal.Top> {
    val rise = riseColor()
    val fall = fallColor()
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    return HorizontalAxis.rememberTop(
        // 线、刻度、网格线全去掉：这一条不是真的坐标轴，只是借它的定位能力摆一排标签
        line = null,
        label = rememberAxisLabelComponent(
            style = MaterialTheme.typography.labelSmall.copy(color = neutral),
        ),
        valueFormatter = remember(rise, fall, neutral) {
            CartesianValueFormatter { context, x, _ ->
                val label = growthLabelAt(context.model.extraStore.getOrNull(GrowthLabelsKey), x)
                label.annotated(rise = rise, fall = fall, neutral = neutral)
            }
        },
        tick = null,
        guideline = null,
        // spacing/offset 和底部的周期轴**完全一致**：这样标了增长率的柱子底下一定也有月份，
        // 读起来是"8月 +12%"一组，而不是上下两排各标各的。
        //
        // ⚠️ 不能图省事用 `aligned()` 的默认值（spacing = 1、offset = 0）。
        // `aligned()` 默认 `addExtremeLabelPadding = true`，Vico 会把 spacing 再乘上
        // `ceil(maxLabelWidth / xSpacing)` 防止标签重叠 —— 12 根柱子时实际间隔变成 2，
        // 而 offset 还是 0，于是标到 0/2/4/6/8/10，**最后一根（最新那期）恰好漏掉**。
        // 实机截图确认过：12 个月时只有 6 个百分比，且最右边那根柱子头上是空的。
        itemPlacer = remember {
            HorizontalAxis.ItemPlacer.aligned(
                spacing = { extras -> axisLabelSpacing(extras.labelCount(GrowthLabelsKey)) },
                offset = { extras -> axisLabelOffset(extras.labelCount(GrowthLabelsKey)) },
            )
        },
    )
}

/** 底部的周期标签（"8月" / "Q3" / "2026"），点多时按 [axisLabelSpacing] 稀疏标注。 */
@Composable
private fun rememberPeriodAxis(): HorizontalAxis<Axis.Position.Horizontal.Bottom> =
    HorizontalAxis.rememberBottom(
        // x 和标签的对应关系一律从**正在绘制的那个 model** 上取，不从 composition
        // 捕获的 series 上取 —— 见 [AxisLabelsKey]，那是一次真实崩溃的修复。
        valueFormatter = { context, x, _ ->
            axisLabelAt(context.model.extraStore.getOrNull(AxisLabelsKey), x)
        },
        itemPlacer = remember {
            HorizontalAxis.ItemPlacer.aligned(
                spacing = { extras -> axisLabelSpacing(extras.labelCount(AxisLabelsKey)) },
                offset = { extras -> axisLabelOffset(extras.labelCount(AxisLabelsKey)) },
            )
        },
    )

/**
 * 纵轴金额，缩写成「万/亿」—— 完整数字（600900.36）会占掉三四十 dp 的绘图宽度。
 *
 * [hideAmounts] 时把 `label` 整个给 null（Vico 的 `rememberStart` 允许，
 * 画标签那一步是 `label ?: return@forEach`），**刻度线和网格线留着** ——
 * 没有它们柱子就悬在空处，读不出高低。
 *
 * 不走"formatter 返回占位符"那条路：Vico 会在每一个刻度上都画一遍那个占位符，
 * 变成纵向一列重复的 `••••••`，比不画更吵。（顺带避开了 `check(isNotBlank())`。）
 *
 * ⚠️ **`label = null` 必须连 `itemPlacer` 一起换掉**（模拟器截图才看出来的）。
 * 两个 placer 在 `maxLabelHeight == 0`（= 没有标签）时的行为都是**特例分支**：
 * - `step()`（默认）跳过防重叠那一步，直接用 `10^(floor(log10(maxY))-1)` 当步长 ——
 *   净值 238 万时步长就是 10 万，**23 条横向网格线**，柱子被条纹糊掉。
 * - `count()` 有一句短路：标签高度为 0 时只返回 `minY` 和 `maxY`，
 *   于是横向网格线整个消失。**这正是想要的** —— 网格线是给标签读数用的，
 *   标签没了它们只剩装饰。竖向的月份网格线来自底部坐标轴，不受影响。
 *
 * 传给 `count` 的数字在这条路径上其实用不到（短路发生在它之前），写 2 是把意图说明白：
 * 只要两端。将来 Vico 去掉那条短路，`count(2)` 仍然是 2 条线，不会退回条纹。
 *
 * **通则：把某个组件设成 null 之前，先查清楚谁在拿它的尺寸算别的东西。**
 */
@Composable
private fun rememberAmountAxis(hideAmounts: Boolean): VerticalAxis<Axis.Position.Vertical.Start> =
    VerticalAxis.rememberStart(
        label = if (hideAmounts) null else rememberAxisLabelComponent(),
        valueFormatter = remember {
            CartesianValueFormatter { _, value, _ -> compactAmountLabel(value) }
        },
        itemPlacer = if (hideAmounts) {
            remember { VerticalAxis.ItemPlacer.count({ AXIS_ENDS_ONLY }) }
        } else {
            remember { VerticalAxis.ItemPlacer.step() }
        },
    )

/** 只要 y 轴两端（最小值和最大值），中间不画网格线。见 [rememberAmountAxis]。 */
private const val AXIS_ENDS_ONLY = 2

// ---------------------------------------------------------------- 纯函数

private fun Money.toYuan(): Double = minorUnits / 100.0

/**
 * 只有一个取样点时，人为让视口"容得下"这么多个 x 单位，柱子就只占其中一格。
 *
 * 取 2 是实机比出来的：Vico 画出的柱宽约等于「视口宽 ÷ (2 × 槽数)」，
 * 槽数取 2 时那根柱子的宽度和**有两个点时**的柱子一模一样 ——
 * 看起来就是"两根里的第一根，第二根还没记"，而不是一块撑满全宽的实心矩形
 * （那是 AGENTS.md 教训 13 里被反馈过的样子）。
 * 槽数再大（试过 6）柱子会细成一条，右边空一大片，反而像画坏了。
 */
private const val SINGLE_POINT_SLOTS = 2.0

/** 幽灵系列每侧的数量，真实系列夹在正中间。见 [TotalColumnChart] 的注释。 */
private const val PHANTOM_COLUMNS_PER_SIDE = 2

/**
 * x 轴标签**跟着 model 一起走**，通过 [ExtraStore] 塞进同一次 `runTransaction`。
 *
 * 修的是一次实机崩溃：**按月切到按季/按年直接闪退**。
 * `modelProducer` 跨 period 切换一直活着，而模型更新是 `LaunchedEffect` 里的
 * **suspend transaction**（还带过渡动画）。切 period 的那一帧，composition 已经拿到
 * 新的 `series`（比如按年只有 1 个点），Vico 手里却还是旧模型（按月 12 个点）——
 * 之前 formatter 直接闭包捕获 `series.dates`，于是被问到越界的 x 时返回空串，
 * 而 Vico 对每个轴标签都有 `check(isNotBlank())`，**空串直接抛异常**。
 *
 * `itemPlacer` 的 spacing/offset 是同一个坑的另一半：`getFirstLabelValue()` 会用
 * `minX + offset * xStep` 去问 formatter，**这个 x 不做范围裁剪**。
 *
 * 判据：**凡是 formatter/ItemPlacer 需要的东西，都必须和数据点在同一个 transaction 里，
 * 不能从 composition 捕获。** 否则"UI 状态"和"图表模型"之间必然有一帧不一致。
 */
private val AxisLabelsKey = ExtraStore.Key<List<String>>()

/** 增长率标签，理由同 [AxisLabelsKey]。 */
private val GrowthLabelsKey = ExtraStore.Key<List<GrowthLabel>>()

private fun <T> ExtraStore.labelCount(key: ExtraStore.Key<List<T>>): Int = getOrNull(key)?.size ?: 0

private fun formatAxisLabel(date: LocalDate, period: Period): String = when (period) {
    Period.MONTH -> "${date.month.ordinal + 1}月"
    Period.QUARTER -> "Q${date.month.ordinal / 3 + 1}"
    Period.YEAR -> date.year.toString()
}

internal fun axisLabels(period: Period, dates: List<LocalDate>): List<String> =
    dates.map { formatAxisLabel(it, period) }

internal fun axisLabels(series: NetWorthSeries): List<String> =
    axisLabels(series.period, series.dates)

internal fun dateLabels(dates: List<LocalDate>): List<String> = dates.map { it.toString() }

/**
 * 标签数量超过这个值就会在一张手机宽度的图上挤到重叠/截断
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

/**
 * 一根柱子相对前一根的增长率标签。
 *
 * [direction] 是 1 / -1 / 0，UI 按它上色 —— **不要去解析 [text] 的正负号**，
 * "算不出来"的占位符和负号长得像但不是一回事。
 */
internal data class GrowthLabel(val text: String, val direction: Int) {
    companion object {
        /**
         * 算不出来：第一根柱子没有前一根，或者上一根 ≤ 0（分母无意义）。
         *
         * **不能是空白串** —— 它会经过 Vico 的 `check(isNotBlank())`。
         * 也不写成 "0%"：那是"没有变化"，和"算不出来"完全是两回事。
         */
        val MISSING = GrowthLabel("—", 0)
    }
}

/** UI 侧上色。红涨绿跌是中国股市语境，见 [com.boomsset.ui.GainLossColors]。 */
private fun GrowthLabel.annotated(rise: Color, fall: Color, neutral: Color): AnnotatedString {
    val color = when {
        direction > 0 -> rise
        direction < 0 -> fall
        else -> neutral
    }
    return buildAnnotatedString { withStyle(SpanStyle(color = color)) { append(text) } }
}

/**
 * 每根柱子相对**前一根**的增长率。
 *
 * 复用 [PortfolioCalculator.growthBp]，和顶部卡片那个"整段区间的净值增长"共用
 * 同一条"期初 ≤ 0 就算不出"的判据 —— 两处各写一遍除法，迟早会一处显示「—」、
 * 另一处显示某个凭空算出来的百分比。
 */
internal fun growthLabels(values: List<Money>): List<GrowthLabel> =
    values.mapIndexed { index, value ->
        val previous = values.getOrNull(index - 1) ?: return@mapIndexed GrowthLabel.MISSING
        val bp = PortfolioCalculator.growthBp(previous.minorUnits, value.minorUnits)
            ?: return@mapIndexed GrowthLabel.MISSING
        // 方向按**四舍五入之后**的那个数判，不是按原始基点：+0.19% 显示出来是 "0%"，
        // 此时再涂成"涨"的红色就自相矛盾（字说没动、颜色说涨了）。颜色跟着看得见的数走。
        val percent = roundToPercent(bp)
        GrowthLabel(text = formatGrowthPercent(bp), direction = percent.compareTo(0))
    }

internal fun growthLabelAt(labels: List<GrowthLabel>?, x: Double): GrowthLabel =
    labels?.getOrNull(x.toInt()) ?: GrowthLabel.MISSING

/**
 * 增长率格式化成**整数百分比**，四舍五入。
 *
 * 不是偷懒：12 根柱子并排时每根只分到二十几 dp，"+12.34%" 放不下会被截断成 "+12…"，
 * 而一个被截断的数字比没有更糟。精确到小数的那个数在顶部卡片里（那里只有一个，放得下）。
 *
 * 四舍五入而不是截断：+0.9% 截断成 "+0%" 会被读成"没动"，而它其实涨了。
 */
internal fun roundToPercent(bp: Int): Int = (bp + if (bp >= 0) 50 else -50) / 100

internal fun formatGrowthPercent(bp: Int): String {
    val rounded = roundToPercent(bp)
    return when {
        rounded > 0 -> "+$rounded%"
        rounded < 0 -> "$rounded%"
        // 真的四舍五入到 0（比如 +0.3%）：给 "0%" 而不是 "+0%"，
        // 后者会让人以为是"涨了一点点但显示不出来"
        else -> "0%"
    }
}

/**
 * 纵轴金额标签：**万/亿**缩写。
 *
 * 和 [com.boomsset.ui.formatCompact] 的阈值一致（中文语境用万/亿，不用 K/M），
 * 但这里的输入是 Vico 给的 Double（元），不是 Money —— 轴上的刻度值本来就是
 * Vico 按范围算出来的，不对应任何一笔真实金额，所以不需要走定点数那条路。
 *
 * 永远不返回空串：Vico 对轴标签有 `check(isNotBlank())`。
 */
internal fun compactAmountLabel(yuan: Double): String {
    val magnitude = abs(yuan)
    val body = when {
        magnitude >= 100_000_000.0 -> "${(magnitude / 100_000_000.0).oneDecimal()}亿"
        magnitude >= 10_000.0 -> "${(magnitude / 10_000.0).oneDecimal()}万"
        else -> magnitude.roundToLong().toString()
    }
    return if (yuan < 0 && body != "0") "-$body" else body
}

/** 保留一位小数，末尾的 .0 去掉。用 Long 算，避免 Double 的科学计数法。 */
private fun Double.oneDecimal(): String {
    val scaled = (this * 10).roundToLong()
    val whole = scaled / 10
    val frac = abs(scaled % 10)
    return if (frac == 0L) whole.toString() else "$whole.$frac"
}

/**
 * 堆叠面积图的累计边界：第 k 条 = 前 k+1 段之和。
 *
 * 画法是每条边界各自填到 0、从最高的那条开始画、后画的盖住前画的，
 * 露出来的那截正好是该类的带子。
 *
 * ⚠️ **要求每一段非负**，否则累计不单调、边界互相穿插，画出来分层是错的。
 * 调用方必须先问 [AllocationSeries.hasNegativeExposure]。
 */
internal fun stackedBands(seriesByClass: List<List<Long>>): List<List<Long>> {
    val running = LongArray(seriesByClass.firstOrNull()?.size ?: 0)
    return seriesByClass.map { values ->
        values.mapIndexed { index, value ->
            running[index] += value
            running[index]
        }
    }
}
