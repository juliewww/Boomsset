package com.boomsset.ui.assets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.boomsset.domain.UpdateKind
import com.boomsset.domain.UpdateRecord
import com.boomsset.ui.InfoTooltip
import com.boomsset.ui.formatDisplay
import com.boomsset.ui.formatWithCurrency
import com.boomsset.ui.historyDateLabel
import com.boomsset.ui.theme.chartColors
import kotlinx.datetime.LocalDate

/**
 * 「加载更多」一次放出多少条。
 *
 * 按**条数**而不是按时间窗口分页：更新频率因人而异，按季度记账的人「近半年」只有两条
 * （展开了跟坏了一样），每天记的人半年有上百条（一次全渲染）。条数对两种节奏都稳定，
 * 也是移动端列表的通行做法。
 */
internal const val HISTORY_PAGE_SIZE = 20

private const val HISTORY_TOOLTIP =
    "这里是每次「更新估值」留下的快照记录，不是收支流水 —— " +
        "旺资只记你在某个时点持有多少，不记每一笔进出。\n\n" +
        "记录会一直保留：净值曲线就是从这些快照算出来的，删掉旧记录等于把过去的净值一起删掉。"

/**
 * 资产页底部的「更新记录」。
 *
 * 写成 [LazyListScope] 的扩展而不是一个独立 `@Composable` —— 记录可能有几百上千条，
 * 塞进一个 `Column` 会一次性组合完全部行。挂进资产页那个已有的 `LazyColumn` 才能按需组合。
 *
 * **这一段是无条件挂在列表末尾的，不在任何 `if (isEmpty)` 分支里面。** 全部资产都归档时
 * 资产页走的是空态文案那一支，但归档记录恰恰都在这里 —— 挂进分支就等于「一归档完就看不见
 * 自己归了什么」。这是 AGENTS.md 那条「空状态不能走一条不包含入口的渲染分支」的同一个坑。
 */
internal fun LazyListScope.updateHistorySection(
    history: List<UpdateRecord>,
    today: LocalDate,
    expanded: Boolean,
    shownCount: Int,
    onToggleExpanded: () -> Unit,
    onLoadMore: () -> Unit,
) {
    if (history.isEmpty()) return

    item(key = "history-header") {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 和「查看 N 项已归档 / 收起已归档」同一种写法 —— 同一页里的两个折叠区
            // 不该一个用箭头图标、一个用换文字。
            TextButton(onClick = onToggleExpanded) {
                Text(
                    if (expanded) "收起更新记录" else "查看更新记录（${history.size} 条）",
                )
            }
            InfoTooltip(HISTORY_TOOLTIP)
        }
    }

    if (!expanded) return

    val visible = history.take(shownCount)
    items(visible, key = { "history-${it.snapshot.id}" }) { record ->
        UpdateRecordRow(record = record, today = today)
    }

    item(key = "history-footer") {
        val remaining = history.size - visible.size
        if (remaining > 0) {
            TextButton(onClick = onLoadMore) { Text("加载更多（还有 $remaining 条）") }
        } else {
            Text(
                "已显示全部 ${history.size} 条",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * 一条记录一行。
 *
 * 每种变化各占一行，不并排 —— `¥12,345,678.00 → ¥12,999,999.00` 这样一串在 360dp 的屏上
 * 已经占掉大半行宽，再并上成本变化必然断行（AGENTS.md 教训 17：中文界面要按最窄屏量）。
 *
 * ⚠️ **金额一律用资产自己的币种 [com.boomsset.domain.Asset.currency]，不折算到基准币种。**
 * 快照里存的就是自身币种下的数；折算历史金额要用**当时**的汇率，而汇率回补是按持仓区间做的、
 * 不保证每条记录那天都有 —— 缺一条就得显示「无法估值」，把一栏本来完全确定的原始记录
 * 弄成有洞的。原始记录就该原样显示。
 */
@Composable
private fun UpdateRecordRow(record: UpdateRecord, today: LocalDate) {
    val currency = record.asset.currency
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(chartColors.of(record.asset.assetClass)),
            )
            // weight 给**名字**，日期永远完整 —— 净值页顶部卡片那条同样的取舍：
            // Row 先按完整宽度量没有 weight 的子项，所以不够宽时折的是名字，不是日期。
            Text(
                record.asset.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                record.recordedDate.historyDateLabel(today),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                record.kind.label(),
                style = MaterialTheme.typography.labelMedium,
                color = when (record.kind) {
                    UpdateKind.CREATED -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                record.primaryChangeText(currency),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        // 成本变了才显示。份额没动而成本动了同样有意义（用户在修正填错的成本），
        // 所以判据是「成本本身变了」，不是「份额变了顺带看看成本」。
        val costBefore = record.previousCost
        val costAfter = record.cost
        if (costBefore != null && costAfter != null && costBefore != costAfter) {
            Text(
                "成本 ${costBefore.formatWithCurrency(currency)} → ${costAfter.formatWithCurrency(currency)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        if (record.modeChanged) {
            Text(
                "估值方式变了，和上一条不可比",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

/**
 * 主变化那一段文字。
 *
 * **变化量不上色。** 这一栏里资产和负债混在一起，而红涨绿跌说的是「涨没涨」不是「好不好」——
 * 房贷从 ¥100 万降到 ¥95 万会被涂成「跌」的颜色，读起来像坏消息。前值和新值都摆着，
 * 箭头方向已经把变化说清楚了，颜色在这里只会添乱。
 * （盈亏那边可以上色，是因为「盈」和「亏」本身就没有歧义。）
 */
private fun UpdateRecord.primaryChangeText(currency: String): String {
    val quantity = quantity
    if (quantity != null) {
        val before = previousQuantity
        return if (before != null) {
            "份额 ${before.formatDisplay()} → ${quantity.formatDisplay()}"
        } else {
            "份额 ${quantity.formatDisplay()}"
        }
    }
    val value = value ?: return "—"
    val before = previousValue
    return if (before != null) {
        "${before.formatWithCurrency(currency)} → ${value.formatWithCurrency(currency)}"
    } else {
        value.formatWithCurrency(currency)
    }
}

private fun UpdateKind.label(): String = when (this) {
    UpdateKind.CREATED -> "新增"
    UpdateKind.UPDATED -> "更新"
    UpdateKind.ARCHIVED -> "归档"
}
