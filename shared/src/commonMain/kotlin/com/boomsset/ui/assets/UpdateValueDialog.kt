package com.boomsset.ui.assets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boomsset.domain.AssetValuation
import com.boomsset.domain.Money
import com.boomsset.domain.Quantity
import com.boomsset.domain.Snapshot
import com.boomsset.ui.formatForInput
import com.boomsset.ui.toMinorUnitsOrNull

/**
 * 更新估值。这是 App 的核心动作 —— 不记流水，只定期回答"这项资产现在值多少"。
 *
 * **关键规则：成本从上一条快照预填。** 快照是完整状态而非增量，用户只改市值时若
 * 成本字段留空，新快照的成本就是 null，收益率会凭空消失。所以两个字段都预填，
 * 让"忘记带上成本"在结构上不会发生。
 */
@Composable
fun UpdateValueDialog(
    valuation: AssetValuation,
    onDismiss: () -> Unit,
    onConfirmManual: (value: Money, costBasis: Money?) -> Unit,
    onConfirmQuoted: (quantity: Quantity, symbol: String, costBasis: Money?) -> Unit,
) {
    val snapshot = valuation.snapshot
    val previousCost = snapshot?.costBasisMinor

    // 预填：市值/份额取当前值，成本取上一条 —— 不留空
    var amountText by remember {
        mutableStateOf(
            when (snapshot) {
                is Snapshot.Manual -> snapshot.value.formatForInput()
                else -> valuation.localValue?.formatForInput() ?: ""
            },
        )
    }
    var quantityText by remember {
        mutableStateOf(
            (snapshot as? Snapshot.Quoted)?.quantity?.formatForInput() ?: "",
        )
    }
    var costText by remember { mutableStateOf(previousCost?.formatForInput() ?: "") }

    val isQuoted = snapshot is Snapshot.Quoted
    val amount = amountText.toMinorUnitsOrNull()
    val quantity = quantityText.toQuantityOrNull()
    val cost = costText.takeIf { it.isNotBlank() }?.toMinorUnitsOrNull()
    val costInvalid = costText.isNotBlank() && cost == null

    val canConfirm = if (isQuoted) quantity != null && !costInvalid
    else amount != null && !costInvalid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新「${valuation.asset.name}」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isQuoted) {
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        label = { Text("持有份额") },
                        singleLine = true,
                        isError = quantityText.isNotBlank() && quantity == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "市值由份额 × 行情单价算出，改不了。加仓减仓就是改这里的份额。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = {
                            Text(if (valuation.asset.isLiability) "当前欠款（元）" else "当前市值（元）")
                        },
                        singleLine = true,
                        isError = amountText.isNotBlank() && amount == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (!valuation.asset.isLiability) {
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = { Text("总投入成本（元）") },
                        singleLine = true,
                        isError = costInvalid,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (isQuoted) {
                            "如果这次是加仓，记得把新投入的钱加进总成本 —— " +
                                "份额涨了成本没涨，收益率会虚高。"
                        } else {
                            "已带出上次填的成本。改动市值不会影响它，除非你也改这里。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Text(
                    "这会新增一条快照，历史记录不会被改写。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    if (isQuoted) {
                        onConfirmQuoted(
                            quantity!!,
                            // isQuoted 已经保证了类型，智能转换在这里成立
                            snapshot.quoteSymbol,
                            cost?.let { Money(it) },
                        )
                    } else {
                        onConfirmManual(Money(amount!!), cost?.let { Money(it) })
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 份额字符串 → 定点整数（scale = 8）。
 *
 * 和金额一样手工解析，不走 Double —— 份额要参与「份额 × 单价」算市值。
 * 超过 8 位小数判为非法，不静默截断。
 */
internal fun String.toQuantityOrNull(): Quantity? {
    val text = trim()
    if (text.isEmpty()) return null
    if (text.startsWith('-')) return null  // 份额不能为负

    val parts = text.removePrefix("+").split('.')
    if (parts.size > 2) return null

    val wholePart = parts[0].ifEmpty { "0" }
    if (!wholePart.all { it.isDigit() }) return null

    val fracPart = parts.getOrNull(1) ?: ""
    if (!fracPart.all { it.isDigit() } || fracPart.length > Quantity.SCALE) return null

    val whole = wholePart.toLongOrNull() ?: return null
    val frac = fracPart.padEnd(Quantity.SCALE, '0').toLongOrNull() ?: return null

    if (whole > (Long.MAX_VALUE - frac) / Quantity.ONE) return null
    return Quantity(whole * Quantity.ONE + frac)
}
