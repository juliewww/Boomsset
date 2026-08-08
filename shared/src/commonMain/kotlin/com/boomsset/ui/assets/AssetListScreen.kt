package com.boomsset.ui.assets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boomsset.domain.AssetClass
import com.boomsset.domain.AssetValuation
import com.boomsset.domain.Money
import com.boomsset.domain.Quantity
import com.boomsset.ui.bpToPercent
import com.boomsset.ui.priceDescription
import com.boomsset.ui.formatWithCurrency
import kotlinx.coroutines.launch

@Composable
fun AssetListScreen(
    state: AssetListUiState,
    onUpdateManual: (assetId: Long, value: Money, costBasis: Money?) -> Unit,
    onUpdateQuoted: (assetId: Long, quantity: Quantity, symbol: String, costBasis: Money?) -> Unit,
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
    }

    updating?.let { valuation ->
        UpdateValueDialog(
            valuation = valuation,
            onDismiss = { updating = null },
            onConfirmManual = { value, cost ->
                onUpdateManual(valuation.asset.id, value, cost)
                updating = null
            },
            onConfirmQuoted = { quantity, symbol, cost ->
                onUpdateQuoted(valuation.asset.id, quantity, symbol, cost)
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

/**
 * 每行原来常驻显示三个按钮（更新估值/改名称分类/归档），在小屏上占掉快一半的卡片高度
 * （实机反馈）。改成**左滑**才露出来 —— 用的是 material3 自带的 `SwipeToDismissBox`，
 * 不新增依赖。它本来是给"划走删除"设计的，这里**不做真正的 dismiss**：`enableDismissFromStartToEnd`
 * 关掉右滑方向，只留左滑；划开后不移除这一行数据，只是把背后的三个按钮露出来，
 * 点完或点旁的地方都会 `reset()` 弹回去，效果就是"左滑显示操作、不是划走"。
 *
 * 「更新估值」这个核心动作**没有变成纯隐形入口** —— 卡片本身仍然整张可点直接触发更新
 * （[AssetRowCard] 的 `onClick`），这是之前"核心动作不能只有隐形入口"那条教训要保住的部分
 * （用户曾经因为找不到能改市值的按钮而误以为"改不了资产"）。左滑收起来的是编辑名称/分类
 * 和归档 —— 这两个本来就不是高频操作，藏进手势里不会重蹈那次的问题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssetRow(
    valuation: AssetValuation,
    baseCurrency: String,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onArchiveClick: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SwipeActionButton("更新", MaterialTheme.colorScheme.primary) {
                    scope.launch { dismissState.reset() }
                    onClick()
                }
                SwipeActionButton("编辑", MaterialTheme.colorScheme.onSurfaceVariant) {
                    scope.launch { dismissState.reset() }
                    onEdit()
                }
                SwipeActionButton("归档", MaterialTheme.colorScheme.error) {
                    scope.launch { dismissState.reset() }
                    onArchiveClick()
                }
            }
        },
    ) {
        AssetRowCard(valuation = valuation, baseCurrency = baseCurrency, onClick = onClick)
    }
}

@Composable
private fun SwipeActionButton(label: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AssetRowCard(
    valuation: AssetValuation,
    baseCurrency: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
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
