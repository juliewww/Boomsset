package com.boomsset.ui.allocation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boomsset.domain.AssetClass
import com.boomsset.domain.TargetAllocation
import com.boomsset.ui.bpToInputPercent
import com.boomsset.ui.bpToPercent
import com.boomsset.ui.parsePercentToBp

/**
 * 编辑目标配置比例。
 *
 * **核心约束：之和必须正好 100%。** 不闭合的配置会让偏离度全错，而且不会报错 ——
 * 所以「保存」在不闭合时是禁用的，并且实时显示当前合计和差额。
 *
 * 预填用 [bpToInputPercent]（不带 % 和多余的 0），保证能被 [parsePercentToBp] 读回 ——
 * 这是从「预填带千分位导致保存永久禁用」那个 bug 学到的。
 */
@Composable
fun TargetEditorDialog(
    allocation: TargetAllocation?,
    onDismiss: () -> Unit,
    onSave: (name: String, targetsBp: Map<AssetClass, Int>) -> Unit,
) {
    val isNew = allocation == null
    var name by remember { mutableStateOf(allocation?.name ?: "我的配置") }

    // 每个大类一个输入框，预填现有值（没有的按 0）
    val inputs = remember {
        mutableStateMapOf<AssetClass, String>().apply {
            AssetClass.displayOrder.forEach { assetClass ->
                put(assetClass, (allocation?.targetsBp?.get(assetClass) ?: 0).bpToInputPercent())
            }
        }
    }

    val parsed = AssetClass.displayOrder.associateWith { parsePercentToBp(inputs[it].orEmpty()) }
    val anyInvalid = parsed.values.any { it == null }
    val sumBp = parsed.values.filterNotNull().sum()
    val closed = !anyInvalid && sumBp == TargetAllocation.TOTAL_BP
    val canSave = closed && name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新建目标配置" else "编辑「${allocation.name}」") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                AssetClass.displayOrder.forEach { assetClass ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            assetClass.editorLabel(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(88.dp),
                        )
                        OutlinedTextField(
                            value = inputs[assetClass].orEmpty(),
                            onValueChange = { inputs[assetClass] = it },
                            suffix = { Text("%") },
                            singleLine = true,
                            isError = parsed[assetClass] == null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // 实时合计 —— 不闭合时明确告诉用户还差多少，而不是只把按钮变灰
                val diff = TargetAllocation.TOTAL_BP - sumBp
                Text(
                    when {
                        anyInvalid -> "有输入不是合法的百分比"
                        closed -> "合计 100%，可以保存"
                        diff > 0 -> "合计 ${sumBp.bpToPercent()}，还差 ${diff.bpToPercent()}"
                        else -> "合计 ${sumBp.bpToPercent()}，超出 ${(-diff).bpToPercent()}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (closed) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (closed) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error,
                )

                Text(
                    "必须正好 100% —— 不闭合的配置会让偏离度全错，而且不会报错。",
                    style = MaterialTheme.typography.labelSmall,
                )
                if (allocation?.isBuiltIn == true) {
                    Text(
                        "这是内置预设。改动只影响你自己的数据，之后可以「恢复默认」。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(name.trim(), parsed.mapValues { it.value ?: 0 })
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

internal fun AssetClass.editorLabel(): String = when (this) {
    AssetClass.LIQUID -> "流动资金"
    AssetClass.FIXED_INCOME -> "固定收益"
    AssetClass.EQUITY -> "权益类"
    AssetClass.ALTERNATIVE -> "另类实物"
    AssetClass.PROTECTION -> "保障类"
}
