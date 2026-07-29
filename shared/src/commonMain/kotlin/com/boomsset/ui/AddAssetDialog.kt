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
import com.boomsset.data.SUPPORTED_CURRENCIES
import com.boomsset.domain.AssetSubtype
import com.boomsset.domain.Money
import com.boomsset.domain.parseMoneyMinor

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
    defaultCurrency: String,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        assetClass: AssetClass,
        subtypeId: Long,
        currency: String,
        value: Money,
        costBasis: Money?,
        isLiability: Boolean,
        includeInAllocation: Boolean,
    ) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var assetClass by remember { mutableStateOf(AssetClass.LIQUID) }
    var currency by remember { mutableStateOf(defaultCurrency) }
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

                Text("币种", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUPPORTED_CURRENCIES.forEach { code ->
                        FilterChip(
                            selected = code == currency,
                            onClick = { currency = code },
                            label = { Text(code) },
                        )
                    }
                }
                if (currency != defaultCurrency) {
                    Text(
                        "非基准币种。净值会按当时汇率折算成 $defaultCurrency —— " +
                            "取不到汇率时这项会显示「无法估值」，不会按 1:1 算。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(if (isLiability) "欠款金额" else "当前市值") },
                    singleLine = true,
                    isError = amountText.isNotBlank() && amount == null,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!isLiability) {
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = { Text("总投入成本（可留空）") },
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
                        currency,
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
 * 「元」字符串 → 分。委托给 [com.boomsset.domain.parseMoneyMinor]。
 */
internal fun String.toMinorUnitsOrNull(): Long? = parseMoneyMinor(this)
