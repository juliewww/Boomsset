package com.boomsset.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp

/**
 * 金额被藏起来时替换掉数字的占位符。
 *
 * **固定长度，不按位数生成。** 用 `"•".repeat(digits)` 会让占位符的宽度随金额的量级变化 ——
 * 七位数和四位数一眼就能分辨，等于把"大概多少钱"这件事漏出去了，而那正是要藏的东西。
 *
 * 不带币种符号：符号本身没有信息量（旁边「查看币种」就写着），
 * 而拼上符号之后 `-¥••••••` 里的负号又会把方向漏出来。
 */
const val MASKED_AMOUNT: String = "••••••"

/**
 * 金额可见时给原文、藏起来时给占位符。
 *
 * 调用方一律走这一个函数，不要各自写 `if (hidden)` —— 漏一处就是漏一个数字，
 * 而"藏了但漏一个"比"根本没藏"更糟：用户以为已经藏好了。
 */
fun maskAmount(hidden: Boolean, text: String): String = if (hidden) MASKED_AMOUNT else text

/**
 * 眼睛图标：点一下切换"金额是否可见"。
 *
 * **图形是手画的，没有引图标依赖。** 项目里的"＋""ⓘ""▾""✓"都是直接写字形，
 * 但眼睛没有能用的字形可写：Unicode 里的 👁 是彩色 emoji（吃不到主题色、
 * 两端字体各画一个样），而"划掉的眼睛"根本没有单码点，只能靠组合字符拼，
 * 落到真机字体上不一定拼得出来。为一个图标引 material-icons-extended 又太重
 * （那个包在 CMP 上还得单独加依赖）。手画的好处是跟着 [LocalContentColor] 走，
 * 深浅色模式和卡片底色都不用另外处理。
 *
 * ⚠️ **Canvas 画的图形不产生无障碍节点**，所以 [contentDescription] 是必须的 ——
 * 不给的话读屏用户听到的只是"按钮"。描述写的是**动作**而不是状态
 * （"隐藏金额"而不是"金额可见"），因为它挂在一个按钮上，读屏读出来的应该是点下去会发生什么。
 * 这也是 UI 测试唯一能定位到它的办法。
 *
 * ⚠️ 图形本身**没有自动化覆盖**（和 AllocationBar 那根竖线同类的缺口）——
 * 改这里的画法必须在真机/模拟器上看一眼。
 */
@Composable
fun AmountVisibilityToggle(hidden: Boolean, onToggle: (Boolean) -> Unit) {
    val color = LocalContentColor.current
    IconButton(
        onClick = { onToggle(!hidden) },
        // ⚠️ 无障碍语义要**整个自己声明**（名字 + 按钮角色 + 点击动作），别只挂一个描述。
        // `IconButton` 把 `clickable` 装在它内部，所以外面挂的语义节点是那个可点节点的
        // **祖先**。逐个试过、每次都拿 `uiautomator dump` 核对（不核对根本看不出来）：
        // - `semantics { contentDescription }`：两个节点，28dp 那个有名字但 `clickable=false`，
        //   48dp 可点的那个没名字 —— 读屏停两次，一次读出名字却点不动，一次只报"按钮"
        // - `semantics(mergeDescendants = true)`：并成了一个 48dp 节点、有名字，
        //   但 `clickable` 仍然是 false（动作没跟着并上来）
        // - `clearAndSetSemantics` + 显式 `onClick`：一个节点，有名字、可点、48dp ✅
        modifier = Modifier
            .size(TOUCH_SIZE)
            .clearAndSetSemantics {
                contentDescription = if (hidden) SHOW_LABEL else HIDE_LABEL
                role = Role.Button
                onClick {
                    onToggle(!hidden)
                    true
                }
            },
    ) {
        Canvas(
            Modifier
                .size(GLYPH_SIZE)
                // 斜杠要在眼睛上"挖"出一条透明的缝（见下面），`BlendMode.Clear` 只有在
                // 自己的离屏图层里才是"擦成透明"，否则会去擦整块卡片底色。
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val strokeWidth = h * STROKE
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

            // 杏仁形的眼眶：上下两条对称的二次贝塞尔。
            // 控制点算的是"让曲线顶点正好落在 cy ± RADIUS"：二次贝塞尔的中点是
            // (P0 + 2·P1 + P2) / 4，两端都在 cy 上，所以 ctrlY = cy ∓ 2·RADIUS。
            // 控制点本身会跑到画布外（被裁掉无所谓），曲线顶点仍在画布内。
            val lens = Path().apply {
                moveTo(0f, cy)
                quadraticTo(w / 2f, cy - 2f * h * RADIUS, w, cy)
                quadraticTo(w / 2f, cy + 2f * h * RADIUS, 0f, cy)
            }
            drawPath(lens, color, style = stroke)
            // 瞳孔**只在"看得见"的状态画**。斜杠是从正中间穿过去的，而瞳孔正好在正中间 ——
            // 两个都画的话瞳孔会被擦成左右两个小碎点（实机放大看确认过），
            // 18dp 的方框里多两个碎点只会更乱。眼眶 + 斜杠已经足够读出"划掉了"。
            if (!hidden) {
                drawCircle(color, radius = h * PUPIL_RADIUS, center = Offset(w / 2f, cy))
            }

            // 藏起来的状态多一条斜杠。**不能只靠"瞳孔填不填"之类的细微差别** ——
            // 两个状态在 18dp 上必须一眼分得开，否则用户不知道现在是藏着还是显示着。
            //
            // ⚠️ 这条斜杠**画错过一次，只有装到设备上放大才看得出来**（实机反馈"图标显示有误"）：
            // 当时是 0.1..0.9 的短斜线，两端正好停在眼眶的曲线上，而中段又和瞳孔粘成一坨 ——
            // 放大之后是一团缠在一起的线，读不出"眼睛被划掉"。两条都要治：
            // 1. **贯穿到角**（0.02..0.98）：两端必须明显伸出眼眶之外，才读作"划掉"，
            //    而不是"眼睛里多了一笔"。
            // 2. **先擦出一条缝再画线**：用 `BlendMode.Clear` 以三倍粗的同一条线擦掉底下的
            //    眼眶和瞳孔，斜杠才是压在眼睛"上面"的一层，而不是和它糊在一起。
            //    Material 的 VisibilityOff 就是这么做的，这条缝是它看起来干净的关键。
            if (hidden) {
                val start = Offset(w * 0.02f, h * 0.98f)
                val end = Offset(w * 0.98f, h * 0.02f)
                drawLine(
                    color = Color.Black, // Clear 模式下颜色不参与运算，只用它的覆盖范围
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth * GAP_RATIO,
                    cap = StrokeCap.Round,
                    blendMode = BlendMode.Clear,
                )
                drawLine(
                    color = color,
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** 和 [InfoTooltip] 的 (i) 一样大 —— 两个都可能出现在同一行，大小不一致会看出来。 */
private val TOUCH_SIZE = 28.dp
private val GLYPH_SIZE = 18.dp

/** 眼眶顶点离中线多远（占高度的比例）。 */
private const val RADIUS = 0.30f
private const val PUPIL_RADIUS = 0.13f
private const val STROKE = 0.085f

/**
 * 斜杠两侧那条缝有多宽 —— 擦除线是笔画的几倍粗。
 *
 * 太小看不出缝，太大会把眼眶啃断成两截不相连的弧（3 倍时实机上就是这样）。
 */
private const val GAP_RATIO = 2.1f

internal const val HIDE_LABEL = "隐藏金额"
internal const val SHOW_LABEL = "显示金额"
