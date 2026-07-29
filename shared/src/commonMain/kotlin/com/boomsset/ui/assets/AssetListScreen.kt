package com.boomsset.ui.assets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boomsset.domain.AssetClass
import com.boomsset.domain.AssetValuation
import com.boomsset.domain.Money
import com.boomsset.domain.Quantity
import com.boomsset.ui.bpToPercent
import com.boomsset.ui.formatWithCurrency

@Composable
fun AssetListScreen(
    state: AssetListUiState,
    onUpdateManual: (assetId: Long, value: Money, costBasis: Money?) -> Unit,
    onUpdateQuoted: (assetId: Long, quantity: Quantity, symbol: String, costBasis: Money?) -> Unit,
    onArchive: (assetId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var updating by remember { mutableStateOf<AssetValuation?>(null) }
    var archiving by remember { mutableStateOf<AssetValuation?>(null) }

    if (state.loading) {
        Text("加载中…", modifier = modifier.padding(16.dp))
        return
    }
    if (state.isEmpty) {
        Column(
            modifier = modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "没有在持资产。去「净值」页点加号添加。",
                style = MaterialTheme.typography.bodyMedium,
            )
            // 全部归档后如果不提这一句，用户会以为数据丢了
            if (state.archivedCount > 0) {
                Text(
                    "另有 ${state.archivedCount} 项已归档 —— 数据没丢，" +
                        "它们的历史仍计入净值曲线，只是不再持有。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        item {
            Text(
                "点任一项更新它现在值多少。这是这个 App 的核心动作 —— 不记流水，只记快照。",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        AssetClass.displayOrder.forEach { assetClass ->
            val rows = state.grouped[assetClass].orEmpty()
            if (rows.isEmpty()) return@forEach

            item(key = "header-$assetClass") {
                Text(
                    assetClass.label(),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(rows, key = { it.asset.id }) { valuation ->
                AssetRow(
                    valuation = valuation,
                    baseCurrency = state.baseCurrency,
                    onClick = { updating = valuation },
                    onLongClick = { archiving = valuation },
                )
            }
        }

        if (state.archivedCount > 0) {
            item {
                Text(
                    "另有 ${state.archivedCount} 项已归档 —— 它们的历史仍计入净值曲线，" +
                        "但不再持有。",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

    updating?.let { valuation ->
        UpdateValueDialog(
            valuation = valuation,
            onDismiss = { updating = null },
            onConfirmManual = { value, cost ->
                onUpdateManual(valuation.asset.id, value, cost)
                updating = null
            },
            onConfirmQuoted = { quantity, symbol, cost ->
                onUpdateQuoted(valuation.asset.id, quantity, symbol, cost)
                updating = null
            },
        )
    }

    archiving?.let { valuation ->
        ArchiveConfirmDialog(
            name = valuation.asset.name,
            onDismiss = { archiving = null },
            onConfirm = {
                onArchive(valuation.asset.id)
                archiving = null
            },
        )
    }
}

@Composable
private fun AssetRow(
    valuation: AssetValuation,
    baseCurrency: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(valuation.asset.name, style = MaterialTheme.typography.titleSmall)
                    if (valuation.asset.isLiability) {
                        Text("负债", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    when {
                        valuation.hasNoSnapshot -> "未录入"
                        valuation.baseValue == null -> "无法估值"
                        else -> valuation.baseValue.formatWithCurrency(baseCurrency)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // 无法估值时明确说原因，不显示成 0 —— 显示 0 会让用户以为资产没了
            if (valuation.isUnpriced) {
                Text(
                    "无法估值（缺行情/汇率，或数量级超出可计算范围），这项没有计入净值",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            valuation.pnl?.let { pnl ->
                val rate = pnl.returnBp
                Text(
                    buildString {
                        append("盈亏 ")
                        append(pnl.absolute.formatWithCurrency(valuation.asset.currency))
                        if (rate != null) append("（${rate.bpToPercent(withSign = true)}）")
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            valuation.unitCost?.let { unitCost ->
                Text(
                    "成本均价 ${unitCost.formatWithCurrency(valuation.asset.currency)}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (!valuation.asset.includeInAllocation) {
                Text("不计入配置比例", style = MaterialTheme.typography.labelSmall)
            }

            TextButton(onClick = onLongClick) { Text("归档") }
        }
    }
}

@Composable
private fun ArchiveConfirmDialog(
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("归档「$name」？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("归档不是删除 —— 历史快照会保留，过去的净值曲线不变。")
                Text(
                    "⚠️ 如果这笔钱转到了别处（比如卖出后进了活期），记得去更新那项资产。" +
                        "否则总净值会凭空少一笔 —— 那是你没经历过的亏损。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("归档") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun AssetClass.label(): String = when (this) {
    AssetClass.LIQUID -> "流动资金"
    AssetClass.FIXED_INCOME -> "固定收益"
    AssetClass.EQUITY -> "权益类"
    AssetClass.ALTERNATIVE -> "另类实物"
    AssetClass.PROTECTION -> "保障类"
}
