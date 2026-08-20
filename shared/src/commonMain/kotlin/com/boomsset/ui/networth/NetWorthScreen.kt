package com.boomsset.ui.networth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boomsset.data.SUPPORTED_CURRENCIES
import com.boomsset.security.AppLockUiState
import com.boomsset.security.AuthCapability
import com.boomsset.domain.Money
import com.boomsset.domain.Period
import com.boomsset.ui.bpToPercent
import com.boomsset.ui.currencySymbol
import com.boomsset.ui.fallColor
import com.boomsset.ui.formatWithCurrency
import com.boomsset.ui.riseColor

@Composable
fun NetWorthScreen(
    state: NetWorthUiState,
    onSelectPeriod: (Period) -> Unit,
    onSelectBaseCurrency: (String) -> Unit,
    lockState: AppLockUiState,
    onToggleLock: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            state.loading -> Text("加载中…", style = MaterialTheme.typography.bodyMedium)

            state.isEmpty -> {
                EmptyHint()
                // 空状态下也要能开应用锁 —— 提前 return 会砍掉这个入口，
                // 这是 AGENTS.md 里那条「空状态不要用提前 return」的教训
                AppLockToggle(lockState, onToggleLock)
            }

            else -> {
                SummaryCard(state)
                BaseCurrencySelector(state.baseCurrency, onSelectBaseCurrency)
                PeriodSelector(state.period, onSelectPeriod)
                state.series?.let { NetWorthChart(it) }
                if (state.unpricedCount > 0) UnpricedWarning(state.unpricedCount)
                GrowthVsReturnNote()
                AppLockToggle(lockState, onToggleLock)
            }
        }
    }
}

