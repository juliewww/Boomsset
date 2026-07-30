package com.boomsset.ui.networth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boomsset.data.SUPPORTED_CURRENCIES
import com.boomsset.security.AppLockUiState
import com.boomsset.security.AuthCapability
import com.boomsset.domain.Money
import com.boomsset.domain.Period
import com.boomsset.ui.bpToPercent
import com.boomsset.ui.formatWithCurrency

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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("当前净值", style = MaterialTheme.typography.labelMedium)
            Text(
                net.formatWithCurrency(state.baseCurrency),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )

            state.series?.growthBp?.let { bp ->
                Text(
                    "净值增长 ${bp.bpToPercent(withSign = true)}（含新增投入）",
                    style = MaterialTheme.typography.bodySmall,
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
                )
                Text(
                    "仅覆盖已填成本的 ${pnl.coveredAssetIds.size} 项资产",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
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

@Composable
private fun EmptyHint() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("还没有资产", style = MaterialTheme.typography.titleMedium)
            Text(
                "点右下角加号记一笔。旺资不记流水 —— 你只需要定期更新每项资产现在值多少。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
