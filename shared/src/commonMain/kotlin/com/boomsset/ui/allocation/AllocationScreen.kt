package com.boomsset.ui.allocation

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
import androidx.compose.material3.LinearProgressIndicator
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
import com.boomsset.domain.AllocationView
import com.boomsset.domain.AssetClass
import com.boomsset.domain.TargetAllocation
import com.boomsset.ui.bpToPercent
import com.boomsset.ui.label
import com.boomsset.ui.formatWithCurrency

@Composable
fun AllocationScreen(
    state: AllocationUiState,
    onSelectAllocation: (Long) -> Unit,
    onSaveTargets: (id: Long, targetsBp: Map<AssetClass, Int>) -> Unit,
    onCreateAllocation: (name: String, targetsBp: Map<AssetClass, Int>) -> Unit,
    onRestoreBuiltIn: (TargetAllocation) -> Unit,
    onDeleteAllocation: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<TargetAllocation?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val view = state.view
        val active = state.allocations.firstOrNull { it.isActive }

        Header(view, active)

        // ⚠️ 这一块**必须在任何空状态分支之外**。
        //
        // 它曾经写在 `else` 分支里，于是零资产时整块被跳过 —— 而它是切换/编辑/新建
        // 目标配置的**唯一**入口，结果新用户根本够不到目标配置。而目标配置恰恰是
        // 录第一笔资产**之前**就想设的东西（"我想先看看该怎么配"）。
        //
        // 这是 AGENTS.md 那条通则的第三次犯：空状态不能走一条不含入口的分支。
        // 注意它不限于提前 `return` —— 这次是 `when` 的分支，形式不同、后果一样。
        if (state.allocations.isNotEmpty()) {
            AllocationPicker(
                allocations = state.allocations,
                onSelect = onSelectAllocation,
                onEdit = { editing = it },
                onCreate = { creating = true },
                onRestore = onRestoreBuiltIn,
                onDelete = onDeleteAllocation,
            )
        }

        when {
            state.loading -> Text("加载中…", style = MaterialTheme.typography.bodyMedium)

            view == null -> Text(
                "读不到配置数据。",
                style = MaterialTheme.typography.bodyMedium,
            )

            // 还没有资产时显示**目标比例本身**，而不是一行"去添加资产"。
            // 这一页在没有数据时也该是有用的：它回答"我打算怎么配"，
            // 这个问题不依赖任何持仓。
            state.isEmpty -> TargetPreview(active)

            // 净资产 ≤ 0 时比例在数学上无意义，直说而不是显示乱数
            view.netWorth.minorUnits <= 0L -> NegativeNetWorthNotice()

            else -> {
                AssetClass.displayOrder.forEach { assetClass ->
                    ClassRow(view, assetClass)
                }
                if (view.hasNegativeExposure) NegativeExposureNotice()
                DenominatorNote()
            }
        }
    }

    editing?.let { allocation ->
        TargetEditorDialog(
            allocation = allocation,
            onDismiss = { editing = null },
            onSave = { _, targets ->
                onSaveTargets(allocation.id, targets)
                editing = null
            },
        )
    }

    if (creating) {
        TargetEditorDialog(
            allocation = null,
            onDismiss = { creating = false },
            onSave = { name, targets ->
                onCreateAllocation(name, targets)
                creating = false
            },
        )
    }
}

/**
 * 目标配置的切换与管理。
 *
 * 多套并存可对比是 domain.md 定的产品决策 —— 这里让它真正可用。
 */
@Composable
private fun AllocationPicker(
    allocations: List<TargetAllocation>,
    onSelect: (Long) -> Unit,
    onEdit: (TargetAllocation) -> Unit,
    onCreate: () -> Unit,
    onRestore: (TargetAllocation) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val active = allocations.firstOrNull { it.isActive }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("对比哪套目标", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            allocations.forEach { allocation ->
                FilterChip(
                    selected = allocation.isActive,
                    onClick = { onSelect(allocation.id) },
                    label = { Text(allocation.name) },
                )
            }
            FilterChip(selected = false, onClick = onCreate, label = { Text("＋ 新建") })
        }

        active?.let { allocation ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onEdit(allocation) }) { Text("编辑比例") }
                if (allocation.isBuiltIn) {
                    TextButton(onClick = { onRestore(allocation) }) { Text("恢复默认") }
                } else {
                    TextButton(onClick = { onDelete(allocation.id) }) { Text("删除") }
                }
            }
            // 内置预设不是权威处方 —— domain.md 要求 UI 不能呈现为针对用户的推荐
            if (allocation.isBuiltIn) {
                Text(
                    "内置预设是行业常见的起点，不是针对你情况的建议。按自己的目标改。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * 标题区。**`view` 可空** —— 加载中和零资产时也要显示标题和当前对比的目标名，
 * 否则这一页在最需要解释自己的时候反而什么都不说。
 */
@Composable
private fun Header(view: AllocationView?, active: TargetAllocation?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("资产配置", style = MaterialTheme.typography.titleLarge)
        if (view != null) {
            Text(
                "净资产 ${view.netWorth.formatWithCurrency(view.baseCurrency)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // 目标名取自 state.allocations，不依赖 view —— 没有资产时它照样有值
        Text(
            active?.let { "对比目标：${it.name}" } ?: "尚未设定目标配置",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * 零资产时显示目标比例。
 *
 * 刻意用和 [ClassRow] 一样的卡片 + 进度条版式：等真的有了资产，同一个位置会换成
 * 当前比例和偏离，位置和形状不变，用户不需要重新找东西在哪。
 */
@Composable
private fun TargetPreview(active: TargetAllocation?) {
    if (active == null) {
        Text(
            "还没有目标配置。点上面的「＋ 新建」定一套，或者先去「净值」页添加资产。",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    Text(
        "这是你的目标比例。添加资产后，这里会换成当前比例和与目标的偏离。",
        style = MaterialTheme.typography.bodyMedium,
    )

    AssetClass.displayOrder.forEach { assetClass ->
        val targetBp = active.targetsBp[assetClass] ?: 0
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(assetClass.label(), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "目标 ${targetBp.bpToPercent(decimals = 0)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                LinearProgressIndicator(
                    progress = {
                        (targetBp.toFloat() / TargetAllocation.TOTAL_BP).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    Text(
        "去「净值」页点右下角加号添加第一笔资产，就能看到自己离目标有多远。",
        style = MaterialTheme.typography.labelMedium,
    )
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
