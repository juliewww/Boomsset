package com.boomsset.ui.assets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import com.boomsset.ui.fallColor
import com.boomsset.ui.riseColor
import com.boomsset.ui.theme.chartColors
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.boomsset.domain.AssetClass
import com.boomsset.domain.AssetValuation
import com.boomsset.domain.Money
import com.boomsset.domain.Quantity
import com.boomsset.ui.bpToPercent
import com.boomsset.ui.priceDescription
import com.boomsset.ui.formatWithCurrency
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

@Composable
fun AssetListScreen(
    state: AssetListUiState,
    onUpdateManual: (assetId: Long, value: Money, costBasis: Money?, asOf: LocalDate?) -> Unit,
    onUpdateQuoted: (
        assetId: Long, quantity: Quantity, symbol: String, costBasis: Money?, asOf: LocalDate?,
    ) -> Unit,
    onArchive: (assetId: Long) -> Unit,
    onUnarchive: (assetId: Long) -> Unit,
    onEditMeta: (AssetValuation, AssetMetaEdit) -> Unit,
    onAddSubtype: (name: String, assetClass: AssetClass) -> Unit,
    onSetManualPrice: (symbol: String, price: com.boomsset.domain.UnitPrice, currency: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var updating by remember { mutableStateOf<AssetValuation?>(null) }
    var archiving by remember { mutableStateOf<AssetValuation?>(null) }
    var editing by remember { mutableStateOf<AssetValuation?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    // Collapsed by default: the body of the assets page is "what do I have right now"; the
    // update history is for looking back, and leaving it expanded would keep dragging the main
    // list further down.
    var showHistory by remember { mutableStateOf(false) }
    var historyShown by remember { mutableIntStateOf(HISTORY_PAGE_SIZE) }

    if (state.loading) {
        Text("加载中…", modifier = modifier.padding(16.dp))
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        // Used to always show a "tap to update valuation and record a snapshot" hint at the top
        // in the non-empty state — it took up space, and users who already know the ropes don't
        // need to be reminded every time (real-device feedback). The empty-state line doesn't
        // count as a "hint" but rather the state itself (an empty list always needs to say so),
        // so it's kept, but since the add button is already on this page (see the FAB change in
        // App.kt) it no longer needs to point to the "net worth" page.
        if (state.isEmpty) {
            item {
                Text(
                    "没有在持资产。点右下角加号添加。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        AssetClass.displayOrder.forEach { assetClass ->
            val rows = state.grouped[assetClass].orEmpty()
            if (rows.isEmpty()) return@forEach

            item(key = "header-$assetClass") {
                // The swatch uses the same set of class colors as the allocation page — a single
                // visual language across all three pages, so "blue = liquid assets" that a user
                // learned on the allocation page still holds here.
                // There is always a name next to the swatch: identity must not rely on color alone.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
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
            items(rows, key = { it.asset.id }) { valuation ->
                AssetRow(
                    valuation = valuation,
                    baseCurrency = state.baseCurrency,
                    onClick = { updating = valuation },
                    onEdit = { editing = valuation },
                    onArchiveClick = { archiving = valuation },
                )
            }
        }

        if (state.archivedCount > 0) {
            item {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showArchived = !showArchived }) {
                        Text(if (showArchived) "收起已归档" else "查看 ${state.archivedCount} 项已归档")
                    }
                    Text(
                        "已归档的历史仍计入净值曲线，只是不再持有。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (showArchived) {
                items(state.archived, key = { "archived-${it.asset.id}" }) { valuation ->
                    ArchivedRow(
                        valuation = valuation,
                        onUnarchive = { onUnarchive(valuation.asset.id) },
                    )
                }
            }
        }

        // Attached at the end of the list, **not inside any of the branches above** — once all
        // assets are archived `state.isEmpty` becomes true, and the archive records are exactly
        // what live in this section. See the comment on updateHistorySection.
        updateHistorySection(
            history = state.history,
            today = state.today,
            expanded = showHistory,
            shownCount = historyShown,
            onToggleExpanded = { showHistory = !showHistory },
            onLoadMore = { historyShown += HISTORY_PAGE_SIZE },
        )
    }

    updating?.let { valuation ->
        UpdateValueDialog(
            valuation = valuation,
            onDismiss = { updating = null },
            onConfirmManual = { value, cost, asOf ->
                onUpdateManual(valuation.asset.id, value, cost, asOf)
                updating = null
            },
            onConfirmQuoted = { quantity, symbol, cost, asOf ->
                onUpdateQuoted(valuation.asset.id, quantity, symbol, cost, asOf)
                updating = null
            },
            onSetManualPrice = onSetManualPrice,
        )
    }

    editing?.let { valuation ->
        EditAssetDialog(
            valuation = valuation,
            subtypes = state.subtypes,
            onDismiss = { editing = null },
            onSave = { edit ->
                onEditMeta(valuation, edit)
                editing = null
            },
            onAddSubtype = onAddSubtype,
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
private fun ArchivedRow(valuation: AssetValuation, onUnarchive: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(valuation.asset.name, style = MaterialTheme.typography.titleSmall)
            Text("已归档", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onUnarchive) { Text("取消归档") }
            Text(
                // The zero-value snapshot recorded at archive time is a real record and won't be
                // undone — say so explicitly, so the user doesn't think data was lost
                "取消归档后它会以 ¥0 出现（归档那条 0 值记录不会被删），需要你再更新一次估值。",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** The two anchor points of the swipe-left gesture: not swiped / swiped open to reveal the actions area. */
private enum class RevealValue { Settled, Revealed }

/**
 * Each row used to always show three buttons (update valuation / rename & reclassify / archive),
 * which took up nearly half the card's height on small screens (real-device feedback). Changed
 * so they only reveal on a **swipe left**.
 *
 * **Not using material3's `SwipeToDismissBox`.** It's a component designed for the "swipe away
 * to dismiss" interaction, with only two anchor points: not swiped (offset=0) and swiped all the
 * way (offset=±the full row width, i.e. the whole row slides off screen) — using it for "swipe
 * open a bit to reveal action buttons" would cause the card's information to **slide away
 * entirely**, not the effect seen in common apps (Gmail, Telegram-style swipe-to-reveal buttons)
 * (real-device feedback: "not a proper implementation"). Switched to Compose Foundation's
 * lower-level `AnchoredDraggableState`, with two custom anchor points: `Settled` (0) and
 * `Revealed` (`-actionsWidthPx`, i.e. **the actions area's own actually-measured width**, not the
 * full row width) — once swiped open, the foreground card yields at most that much space,
 * showing "as much as fits".
 *
 * The core "update valuation" action **has not become a purely invisible entry point** — the
 * card itself is still tappable anywhere to trigger an update directly ([AssetRowCard]'s
 * `onClick`), preserving the part of the earlier lesson that "a core action must not have only
 * an invisible entry point" (a user once mistakenly thought "the asset can't be edited" because
 * they couldn't find a button to change the market value). What's tucked behind the swipe is
 * edit name/class and archive — these were never high-frequency actions, so hiding them behind
 * a gesture doesn't repeat that earlier problem.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssetRow(
    valuation: AssetValuation,
    baseCurrency: String,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onArchiveClick: () -> Unit,
) {
    var actionsWidthPx by remember { mutableFloatStateOf(0f) }
    // **Anchors must be supplied directly at construction time, not only patched in later inside
    // a LaunchedEffect.** The first implementation missed this: `requireOffset()` is read by
    // `.offset { }` as early as the first layout frame, while `updateAnchors` isn't called for
    // the first time until `LaunchedEffect` finishes running — during that in-between frame the
    // offset hasn't been "initialized" yet and it throws `IllegalStateException` (crashed the
    // moment you tapped the "assets" tab on a real device, per real-device feedback). So at
    // construction time both anchor points are first filled with 0 (the actions area's width
    // hasn't been measured yet), and once the measurement frame completes, `LaunchedEffect`
    // updates them to the real width.
    val draggableState = remember {
        AnchoredDraggableState(
            RevealValue.Settled,
            DraggableAnchors {
                RevealValue.Settled at 0f
                RevealValue.Revealed at 0f
            },
        )
    }
    // Before the actions area's real width has been measured, both anchor points are 0 — so it
    // can't be swiped yet, and it updates once the measurement frame completes; this delay isn't
    // perceptible to the eye. The width is computed from the actual content, not a fixed fraction
    // of the row width, guaranteeing "reveal just enough to tap the three buttons" rather than
    // some percentage of the row width.
    LaunchedEffect(actionsWidthPx) {
        draggableState.updateAnchors(
            DraggableAnchors {
                RevealValue.Settled at 0f
                RevealValue.Revealed at -actionsWidthPx
            },
        )
    }
    val scope = rememberCoroutineScope()

    // `anchoredDraggable` is attached to the **outermost** Box, not to the foreground card's
    // layer — this mirrors material3's own `SwipeToDismissBox` structure. The first version put
    // it on the same layer as `.offset {}` (the foreground card's Box), and as a result tapping
    // the revealed buttons behind it after swiping left did nothing at all (real-device
    // feedback). The foreground card keeps only `.offset {}`, responsible for tracking the drag
    // position; drag-gesture recognition and tapping the buttons behind it each live on separate
    // layers, so they don't steal events from each other.
    Box(
        Modifier
            .fillMaxWidth()
            .anchoredDraggable(draggableState, Orientation.Horizontal),
    ) {
        // This Box does not participate in the outer Box's size calculation (that's the
        // definition of `matchParentSize`); the outer height is entirely determined by the
        // foreground card below it — if the button Row used `fillMaxHeight()` directly inside a
        // LazyColumn it would get an "infinite height" constraint error. `matchParentSize` is
        // Compose's standard idiom for "make a background layer match a foreground layer's
        // already-measured size".
        Box(Modifier.matchParentSize()) {
            Row(
                Modifier
                    .align(Alignment.CenterEnd)
                    .onSizeChanged { actionsWidthPx = it.width.toFloat() }
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SwipeActionButton("更新", MaterialTheme.colorScheme.primary) {
                    scope.launch { draggableState.animateTo(RevealValue.Settled) }
                    onClick()
                }
                SwipeActionButton("编辑", MaterialTheme.colorScheme.onSurfaceVariant) {
                    scope.launch { draggableState.animateTo(RevealValue.Settled) }
                    onEdit()
                }
                SwipeActionButton("归档", MaterialTheme.colorScheme.error) {
                    scope.launch { draggableState.animateTo(RevealValue.Settled) }
                    onArchiveClick()
                }
            }
        }

        Box(
            Modifier.offset { IntOffset(draggableState.requireOffset().roundToInt(), 0) },
        ) {
            AssetRowCard(valuation = valuation, baseCurrency = baseCurrency, onClick = onClick)
        }
    }
}

@Composable
private fun SwipeActionButton(label: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The card was redesigned — it used to be a default `Card` with no `colors`/`elevation` at all,
 * whose container color, `surfaceContainerLow` (`#FCFCFC`), plus the default shadow, differed
 * from the pure-white page background by only 3 color values; the shadow's gray edge ended up
 * being the most visually prominent thing, reading as "one solid gray blob" (real-device
 * feedback). Changed to a **pure white container + a 1dp thin border** instead of a shadow to
 * mark the card's boundary — the border color is distinct from the shadow, avoiding that blurry
 * gray halo. A vertical bar in the class color was added on the left — reusing the same
 * [chartColors] already used on the allocation page/section headers rather than introducing a
 * new accent color, effectively "stretching" the header's color dot into a bar, adding a bit
 * more visual distinction on the same screen without reinventing anything.
 */
@Composable
private fun AssetRowCard(
    valuation: AssetValuation,
    baseCurrency: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(chartColors.of(valuation.asset.assetClass)),
            )
            AssetRowContent(valuation, baseCurrency)
        }
    }
}

@Composable
private fun AssetRowContent(valuation: AssetValuation, baseCurrency: String) {
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

        // When it can't be valued, state the reason explicitly rather than showing 0 — showing 0
        // would make the user think the asset is gone
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
                color = when {
                    pnl.absolute.minorUnits > 0 -> riseColor()
                    pnl.absolute.minorUnits < 0 -> fallColor()
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        // Quote date / staleness notice — Tencent's quote API is unofficial, and the user must
        // know how old the price is
        if (valuation.snapshot is com.boomsset.domain.Snapshot.Quoted) {
            Text(
                valuation.priceDescription(),
                style = MaterialTheme.typography.labelSmall,
                color = if (valuation.isPriceStale) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
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
