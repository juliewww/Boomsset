package com.boomsset.ui.assets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.boomsset.data.SUPPORTED_CURRENCIES
import com.boomsset.domain.AssetClass
import com.boomsset.domain.AssetEditPolicy
import com.boomsset.domain.AssetSubtype
import com.boomsset.domain.AssetValuation

/** 编辑后的资产元信息。 */
data class AssetMetaEdit(
    val name: String,
    val assetClass: AssetClass,
    val subtypeId: Long,
    val currency: String,
    val includeInAllocation: Boolean,
)

/**
 * 编辑资产元信息。
 *
 * 币种在有历史记录后会被锁住，并且**说明原因** —— 见 [AssetEditPolicy]。
 * 只把控件禁掉而不解释，用户会以为是 bug。
 */
@Composable
fun EditAssetDialog(
    valuation: AssetValuation,
    subtypes: List<AssetSubtype>,
    onDismiss: () -> Unit,
    onSave: (AssetMetaEdit) -> Unit,
    onAddSubtype: (name: String, assetClass: AssetClass) -> Unit,
) {
    val asset = valuation.asset
    var name by remember { mutableStateOf(asset.name) }
    var assetClass by remember { mutableStateOf(asset.assetClass) }
    var subtypeId by remember { mutableStateOf(asset.subtypeId) }
    var currency by remember { mutableStateOf(asset.currency) }
    var includeInAllocation by remember { mutableStateOf(asset.includeInAllocation) }
    var newSubtypeName by remember { mutableStateOf("") }
    var addingSubtype by remember { mutableStateOf(false) }

    val currencyEditable = AssetEditPolicy.canChangeCurrencyAndLiability(valuation.snapshotCount)
    val classSubtypes = subtypes.filter { it.assetClass == assetClass }

    // 换了大类之后原来的品种就不属于这个类了，自动落到该类的第一个
    val effectiveSubtypeId = if (classSubtypes.any { it.id == subtypeId }) {
        subtypeId
    } else {
        classSubtypes.firstOrNull()?.id
    }

    val canSave = name.isNotBlank() && effectiveSubtypeId != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑「${asset.name}」") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("大类", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssetClass.displayOrder.forEach { candidate ->
                        FilterChip(
                            selected = candidate == assetClass,
                            onClick = { assetClass = candidate },
                            label = { Text(candidate.editLabel()) },
                        )
                    }
                }
                if (asset.isLiability) {
                    Text(
                        "这是负债 —— 大类决定它从哪一类里抵扣。房贷选「另类实物」，信用卡选「流动资金」。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Text("品种", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    classSubtypes.forEach { subtype ->
                        FilterChip(
                            selected = subtype.id == effectiveSubtypeId,
                            onClick = { subtypeId = subtype.id },
                            label = { Text(subtype.name) },
                        )
                    }
                    FilterChip(
                        selected = addingSubtype,
                        onClick = { addingSubtype = !addingSubtype },
                        label = { Text("＋ 自定义") },
                    )
                }
                if (addingSubtype) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = newSubtypeName,
                            onValueChange = { newSubtypeName = it },
                            label = { Text("新品种名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextButton(
                        enabled = newSubtypeName.isNotBlank(),
                        onClick = {
                            onAddSubtype(newSubtypeName.trim(), assetClass)
                            newSubtypeName = ""
                            addingSubtype = false
                        },
                    ) { Text("添加到「${assetClass.editLabel()}」") }
                }

                Text("币种", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUPPORTED_CURRENCIES.forEach { code ->
                        FilterChip(
                            enabled = currencyEditable,
                            selected = code == currency,
                            onClick = { currency = code },
                            label = { Text(code) },
                        )
                    }
                }
                if (!currencyEditable) {
                    // 说清为什么锁住，而不是只把 chip 变灰
                    Text(
                        AssetEditPolicy.lockedReason(valuation.snapshotCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeInAllocation,
                        onCheckedChange = { includeInAllocation = it },
                    )
                    Text("计入配置比例", style = MaterialTheme.typography.bodyMedium)
                }

                Text(
                    "改这些只影响归类和展示，不会改动任何已记录的金额。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        AssetMetaEdit(
                            name = name.trim(),
                            assetClass = assetClass,
                            subtypeId = effectiveSubtypeId!!,
                            currency = if (currencyEditable) currency else asset.currency,
                            includeInAllocation = includeInAllocation,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

internal fun AssetClass.editLabel(): String = when (this) {
    AssetClass.LIQUID -> "流动资金"
    AssetClass.FIXED_INCOME -> "固定收益"
    AssetClass.EQUITY -> "权益类"
    AssetClass.ALTERNATIVE -> "另类实物"
    AssetClass.PROTECTION -> "保障类"
}
