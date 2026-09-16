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
 * How many records "load more" releases at a time.
 *
 * Paginated by **record count** rather than a time window: update frequency varies a lot by
 * user — someone recording quarterly might have only two records in "the last six months"
 * (expanding it would look broken), while someone recording daily might have hundreds in six
 * months (rendering them all at once). Count-based pagination is stable for both cadences, and
 * it's also the conventional approach for mobile lists.
 */
internal const val HISTORY_PAGE_SIZE = 20

private const val HISTORY_TOOLTIP =
    "这里是每次「更新估值」留下的快照记录，不是收支流水 —— " +
        "猪满仓只记你在某个时点持有多少，不记每一笔进出。\n\n" +
        "记录会一直保留：净值曲线就是从这些快照算出来的，删掉旧记录等于把过去的净值一起删掉。"

/**
 * The "update history" section at the bottom of the assets page.
 *
 * Written as a [LazyListScope] extension rather than a standalone `@Composable` — there could be
 * hundreds or thousands of records, and stuffing them into a `Column` would compose every row at
 * once. Attaching it to the assets page's existing `LazyColumn` allows on-demand composition.
 *
 * **This section is attached unconditionally at the end of the list, not inside any
 * `if (isEmpty)` branch.** When all assets are archived, the assets page takes the empty-state
 * text branch, but the archive records are exactly what live here — attaching it inside that
 * branch would mean "the moment you finish archiving, you can no longer see what you archived".
 * This is the same pitfall as AGENTS.md's rule that "an empty state must not take a rendering
 * branch that omits the entry point".
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
            // Same pattern as "view N archived / collapse archived" — the two collapsible
            // sections on the same page shouldn't use different conventions (one an arrow icon,
            // the other swapped text).
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
 * One record per row.
 *
 * Each kind of change gets its own line rather than being placed side by side — a string like
 * `¥12,345,678.00 → ¥12,999,999.00` already takes up most of a row's width on a 360dp screen;
 * putting the cost-basis change next to it would force a line break (AGENTS.md lesson 17:
 * Chinese-text layouts must be measured against the narrowest screen).
 *
 * ⚠️ **Amounts are always shown in the asset's own currency
 * [com.boomsset.domain.Asset.currency], never converted to the base currency.** The snapshot
 * stores the number in its own currency as-is; converting a historical amount would require the
 * exchange rate **at that time**, and FX backfilling is done over the holding period's date
 * range, not guaranteed to cover every single record's day — missing even one would force
 * showing "can't be valued", turning a column of otherwise perfectly certain raw records into
 * one full of holes. Raw records should be shown exactly as recorded.
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
            // weight goes to the **name**, the date is always shown in full — the same tradeoff
            // as the net worth page's top card: Row measures non-weighted children at their full
            // width first, so when space is tight it's the name that wraps, not the date.
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

        // Only shown when the cost basis actually changed. It's still meaningful when the
        // quantity didn't move but the cost did (the user is correcting a mis-entered cost),
        // so the condition is "the cost basis itself changed", not "quantity changed, so also
        // check the cost in passing".
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
 * The main change-description text.
 *
 * **The amount of change is not color-coded.** This column mixes assets and liabilities
 * together, and the convention "red = up, green = down" reflects "did it go up" not "is this
 * good" — a mortgage dropping from ¥1,000,000 to ¥950,000 would get painted the "down" color,
 * reading like bad news. With both the before and after values shown, the arrow direction
 * already makes the change clear, and color here would only add confusion.
 * (The gain/loss section, by contrast, can be color-coded, because "gain" and "loss" are
 * unambiguous on their own.)
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
