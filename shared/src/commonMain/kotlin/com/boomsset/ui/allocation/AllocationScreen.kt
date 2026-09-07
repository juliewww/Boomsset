package com.boomsset.ui.allocation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.boomsset.ui.theme.chartColors
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
import androidx.compose.material3.Surface
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
import com.boomsset.domain.Money
import com.boomsset.domain.TargetAllocation
import com.boomsset.ui.InfoTooltip
import com.boomsset.ui.bpToPercent
import com.boomsset.ui.label
import com.boomsset.ui.formatSigned
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

        Header(view)

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
                AllocationDonut(
                    shares = AssetClass.displayOrder.map { it to (view.exposures[it]?.netExposure ?: Money.ZERO) },
                    netWorth = view.netWorth,
                    baseCurrency = view.baseCurrency,
                )
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
 *
 * **编辑/恢复默认/删除不再常驻显示** —— 之前是两个 [TextButton] 常驻在 chip 行下面，
 * 占用了一整行空间（实机反馈）。现在长按当前目标才展开，chip 行本身已经用
 * `selected` 状态标出"当前对比哪一套"，不需要额外的常驻按钮或说明文字。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AllocationPicker(
    allocations: List<TargetAllocation>,
    onSelect: (Long) -> Unit,
    onEdit: (TargetAllocation) -> Unit,
    onCreate: () -> Unit,
    onRestore: (TargetAllocation) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var actionsForId by remember { mutableStateOf<Long?>(null) }
    val actionsTarget = allocations.firstOrNull { it.id == actionsForId }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            allocations.forEach { allocation ->
                if (allocation.isActive) {
                    // 当前目标换成手写的可长按 chip，而不是 FilterChip ——
                    // FilterChip 自带的 clickable 和外层长按手势叠在一起容易互相吞掉手势，
                    // 干脆只给这一个 chip 换成 combinedClickable，短按不做事（本来就已经选中）、
                    // 长按才展开编辑/恢复默认/删除。
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { actionsForId = allocation.id },
                        ),
                    ) {
                        Text(
                            allocation.name,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                } else {
                    FilterChip(
                        selected = false,
                        onClick = { onSelect(allocation.id) },
                        label = { Text(allocation.name) },
                    )
                }
            }
            FilterChip(selected = false, onClick = onCreate, label = { Text("＋ 新建") })
            if (actionsTarget == null) {
                InfoTooltip("长按上面高亮的目标可以编辑比例，或恢复默认/删除。")
            }
        }

        if (actionsTarget != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onEdit(actionsTarget); actionsForId = null }) {
                    Text("编辑比例")
                }
                if (actionsTarget.isBuiltIn) {
                    TextButton(onClick = { onRestore(actionsTarget); actionsForId = null }) {
                        Text("恢复默认")
                    }
                } else {
                    TextButton(onClick = { onDelete(actionsTarget.id); actionsForId = null }) {
                        Text("删除")
                    }
                }
                TextButton(onClick = { actionsForId = null }) { Text("收起") }
            }
            // 内置预设不是权威处方 —— domain.md 要求 UI 不能呈现为针对用户的推荐
            if (actionsTarget.isBuiltIn) {
                Text(
                    "内置预设是行业常见的起点，不是针对你情况的建议。按自己的目标改。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * 标题区。**`view` 可空** —— 加载中和零资产时也要显示标题，
 * 否则这一页在最需要解释自己的时候反而什么都不说。
 *
 * 当前对比的是哪一套目标，**不在这里重复显示** —— [AllocationPicker] 的
 * chip 行已经用 `selected` 状态标出来了，两处都写一遍是纯粹的重复信息。
 */
@Composable
private fun Header(view: AllocationView?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("资产配置", style = MaterialTheme.typography.titleLarge)
        if (view != null) {
            Text(
                "净资产 ${view.netWorth.formatWithCurrency(view.baseCurrency)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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
            "还没有目标配置。点上面的「＋ 新建」定一套，或者先去「资产」页添加资产。",
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
                    ClassLabel(assetClass)
                    Text(
                        "目标 ${targetBp.bpToPercent(decimals = 0)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                // 这里填充的**就是目标**（还没有资产，没有"当前"可画），所以不画竖线 ——
                // 一根和填充末端重合的竖线只是重复。用同一个 [AllocationBar] 是为了
                // 让形状和高度跟有资产时完全一致：等录了第一笔，同一个位置换成
                // "填充=当前、竖线=目标"，用户不用重新找东西在哪。
                AllocationBar(
                    fillBp = targetBp,
                    markerBp = null,
                    color = chartColors.of(assetClass),
                )
            }
        }
    }

    Text(
        "去「资产」页点右下角加号添加第一笔资产，就能看到自己离目标有多远。",
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
                ClassLabel(assetClass)
                Text(
                    shareBp?.bpToPercent() ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // 填充 = 当前占比（画不出负数，负敞口按 0 长度，真实值在下面文字里），
            // 竖线 = 目标位置。两个点位放在同一根条上才比得出来。
            AllocationBar(
                fillBp = shareBp,
                markerBp = targetBp,
                color = chartColors.of(assetClass),
            )

            Text(
                buildString {
                    append("净敞口 ")
                    append(exposure.netExposure.formatSigned(view.baseCurrency))
                    if (!exposure.liabilities.isZero) {
                        append("（资产 ${exposure.assets.formatWithCurrency(view.baseCurrency)} ")
                        append("− 负债 ${exposure.liabilities.formatWithCurrency(view.baseCurrency)}）")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
            )

            if (targetBp != null && deviationBp != null) {
                // 颜色**不是唯一线索**：超配/低配/已达标 这几个词一直在，
                // 色盲用户和黑白打印都读得出方向。颜色只是让它可扫视。
                Text(
                    "目标 ${targetBp.bpToPercent(decimals = 0)}，" +
                        if (deviationBp == 0) "已达标"
                        else "${if (deviationBp > 0) "超配" else "低配"} " +
                            kotlin.math.abs(deviationBp).bpToPercent() +
                            rebalanceClause(view, assetClass),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (deviationBp == 0) FontWeight.Normal else FontWeight.Medium,
                    color = when {
                        deviationBp > 0 -> chartColors.over
                        deviationBp < 0 -> chartColors.under
                        else -> chartColors.onTarget
                    },
                )
            }
        }
    }
}

/**
 * 「· 距目标 ±¥X」—— 把偏离度换算成钱。
 *
 * 存在的理由：知道"超配 31%"并不等于知道该动多少钱，用户得自己拿净资产去乘
 * （实机反馈）。口径和取整的坑都在 [AllocationView.rebalanceAmount] 的文档里。
 *
 * **接在偏离度那一行后面，不另起一行。** 五张卡片各多一行，这一页会明显变长，
 * 而"废话太多"已经被反馈过两次。共用同一个颜色也是有意的：超配是红的，
 * 跟着的调整额自然读作"该减"。
 *
 * 金额**不显示分**：这是个规划用的量级，分位是噪音。因此当调整额不足 ¥1 时整句省掉 ——
 * 否则会出现"低配 0.01% · 距目标 +¥0"这种自相矛盾的显示（净资产很小时会发生）。
 */
private fun rebalanceClause(view: AllocationView, assetClass: AssetClass): String {
    val amount = view.rebalanceAmount(assetClass) ?: return ""
    if (kotlin.math.abs(amount.minorUnits) < 100L) return ""
    return " · 距目标 ${amount.formatSigned(view.baseCurrency, showDecimals = false)}"
}

/**
 * 大类标签 = 小色块 + 名称。
 *
 * **色块旁边一定有名字。** 身份不能只靠颜色 —— 浅色模式下有几个大类色低于 3:1 的
 * 色块对比度，靠色块本身认不出来；而且色盲用户和黑白打印都需要文字。
 */
@Composable
private fun ClassLabel(assetClass: AssetClass) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(chartColors.of(assetClass)),
        )
        Text(assetClass.label(), style = MaterialTheme.typography.titleSmall)
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
    // 原来是一整句解释常驻显示，实机反馈是"有很多废话" —— 换成一行极短的提示
    // + (i) 图标，完整解释收进点开才看的 tooltip。
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "净敞口已抵扣负债，比例加总 100%",
            style = MaterialTheme.typography.labelSmall,
        )
        InfoTooltip(
            "比例的分母是全部净资产（含自住房）。各大类显示的是净敞口 —— " +
                "归属到该类的负债已经抵扣，所以比例加总为 100%。正号表示这类资产扣除对应负债后" +
                "仍是净资产，负号表示这类的负债超过了资产。\n\n" +
                "条形上的竖线是目标位置，填充是当前占比。\n\n" +
                "「距目标」按当前净资产折算，假设总净资产不变（减掉超配的、等额加到低配的），" +
                "所以各类加起来正好是 0。只投新钱不卖出的话要投得更多 —— 新钱同时也进分母。",
        )
    }
}
