package com.boomsset.ui.allocation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.boomsset.domain.TargetAllocation
import com.boomsset.ui.theme.chartColors

private val TRACK_HEIGHT = 10.dp
private val MARKER_WIDTH = 3.dp
/** 竖线上下各探出轨道这么多 —— 完全齐平的话，压在填充色上几乎看不出来。 */
private val MARKER_OVERHANG = 3.dp
/** 竖线外面那圈浅色描边的单边宽度。 */
private val MARKER_HALO = 1.dp

/**
 * 配置比例的条形：**填充 = 一个比例，竖线 = 另一个比例。**
 *
 * 替掉了原来的 `LinearProgressIndicator` —— 它画不了第二个标记，而"当前在哪、
 * 目标在哪"要放在同一根条上才比得出来（不然目标只是下面一行字，得自己在脑子里换算）。
 *
 * ## 量程固定 0~100%，不按行自适应
 *
 * 跨行可比是这根条唯一的价值：五行用同一把尺子，「另类 71%」的条就该比「保障 10%」长。
 * 自适应量程会让两者一样长，条形就退化成纯装饰。代价是目标 5%、当前 2% 这种小数值
 * 在条上只差十几 dp，分辨勉强 —— 精确数字在同一张卡片的文字里，这个取舍是有意的。
 *
 * ## 竖线的含义**不能只靠图形传达**
 *
 * 每一行都同时显示「目标 X%」的文字（见 `ClassRow`），竖线只是让它可扫视。
 * 这是 [com.boomsset.ui.theme.ChartColors] 那条硬规则的延续：身份和数值不能只靠
 * 颜色或图形编码。**改版式时不要把那些文字去掉。**
 *
 * 没有挂无障碍语义（`LinearProgressIndicator` 自带的那份进度语义因此丢了）：
 * 这一行的当前占比 / 目标 / 偏离 / 折算金额四个数都已经是可朗读的文字，
 * 再给条形加一份描述只会把同样的信息读两遍。
 *
 * @param fillBp 填充到哪个比例（基点）。null 或负数按 0 处理 —— 负净敞口画不出长度，
 *   真实数值由调用方在文字里给出。
 * @param markerBp 竖线画在哪个比例（基点）。null 就不画（零资产预览时填充本身就是目标，
 *   再画一根重合的竖线是重复）。
 */
@Composable
fun AllocationBar(
    fillBp: Int?,
    markerBp: Int?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val trackColor = chartColors.track
    // 描边取卡片底色，让竖线在填充色上也能读出来。用 surface 而不是固定白色：
    // 深色模式下白描边会比竖线本身更抢眼。
    val haloColor = MaterialTheme.colorScheme.surface
    val markerColor = MaterialTheme.colorScheme.onSurface

    val fillFraction = ((fillBp ?: 0).coerceAtLeast(0).toFloat() / TargetAllocation.TOTAL_BP)
        .coerceIn(0f, 1f)
    val markerFraction = markerBp
        ?.let { (it.toFloat() / TargetAllocation.TOTAL_BP).coerceIn(0f, 1f) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT + MARKER_OVERHANG * 2),
    ) {
        val trackH = TRACK_HEIGHT.toPx()
        val top = (size.height - trackH) / 2f
        val radius = CornerRadius(trackH / 2f)

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, top),
            size = Size(size.width, trackH),
            cornerRadius = radius,
        )

        if (fillFraction > 0f) {
            drawRoundRect(
                color = color,
                topLeft = Offset(0f, top),
                size = Size(size.width * fillFraction, trackH),
                cornerRadius = radius,
            )
        }

        markerFraction?.let { fraction ->
            val markerW = MARKER_WIDTH.toPx()
            val haloW = markerW + MARKER_HALO.toPx() * 2
            // 夹住两端，目标 0% / 100% 时竖线也要整根留在条内而不是被裁掉一半
            val centerX = (size.width * fraction).coerceIn(haloW / 2f, size.width - haloW / 2f)
            val markerTop = top - MARKER_OVERHANG.toPx()
            val markerH = trackH + MARKER_OVERHANG.toPx() * 2

            drawRoundRect(
                color = haloColor,
                topLeft = Offset(centerX - haloW / 2f, markerTop),
                size = Size(haloW, markerH),
                cornerRadius = CornerRadius(haloW / 2f),
            )
            drawRoundRect(
                color = markerColor,
                topLeft = Offset(centerX - markerW / 2f, markerTop),
                size = Size(markerW, markerH),
                cornerRadius = CornerRadius(markerW / 2f),
            )
        }
    }
}
