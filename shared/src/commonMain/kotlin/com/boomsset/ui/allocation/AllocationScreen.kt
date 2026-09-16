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

        // ⚠️ This block **must sit outside any empty-state branch**.
        //
        // It used to live inside the `else` branch, so the whole block was skipped at zero
        // assets — yet it's the **only** entry point for switching/editing/creating target
        // allocations, which meant new users could never reach target allocation configuration
        // at all. And configuring a target allocation is exactly the kind of thing a user wants
        // to do *before* recording their first asset ("let me see how I want to allocate first").
        //
        // This is the third time the general rule from AGENTS.md has been violated: an empty
        // state must not take a branch that omits the entry point. Note it isn't limited to an
        // early `return` — this time it was a `when` branch, different shape, same consequence.
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

            // With no assets yet, show **the target ratios themselves** rather than a line
            // saying "go add an asset". This page should still be useful with no data: it
            // answers "how do I intend to allocate", a question that doesn't depend on holdings.
            state.isEmpty -> TargetPreview(active)

            // When net worth <= 0 the ratios are mathematically meaningless; say so plainly rather than showing garbage numbers
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
 * Switching and managing target allocations.
 *
 * Having multiple sets coexist for comparison is a product decision defined in domain.md —
 * this makes it actually usable.
 *
 * **Edit/restore-default/delete are no longer always on screen** — they used to be two
 * [TextButton]s permanently shown below the chip row, taking up a whole line's worth of space
 * (real-device feedback). Now they only expand on a long press of the current target; the chip
 * row itself already marks "which one is being compared right now" via its `selected` state, so
 * no extra persistent buttons or explanatory text are needed.
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
                    // The current target is a hand-rolled long-pressable chip instead of a
                    // FilterChip — FilterChip's built-in clickable tends to fight with an outer
                    // long-press gesture layered on top, swallowing each other's events. So just
                    // this one chip is switched to combinedClickable: a short press does nothing
                    // (it's already selected), a long press expands edit/restore-default/delete.
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
            // Built-in presets aren't an authoritative prescription — domain.md requires the UI
            // to avoid presenting them as personalized recommendations
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
 * The header area. **`view` is nullable** — the title must still show while loading and at zero
 * assets, otherwise this page says nothing exactly when it most needs to explain itself.
 *
 * Which target is currently being compared **is not repeated here** — [AllocationPicker]'s chip
 * row already marks it via the `selected` state, and writing it in both places would be pure
 * redundant information.
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
 * Show the target ratios when there are zero assets.
 *
 * Deliberately uses the same card + progress-bar layout as [ClassRow]: once assets actually
 * exist, the same spot switches to showing current ratio and deviation, with position and shape
 * unchanged, so the user never has to relearn where things are.
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
                // What's filled here **is the target itself** (no assets yet, so there's no
                // "current" to draw), so no marker line is drawn — a marker line coinciding with
                // the end of the fill would just be redundant. The same [AllocationBar] is reused
                // so shape and height stay identical to when assets exist: once the first one is
                // recorded, the same spot switches to "fill = current, marker = target" and the
                // user doesn't need to relearn where things are.
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

            // Fill = current share (negative can't be drawn, a negative exposure renders as
            // length 0, the actual value is in the text below), marker line = target position.
            // Both points have to be on the same bar to be comparable.
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
                // Color is **not the only cue**: the words over-allocated/under-allocated/on-target
                // are always present, so colorblind users and black-and-white printing can still
                // read the direction. Color is only there to make it scannable.
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
 * "· ¥X from target" — converts the deviation into an amount of money.
 *
 * Reasoning: knowing "over-allocated by 31%" doesn't tell you how much money to move — the user
 * would have to multiply by net worth themselves (real-device feedback). The rounding and
 * conventions pitfalls are documented in [AllocationView.rebalanceAmount].
 *
 * **Appended to the end of the deviation line, not a new line.** Adding a line to each of the
 * five cards would noticeably lengthen this page, and "too much verbosity" has already been
 * flagged as feedback twice. Sharing the same color is also intentional: over-allocated is red,
 * so the rebalance amount that follows naturally reads as "should reduce".
 *
 * The amount **omits cents**: this is a planning-scale figure, and the cents digit is noise.
 * So the whole clause is dropped when the rebalance amount is under ¥1 — otherwise you'd get a
 * self-contradictory display like "under-allocated 0.01% · ¥0 from target" (which happens when
 * net worth is very small).
 */
private fun rebalanceClause(view: AllocationView, assetClass: AssetClass): String {
    val amount = view.rebalanceAmount(assetClass) ?: return ""
    if (kotlin.math.abs(amount.minorUnits) < 100L) return ""
    return " · 距目标 ${amount.formatSigned(view.baseCurrency, showDecimals = false)}"
}

/**
 * The class label = a small color swatch + name.
 *
 * **There is always a name next to the color swatch.** Identity must not rely on color alone —
 * in light mode several class colors have a swatch contrast below 3:1, so the swatch alone
 * isn't recognizable; also colorblind users and black-and-white printing both need the text.
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
    // Used to be a full explanatory sentence shown permanently; real-device feedback was "too
    // much filler text" — switched to one very short line + an (i) icon, with the full
    // explanation moved into a tooltip that only shows on tap.
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
