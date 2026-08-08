package com.boomsset.ui.allocation

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.boomsset.domain.AssetClass
import com.boomsset.domain.Money
import com.boomsset.ui.formatWithCurrency
import com.boomsset.ui.label
import com.boomsset.ui.theme.chartColors
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.pie.PieChart
import com.patrykandpatrick.vico.compose.pie.PieChartHost
import com.patrykandpatrick.vico.compose.pie.PieSize
import com.patrykandpatrick.vico.compose.pie.data.PieChartModelProducer
import com.patrykandpatrick.vico.compose.pie.data.pieSeries
import com.patrykandpatrick.vico.compose.pie.rememberPieChart
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

private const val START_ANGLE_DEG = -90f
private val INNER_RADIUS = 64.dp

/**
 * 资产配置的环形占比图，扇区**可点**。
 *
 * 是 [ClassRow] 那组进度条的**补充**，不是替代 —— 条形回答"每一类偏离目标多少"，
 * 这个图回答"现在整体是怎么分的"，一眼看比例更直接。所以画在同一屏里，
 * 用的还是 [chartColors] 那五个固定顺序的大类色，不重新定义配色。
 *
 * 圆环中间默认显示净资产总额；**点一个扇区换成显示那一类的名称和金额**（实机反馈：
 * 光看颜色和角度猜不出具体是多少钱），再点一次收起。
 *
 * Vico 的 `PieChart` 没有内置的点击回调，所以命中检测是手写的：拿到 [Box] 的实际
 * 像素尺寸（[androidx.compose.ui.layout.onSizeChanged]），把点击坐标换算成相对圆心的
 * 角度和半径，再用 [values] 累加出的角度区间去判断落在哪个扇区。角度换算用的是
 * Android/Vico 共用的画布约定：`atan2(dy, dx)` 在屏幕坐标系（y 向下）里直接就是
 * "从 3 点钟方向顺时针量"的角度，不需要额外翻转符号 —— 这个约定和 [rememberPieChart]
 * 的 `startAngle` 参数是同一套，所以这里显式传 `startAngle = -90f`（12 点钟方向起画）
 * 而不是依赖库内部默认值，命中检测的角度基准才能保证和实际渲染完全一致。
 *
 * @param shares 各大类的**净敞口**，按 [AssetClass.displayOrder] 传入，顺序必须对应
 *   [chartColors] 的顺序（[PieChart.SliceProvider.series] 是按下标配对颜色的，
 *   传错顺序不会报错，只会**颜色和大类对不上**，属于那种编译期发现不了的错误）。
 * @param netWorth 圆环中心默认显示的总额。
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
    val total = values.sum()

    LaunchedEffect(values) {
        if (total == 0f) return@LaunchedEffect
        modelProducer.runTransaction {
            pieSeries { series(values) }
        }
    }

    // `chartColors` 是个 @Composable 属性（读 CompositionLocal），不能在 remember{}
    // 的 lambda 里调用 —— 那个 lambda 带 @DisallowComposableCalls。所以先在
    // 组合作用域里取出普通值，再拿普通值去构建 Slice 列表。
    val colors = chartColors
    val slices = shares.map { (assetClass, _) -> PieChart.Slice(fill = Fill(colors.of(assetClass))) }
    val pieChart = rememberPieChart(
        sliceProvider = PieChart.SliceProvider.series(slices),
        innerSize = PieSize.Inner.fixed(INNER_RADIUS),
        startAngle = START_ANGLE_DEG,
    )

    var selected by remember(shares) { mutableStateOf<AssetClass?>(null) }
    var boxSizePx by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(
        modifier
            .fillMaxWidth()
            .height(180.dp)
            .onSizeChanged { boxSizePx = it }
            .pointerInput(shares, total) {
                if (total == 0f) return@pointerInput
                detectTapGestures { offset ->
                    val hit = hitTestSlice(offset, boxSizePx, density, values, total)
                    selected = if (hit != null) shares.getOrNull(hit)?.first else null
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        PieChartHost(
            chart = pieChart,
            modelProducer = modelProducer,
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
        val selectedShare = selected?.let { assetClass -> shares.firstOrNull { it.first == assetClass } }
        if (selectedShare != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(selectedShare.first.label(), style = MaterialTheme.typography.labelMedium)
                Text(
                    selectedShare.second.formatWithCurrency(baseCurrency),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        } else {
            Text(
                netWorth.formatWithCurrency(baseCurrency),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** 返回命中的扇区在 [values] 里的下标；洞里、圆外或点在缝隙上都算没命中。 */
private fun hitTestSlice(
    offset: Offset,
    boxSize: IntSize,
    density: Density,
    values: List<Float>,
    total: Float,
): Int? {
    if (boxSize.width == 0 || boxSize.height == 0) return null
    val cx = boxSize.width / 2f
    val cy = boxSize.height / 2f
    val dx = offset.x - cx
    val dy = offset.y - cy
    val dist = sqrt(dx * dx + dy * dy)

    val outerRadius = min(boxSize.width, boxSize.height) / 2f
    val innerRadius = with(density) { INNER_RADIUS.toPx() }
    if (dist < innerRadius || dist > outerRadius) return null

    val rawDeg = atan2(dy, dx) * 180f / kotlin.math.PI.toFloat()
    var relative = (rawDeg - START_ANGLE_DEG) % 360f
    if (relative < 0f) relative += 360f

    var cumulative = 0f
    values.forEachIndexed { index, value ->
        val sweep = value / total * 360f
        if (relative < cumulative + sweep) return index
        cumulative += sweep
    }
    return null
}
