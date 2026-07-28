package com.boomsset.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boomsset.domain.AssetClass
import com.boomsset.domain.AssetSubtype
import com.boomsset.domain.Money

/**
 * 添加资产。
 *
 * v1 的简化：品种（subtype）自动取该大类下的第一个内置品种，不让用户选。
 * 品种主要服务记账归类，不影响净值和配置算法，所以这个简化不会算错数 ——
 * 完整的品种选择器留到资产管理页做。
 *
 * 只支持 MANUAL 模式。QUOTED 需要行情代码和取价链路，等 Ktor 接入后再开。
 */
@Composable
fun AddAssetDialog(
    subtypes: List<AssetSubtype>,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        assetClass: AssetClass,
        subtypeId: Long,
        value: Money,
        costBasis: Money?,
        isLiability: Boolean,
        includeInAllocation: Boolean,
    ) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var assetClass by remember { mutableStateOf(AssetClass.LIQUID) }
    var amountText by remember { mutableStateOf("") }
    var costText by remember { mutableStateOf("") }
    var isLiability by remember { mutableStateOf(false) }
    var includeInAllocation by remember { mutableStateOf(true) }

    val amount = amountText.toMinorUnitsOrNull()
    val cost = costText.takeIf { it.isNotBlank() }?.toMinorUnitsOrNull()
    val subtypeId = subtypes.firstOrNull { it.assetClass == assetClass }?.id
    val canConfirm = name.isNotBlank() && amount != null && subtypeId != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加资产") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称，如「招行活期」") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("大类", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssetClass.displayOrder.forEach { candidate ->
                        FilterChip(
                            selected = candidate == assetClass,
                            onClick = { assetClass = candidate },
                            label = { Text(candidate.shortLabel()) },
                        )
                    }
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(if (isLiability) "欠款金额（元）" else "当前市值（元）") },
                    singleLine = true,
                    isError = amountText.isNotBlank() && amount == null,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!isLiability) {
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = { Text("总投入成本（元，可留空）") },
                        singleLine = true,
                        isError = costText.isNotBlank() && cost == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "填了成本才能显示浮动盈亏和收益率。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isLiability, onCheckedChange = { isLiability = it })
                    Text("这是一笔负债", style = MaterialTheme.typography.bodyMedium)
                }
                if (isLiability) {
                    Text(
                        "负债会从上面选的大类里抵扣。房贷选「另类实物」，信用卡选「流动资金」。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeInAllocation,
                        onCheckedChange = { includeInAllocation = it },
                    )
                    Text("计入配置比例", style = MaterialTheme.typography.bodyMedium)
                }
                if (!includeInAllocation) {
                    Text(
                        "取消勾选后，这项资产不进配置饼图的分子和分母。自住房常这么处理。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    onConfirm(
                        name.trim(),
                        assetClass,
                        subtypeId!!,
                        Money(amount!!),
                        cost?.let { Money(it) },
                        isLiability,
                        includeInAllocation,
                    )
                },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun AssetClass.shortLabel(): String = when (this) {
    AssetClass.LIQUID -> "流动资金"
    AssetClass.FIXED_INCOME -> "固定收益"
    AssetClass.EQUITY -> "权益类"
    AssetClass.ALTERNATIVE -> "另类实物"
    AssetClass.PROTECTION -> "保障类"
}

/**
 * 「元」字符串 → 分。
 *
 * 手工解析而不是 `toDouble() * 100` —— 后者会引入浮点误差，
 * 而这个 App 的整个金额链路都在避免它（见 AGENTS.md 约束 4）。
 * 超过两位小数直接判为非法输入，不静默截断。
 */
internal fun String.toMinorUnitsOrNull(): Long? {
    val text = trim()
    if (text.isEmpty()) return null

    val negative = text.startsWith('-')
    val body = text.removePrefix("-").removePrefix("+")
    if (body.isEmpty()) return null

    val parts = body.split('.')
    if (parts.size > 2) return null

    val yuanPart = parts[0].ifEmpty { "0" }
    if (!yuanPart.all { it.isDigit() }) return null

    val centPart = parts.getOrNull(1) ?: ""
    if (!centPart.all { it.isDigit() } || centPart.length > 2) return null

    val yuan = yuanPart.toLongOrNull() ?: return null
    val cents = centPart.padEnd(2, '0').toLongOrNull() ?: return null

    // 溢出保护：金额乘 100 可能超出 Long
    if (yuan > (Long.MAX_VALUE - cents) / 100) return null

    val minor = yuan * 100 + cents
    return if (negative) -minor else minor
}
