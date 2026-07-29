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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boomsset.data.SUPPORTED_CURRENCIES
import com.boomsset.domain.Money
import com.boomsset.domain.Period
import com.boomsset.ui.bpToPercent
import com.boomsset.ui.formatWithCurrency

@Composable
fun NetWorthScreen(
    state: NetWorthUiState,
    onSelectPeriod: (Period) -> Unit,
    onSelectBaseCurrency: (String) -> Unit,
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

            state.isEmpty -> EmptyHint()

            else -> {
                SummaryCard(state)
                BaseCurrencySelector(state.baseCurrency, onSelectBaseCurrency)
                PeriodSelector(state.period, onSelectPeriod)
                state.series?.let { NetWorthChart(it) }
                if (state.unpricedCount > 0) UnpricedWarning(state.unpricedCount)
                GrowthVsReturnNote()
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
                "缺少行情或汇率数据。这些资产**没有**计入上面的净值 —— " +
                    "不按 0 计算，是为了避免静默低估。",
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
