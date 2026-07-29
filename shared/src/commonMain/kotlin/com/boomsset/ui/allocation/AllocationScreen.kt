package com.boomsset.ui.allocation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boomsset.domain.AllocationView
import com.boomsset.domain.AssetClass
import com.boomsset.domain.TargetAllocation
import com.boomsset.ui.bpToPercent
import com.boomsset.ui.formatWithCurrency

@Composable
fun AllocationScreen(
    state: AllocationUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val view = state.view
        when {
            state.loading -> Text("加载中…", style = MaterialTheme.typography.bodyMedium)
            view == null || state.isEmpty -> Text(
                "还没有资产，先去「净值」页添加。",
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> {
                Header(view)

                // 净资产 ≤ 0 时比例在数学上无意义，直说而不是显示乱数
                if (view.netWorth.minorUnits <= 0L) {
                    NegativeNetWorthNotice()
                } else {
                    AssetClass.displayOrder.forEach { assetClass ->
                        ClassRow(view, assetClass)
                    }
                    if (view.hasNegativeExposure) NegativeExposureNotice()
                }

                DenominatorNote()
            }
        }
    }
}

@Composable
private fun Header(view: AllocationView) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("资产配置", style = MaterialTheme.typography.titleLarge)
        Text(
            "净资产 ${view.netWorth.formatWithCurrency(view.baseCurrency)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            view.target?.let { "对比目标：${it.name}" } ?: "尚未设定目标配置",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ClassRow(view: AllocationView, assetClass: AssetClass) {
    val exposure = view.exposures[assetClass] ?: return
    val shareBp = view.shareBp(assetClass)
    val targetBp = view.targetBp(assetClass)
    val deviationBp = view.deviationBp(assetClass)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(assetClass.label(), style = MaterialTheme.typography.titleSmall)
                Text(
                    shareBp?.bpToPercent() ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // 进度条画不出负数，负敞口按 0 长度显示，真实值在下面文字里
            LinearProgressIndicator(
                progress = {
                    val bp = shareBp ?: 0
                    (bp.coerceAtLeast(0).toFloat() / TargetAllocation.TOTAL_BP).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "净敞口 ${exposure.netExposure.formatWithCurrency(view.baseCurrency)}" +
                    if (!exposure.liabilities.isZero) {
                        "（资产 ${exposure.assets.formatWithCurrency(view.baseCurrency)} " +
                            "− 负债 ${exposure.liabilities.formatWithCurrency(view.baseCurrency)}）"
                    } else "",
                style = MaterialTheme.typography.labelSmall,
            )

            if (targetBp != null && deviationBp != null) {
                Text(
                    "目标 ${targetBp.bpToPercent(decimals = 0)}，" +
                        if (deviationBp == 0) "已达标"
                        else "${if (deviationBp > 0) "超配" else "低配"} " +
                            "${kotlin.math.abs(deviationBp).bpToPercent()}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

private fun AssetClass.label(): String = when (this) {
    AssetClass.LIQUID -> "流动资金"
    AssetClass.FIXED_INCOME -> "固定收益"
    AssetClass.EQUITY -> "权益类"
    AssetClass.ALTERNATIVE -> "另类实物"
    AssetClass.PROTECTION -> "保障类"
}

@Composable
private fun NegativeNetWorthNotice() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("净资产为负", style = MaterialTheme.typography.titleSmall)
            Text(
                "负债超过资产，配置比例无法计算（分母为负）。先看净值页了解构成。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun NegativeExposureNotice() {
    Text(
        "有大类的净敞口为负（负债超过该类资产），上面的进度条按 0 显示，真实数值见每项的净敞口。",
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun DenominatorNote() {
    Text(
        "比例的分母是全部净资产（含自住房）。各大类显示的是净敞口 —— " +
            "归属到该类的负债已经抵扣，所以比例加总为 100%。",
        style = MaterialTheme.typography.labelSmall,
    )
}
