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
    // 默认折叠：资产页的主体是「我现在有什么」，更新记录是回顾用的，
    // 展开着会让主列表一直往下拖。
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
        // 原来非空态时顶部常驻一句"点更新估值记快照"的说明 —— 占地方，且用惯了的用户
        // 不需要每次都被提醒（实机反馈）。空状态那句不算"提示"而是状态本身（列表是空的
        // 总要说一声），所以保留，但因为加号已经就在这一页（见 App.kt 的 FAB 改动），
        // 不用再指去"净值"页。
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
                // 色块和配置页用的是同一套大类色 —— 三个页面同一种视觉语言，
                // 用户在配置页认到的"蓝色=流动资金"在这里仍然成立。
                // 色块旁边一定有名字：身份不能只靠颜色。
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

        // 挂在列表末尾、**不在上面任何一个分支里** —— 全部资产归档后 `state.isEmpty`
        // 为真，而归档记录恰恰都在这一栏。见 updateHistorySection 的注释。
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
                // 归档时那条 0 值快照是真实记录，不会被撤销 —— 说清楚，别让用户以为数据丢了
                "取消归档后它会以 ¥0 出现（归档那条 0 值记录不会被删），需要你再更新一次估值。",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** 左滑手势的两个落点：没划开 / 划开露出操作区。 */
private enum class RevealValue { Settled, Revealed }

/**
 * 每行原来常驻显示三个按钮（更新估值/改名称分类/归档），在小屏上占掉快一半的卡片高度
 * （实机反馈）。改成**左滑**才露出来。
 *
 * **不用 material3 的 `SwipeToDismissBox`。** 它是"划走删除"这个交互设计的组件，
 * 只有两个落点：没划开（offset=0）和划到底（offset=±整行宽度，也就是整行滑出屏幕）——
 * 拿它做"划开一点、露出操作按钮"用会导致卡片信息**整个滑没**，不是常见 App
 * （Gmail、Telegram 那种左滑露出按钮）的效果（实机反馈："不是正规的实现"）。
 * 改用 Compose Foundation 更底层的 `AnchoredDraggableState`，自己定两个落点：
 * `Settled`（0）和 `Revealed`（`-actionsWidthPx`，即**操作区自己实际测量出来的宽度**，
 * 不是整行宽度）——划开后最多让前景卡片让出操作区那么宽，"能显示多少就多少"。
 *
 * 「更新估值」这个核心动作**没有变成纯隐形入口** —— 卡片本身仍然整张可点直接触发更新
 * （[AssetRowCard] 的 `onClick`），这是之前"核心动作不能只有隐形入口"那条教训要保住的部分
 * （用户曾经因为找不到能改市值的按钮而误以为"改不了资产"）。左滑收起来的是编辑名称/分类
 * 和归档 —— 这两个本来就不是高频操作，藏进手势里不会重蹈那次的问题。
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
    // **构造时必须直接带上 anchors，不能只在 LaunchedEffect 里后补。**
    // 第一次实现漏了这个，`requireOffset()` 在第一帧布局时就被 `.offset { }` 读取，
    // 而 `updateAnchors` 要等 `LaunchedEffect` 跑完才第一次调用——中间那一帧
    // offset 还没被"初始化"过，直接抛 `IllegalStateException`（真机上一点"资产"
    // tab 就崩，实机反馈）。构造时先给两个落点都填 0（还没量出操作区宽度），
    // 等测量完那一帧 `LaunchedEffect` 再更新成真实宽度。
    val draggableState = remember {
        AnchoredDraggableState(
            RevealValue.Settled,
            DraggableAnchors {
                RevealValue.Settled at 0f
                RevealValue.Revealed at 0f
            },
        )
    }
    // 操作区还没测量出真实宽度之前，两个落点都是 0——先不能划，等测量完那一帧再更新，
    // 肉眼感觉不到这个延迟。宽度按实际内容算，不是行宽的固定比例，保证"露出刚好够按的
    // 三个按钮"而不是行宽的某个百分比。
    LaunchedEffect(actionsWidthPx) {
        draggableState.updateAnchors(
            DraggableAnchors {
                RevealValue.Settled at 0f
                RevealValue.Revealed at -actionsWidthPx
            },
        )
    }
    val scope = rememberCoroutineScope()

    // `anchoredDraggable` 挂在**最外层** Box 上，不是挂在前景卡片那层——这是照抄
    // material3 自己的 `SwipeToDismissBox` 的结构。第一版把它跟 `.offset {}` 放在
    // 同一层（前景卡片那个 Box），结果左滑后点背后露出来的按钮完全没反应
    // （实机反馈）。前景卡片只留 `.offset {}` 负责跟手位移，拖拽手势的识别和
    // 背后按钮的点击各自在不同层，不会互相抢事件。
    Box(
        Modifier
            .fillMaxWidth()
            .anchoredDraggable(draggableState, Orientation.Horizontal),
    ) {
        // 这层 Box 不参与外层 Box 的尺寸计算（`matchParentSize` 的定义），
        // 外层高度完全由下面前景卡片撑出来——按钮 Row 直接用 `fillMaxHeight()`
        // 在 LazyColumn 里会拿到"无限高"约束报错，`matchParentSize` 是 Compose
        // 里"背景层贴合前景已经量出来的尺寸"的标准写法。
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
 * 卡片重新设计过——原来是不带任何 `colors`/`elevation` 的默认 `Card`，容器色是
 * `surfaceContainerLow`（`#FCFCFC`）叠加默认阴影，跟纯白页面背景放在一起
 * 只有 3 个色值的差距，阴影的灰边反而成了最显眼的东西，看起来像"一整块灰"
 * （实机反馈）。改成**纯白容器 + 1dp 细边框**代替阴影去区分卡片边界，
 * 边框颜色和阴影不一样，不会有那圈模糊的灰晕。左边加一条大类色的竖条——
 * 复用配置页/表头已经在用的那套 [chartColors]，不是新起的强调色，
 * 相当于把表头那个色点"拉长"成一条，同一屏内多一点视觉区分，不重复造轮子。
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
                color = when {
                    pnl.absolute.minorUnits > 0 -> riseColor()
                    pnl.absolute.minorUnits < 0 -> fallColor()
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        // 行情日期/过期提示 —— 腾讯是非官方接口，用户必须知道价格有多旧
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
