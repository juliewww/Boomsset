package com.boomsset.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * (i) 图标 + 点按弹出的说明气泡。
 *
 * 用来把那些一次性看不懂、但**不需要常驻**的解释文字收起来 ——
 * 配置页的"对比哪套目标""内置预设是行业常见的起点……"、净值页的"切换币种只改展示口径"
 * 这类句子常驻显示，占地方还啰嗦（实机反馈）。`TooltipBox` 默认是长按/悬停触发，这里手动在
 * `onClick` 里调 `state.show()`，因为触屏上点一下比长按更符合"点 (i) 看说明"的直觉，
 * 而且配置页已经把"长按"用在了目标 chip 上 ——
 * 同一屏里不该有两种手势各自绑着不同含义。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InfoTooltip(text: String) {
    // isPersistent = true：默认的气泡 **1.5 秒就自己消失**（实机连拍确认），
    // 而这里装的是三四行中文，读完要好几秒 —— 主动点开的说明必须等用户点别处才收，
    // 不然等于把文字藏进了一个来不及看的地方。
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = tooltipState,
    ) {
        IconButton(
            onClick = { scope.launch { tooltipState.show() } },
            modifier = Modifier.size(28.dp),
        ) {
            Text("ⓘ", style = MaterialTheme.typography.labelMedium)
        }
    }
}
