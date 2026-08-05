package com.boomsset.ui.allocation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boomsset.domain.AssetClass
import com.boomsset.domain.Money
import com.boomsset.ui.formatWithCurrency
import com.boomsset.ui.theme.chartColors
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.pie.PieChart
import com.patrykandpatrick.vico.compose.pie.PieChartHost
import com.patrykandpatrick.vico.compose.pie.PieSize
import com.patrykandpatrick.vico.compose.pie.data.PieChartModelProducer
import com.patrykandpatrick.vico.compose.pie.data.pieSeries
import com.patrykandpatrick.vico.compose.pie.rememberPieChart

/**
 * 资产配置的环形占比图。
 *
 * 是 [ClassRow] 那组进度条的**补充**，不是替代 —— 条形回答"每一类偏离目标多少"，
 * 这个图回答"现在整体是怎么分的"，一眼看比例更直接。所以画在同一屏里，
 * 用的还是 [chartColors] 那五个固定顺序的大类色，不重新定义配色。
 *
 * 圆环中间没有留给它自己的图例文字 —— 名称和百分比 [ClassRow] 已经写了，
 * 图例文字重复一遍是纯粹的信息冗余（配置页第一版就因为重复了一遍"对比目标"被反馈过）。
 * 中间挖空的洞用来放净资产总额，算是给这块空间一个用处。
 *
 * @param shares 各大类的**净敞口**，按 [AssetClass.displayOrder] 传入，顺序必须对应
 *   [chartColors] 的顺序（[PieChart.SliceProvider.series] 是按下标配对颜色的，
 *   传错顺序不会报错，只会**颜色和大类对不上**，属于那种编译期发现不了的错误）。
 * @param netWorth 圆环中心显示的总额。
 */
@Composable
fun AllocationDonut(
    shares: List<Pair<AssetClass, Money>>,
    netWorth: Money,
    baseCurrency: String,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { PieChartModelProducer() }

    // Pie 的 Entry 要求非负 —— 负净敞口（该类负债超过资产）画不成一个扇区，
    // 和 ClassRow 的进度条一个道理：真实数值已经在文字里显示了，这里只负责占比形状。
    val values = shares.map { (_, money) -> money.minorUnits.coerceAtLeast(0L).toFloat() }

    LaunchedEffect(values) {
        if (values.all { it == 0f }) return@LaunchedEffect
        modelProducer.runTransaction {
            pieSeries { series(values) }
        }
    }

    // `chartColors` 是个 @Composable 属性（读 CompositionLocal），不能在 remember{}
    // 的 lambda 里调用 —— 那个 lambda 带 @DisallowComposableCalls。所以先在
    // 组合作用域里取出普通值，再拿普通值去构建 Slice 列表。
    //
    // 这里没有额外套 remember：Slice 没有覆盖 equals/hashCode，是按引用比较的，
    // 就算包一层 remember，只要 shares 变了就会创建新实例，缓存也命不中 ——
    // 索性直接算，逻辑更直白，多余的 remember 只是心理安慰。
    val colors = chartColors
    val slices = shares.map { (assetClass, _) -> PieChart.Slice(fill = Fill(colors.of(assetClass))) }
    val pieChart = rememberPieChart(
        sliceProvider = PieChart.SliceProvider.series(slices),
        innerSize = PieSize.Inner.fixed(64.dp),
    )

    Box(modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
        PieChartHost(
            chart = pieChart,
            modelProducer = modelProducer,
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
        Text(
            netWorth.formatWithCurrency(baseCurrency),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