@Composable
private fun SummaryCard(state: NetWorthUiState) {
    val net = state.series?.latest?.netWorth ?: Money.ZERO
    // 只是"划一下别让旁边的人看到具体数字"的临时遮挡，不是账号级别的隐私设置 ——
    // 不落 DataStore，重开 App 默认又是可见的，免得用户忘记自己藏起来过、
    // 以为净值消失了。用 rememberSaveable 只是为了转屏之类的配置变化不重置。
    var netWorthVisible by rememberSaveable { mutableStateOf(true) }

    // 卡片用品牌色的浅色容器打底 —— 反馈是净值页太灰暗；整页只有这一处用容器强调，
    // 不会和"表面是中性白灰"的整体设计冲突（见 AGENTS.md）。
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "当前净值",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                IconButton(
                    onClick = { netWorthVisible = !netWorthVisible },
                    modifier = Modifier.size(28.dp).semantics {
                        contentDescription = if (netWorthVisible) "隐藏净值" else "显示净值"
                    },
                ) {
                    EyeIcon(
                        open = netWorthVisible,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                if (netWorthVisible) {
                    net.formatWithCurrency(state.baseCurrency)
                } else {
                    "${currencySymbol(state.baseCurrency)}****"
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            state.series?.growthBp?.let { bp ->
                Text(
                    "净值增长 ${bp.bpToPercent(withSign = true)}（含新增投入）",
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        bp > 0 -> riseColor()
                        bp < 0 -> fallColor()
                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                    },
                )
            }

            // 浮动盈亏和净值增长是两个不同口径，标签必须写清 —— 见 docs/domain.md
            val pnl = state.pnl
            if (pnl != null && pnl.hasCoverage) {
                val rate = pnl.pnl.returnBp
                Text(
                    buildString {
                        append("浮动盈亏 ")
                        append(pnl.pnl.absolute.formatWithCurrency(state.baseCurrency))
                        if (rate != null) append("（${rate.bpToPercent(withSign = true)}）")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        pnl.pnl.absolute.minorUnits > 0 -> riseColor()
                        pnl.pnl.absolute.minorUnits < 0 -> fallColor()
                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                    },
                )
                Text(
                    "仅覆盖已填成本的 ${pnl.coveredAssetIds.size} 项资产",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * 手绘的眼睛图标，不是 Material Icons —— 项目没有引入图标库依赖
 * （配置页 `InfoTooltip` 的 "ⓘ" 用的是同样的取舍：只为一个图标加一个新依赖
 * 不划算，还要额外核实它有没有 iOS artifact，见 AGENTS.md 硬约束 5）。
 * 眼形是两条二次贝塞尔曲线拼成的"杏仁形"轮廓，睁眼时中间多画一个瞳孔圆点，
 * 闭眼时同一个轮廓上加一条对角线划掉。
 */
@Composable
private fun EyeIcon(open: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val eyeOutline = Path().apply {
            moveTo(0f, h / 2)
            quadraticTo(w / 2, -h * 0.1f, w, h / 2)
            quadraticTo(w / 2, h * 1.1f, 0f, h / 2)
            close()
        }
        val strokeWidth = w * 0.09f
        drawPath(eyeOutline, color = tint, style = Stroke(width = strokeWidth))
        if (open) {
            drawCircle(color = tint, radius = h * 0.2f, center = Offset(w / 2, h / 2))
        } else {
            drawLine(
                color = tint,
                start = Offset(w * 0.08f, h * 0.08f),
                end = Offset(w * 0.92f, h * 0.92f),
                strokeWidth = strokeWidth,
            )
        }
    }
}

/**
 * 应用锁开关。
 *
 * 不可用时**说明原因并禁用**，而不是让用户开了之后发现进不去 ——
 * 「没录入」是去系统设置能解决的，「不支持」是无解的，两者要说清区别。
 */
@Composable
private fun AppLockToggle(lockState: AppLockUiState, onToggle: (Boolean) -> Unit) {
    val canUse = lockState.capability == AuthCapability.AVAILABLE
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("应用锁", style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = lockState.lockEnabled,
                    onCheckedChange = onToggle,
                    enabled = canUse || lockState.lockEnabled,
                )
            }
            Text(
                when (lockState.capability) {
                    AuthCapability.AVAILABLE ->
                        "开启后每次打开旺资都需要验证身份。开启时会先验一次。"
                    AuthCapability.NOT_ENROLLED ->
                        "这台设备还没设锁屏密码或生物识别 —— 去系统设置里加上就能用了。"
                    AuthCapability.NO_HARDWARE ->
                        "这台设备不支持生物识别，也没有锁屏密码可用。"
                    AuthCapability.TEMPORARILY_UNAVAILABLE ->
                        "验证暂时不可用（可能是多次失败被锁定），稍后再试。"
                },
                style = MaterialTheme.typography.labelSmall,
            )
            lockState.lastError?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun BaseCurrencySelector(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("以哪种币种查看", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SUPPORTED_CURRENCIES.forEach { code ->
                FilterChip(
                    selected = code == selected,
                    onClick = { onSelect(code) },
                    label = { Text(code) },
                )
            }
        }
        Text(
            "只改变展示口径。已记录的金额和币种一个都不会被改写。",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun PeriodSelector(selected: Period, onSelect: (Period) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Period.entries.forEach { period ->
            FilterChip(
                selected = period == selected,
                onClick = { onSelect(period) },
                label = { Text(period.label()) },
            )
        }
    }
}

private fun Period.label(): String = when (this) {
    Period.MONTH -> "按月"
    Period.QUARTER -> "按季"
    Period.YEAR -> "按年"
}

@Composable
private fun UnpricedWarning(count: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$count 项资产无法估值", style = MaterialTheme.typography.titleSmall)
            Text(
                "可能是缺行情/汇率，也可能是份额或价格的数量级超出了可计算范围。" +
                    "这些资产**没有**计入上面的净值 —— 不按 0 计算，是为了避免静默低估。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GrowthVsReturnNote() {
    Text(
        "「净值增长」包含你新存进去的钱，「浮动盈亏」才反映投资本身的表现。",
        style = MaterialTheme.typography.labelSmall,
    )
}

/**
 * 空状态的上手指引。
 *
 * 原来只有一句"点右下角加号"。问题是**新用户不知道这个 App 的工作方式**：
 * 它不记流水、记快照，这跟大部分记账 App 相反 —— 不先说清楚，用户会按记流水的
 * 预期去用，然后觉得功能缺失。所以这里把三步说完，并且指出配置可以先设。
 *
 * 刻意保持短：三条各一行。空状态放长篇说明没人看。
 */
@Composable
private fun EmptyHint() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("还没有资产", style = MaterialTheme.typography.titleMedium)
            Text(
                "旺资不记流水，记的是快照 —— 你不用逐笔录收支，只要定期更新每项资产现在值多少。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Step("1", "去「资产」页点右下角加号，添加一项资产（存款、基金、股票、房产都行）")
            Step("2", "以后每月或每季回来更新一次市值，净值曲线就长出来了")
            Step("3", "去「配置」页设定目标比例，就能看到自己离目标有多远")
            Text(
                "现在就可以先去「配置」页看看内置的几套目标比例 —— 那一页不需要有资产也能用。",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            number,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
