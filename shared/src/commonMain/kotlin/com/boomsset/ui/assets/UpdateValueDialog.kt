package com.boomsset.ui.assets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.boomsset.domain.AssetValuation
import com.boomsset.domain.Money
import com.boomsset.domain.Quantity
import com.boomsset.domain.parseQuantity
import com.boomsset.domain.Snapshot
import com.boomsset.domain.UnitPrice
import com.boomsset.domain.parseUnitPrice
import com.boomsset.ui.formatForInput
import com.boomsset.ui.priceDescription
import com.boomsset.ui.toMinorUnitsOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 更新估值。这是 App 的核心动作 —— 不记流水，只定期回答"这项资产现在值多少"。
 *
 * **关键规则：成本从上一条快照预填。** 快照是完整状态而非增量，用户只改市值时若
 * 成本字段留空，新快照的成本就是 null，收益率会凭空消失。所以两个字段都预填，
 * 让"忘记带上成本"在结构上不会发生。
 *
 * **默认记为"现在"，但可以改成补录某一天。** `asOf` 和 `recordedAt` 本就分开
 * （见 docs/domain.md「时间处理」），数据模型一直支持补录历史，只是这个入口之前
 * 没接出来。日期选择器**默认折叠**——大多数更新就是"现在"，常驻一个日期选择器
 * 会让最常见的操作多一步，和 InfoTooltip/AllocationPicker 那些"点开才用"的入口
 * 是同一个思路。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateValueDialog(
    valuation: AssetValuation,
    onDismiss: () -> Unit,
    onConfirmManual: (value: Money, costBasis: Money?, asOf: LocalDate?) -> Unit,
    onConfirmQuoted: (quantity: Quantity, symbol: String, costBasis: Money?, asOf: LocalDate?) -> Unit,
    onSetManualPrice: (symbol: String, price: UnitPrice, currency: String) -> Unit,
) {
    val snapshot = valuation.snapshot
    val previousCost = snapshot?.costBasisMinor

    // 预填：市值/份额取当前值，成本取上一条 —— 不留空
    var amountText by remember {
        mutableStateOf(
            when (snapshot) {
                is Snapshot.Manual -> snapshot.value.formatForInput()
                else -> valuation.localValue?.formatForInput() ?: ""
            },
        )
    }
    var quantityText by remember {
        mutableStateOf(
            (snapshot as? Snapshot.Quoted)?.quantity?.formatForInput() ?: "",
        )
    }
    var costText by remember { mutableStateOf(previousCost?.formatForInput() ?: "") }
    // 单价预填当前行情（可能是 stale 的），空着表示不覆盖
    var priceText by remember {
        mutableStateOf(valuation.quote?.price?.formatForInput() ?: "")
    }
    // 补录日期。null 表示"现在"——这是绝大多数更新的情况，不给它默认值。
    var asOfDate by remember { mutableStateOf<LocalDate?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    val isQuoted = snapshot is Snapshot.Quoted
    val amount = amountText.toMinorUnitsOrNull()
    val quantity = quantityText.toQuantityOrNull()
    val cost = costText.takeIf { it.isNotBlank() }?.toMinorUnitsOrNull()
    val manualPrice = priceText.takeIf { it.isNotBlank() }?.let { parseUnitPrice(it) }
    val priceInvalid = priceText.isNotBlank() && manualPrice == null
    val costInvalid = costText.isNotBlank() && cost == null

    val canConfirm = if (isQuoted) quantity != null && !costInvalid && !priceInvalid
    else amount != null && !costInvalid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新「${valuation.asset.name}」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isQuoted) {
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        label = { Text("持有份额") },
                        singleLine = true,
                        isError = quantityText.isNotBlank() && quantity == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "市值由份额 × 行情单价算出。加仓减仓就是改这里的份额。",
                        style = MaterialTheme.typography.labelSmall,
                    )

                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = { Text("行情单价") },
                        singleLine = true,
                        isError = priceText.isNotBlank() && manualPrice == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        valuation.priceDescription(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (valuation.isPriceStale) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = {
                            Text(if (valuation.asset.isLiability) "当前欠款（元）" else "当前市值（元）")
                        },
                        singleLine = true,
                        isError = amountText.isNotBlank() && amount == null,
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = FIELD_UPDATE_AMOUNT
                        },
                    )
                }

                if (!valuation.asset.isLiability) {
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = { Text("总投入成本（元）") },
                        singleLine = true,
                        isError = costInvalid,
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = FIELD_UPDATE_COST
                        },
                    )
                    Text(
                        if (isQuoted) {
                            "如果这次是加仓，记得把新投入的钱加进总成本 —— " +
                                "份额涨了成本没涨，收益率会虚高。"
                        } else {
                            "已带出上次填的成本。改动市值不会影响它，除非你也改这里。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Text(
                    "这会新增一条快照，历史记录不会被改写。",
                    style = MaterialTheme.typography.labelSmall,
                )

                // 补录日期：默认折叠成一句话 + 一个按钮，点了才展开日期选择器 ——
                // 常驻一个日期选择器会让"现在"这个最常见的情况多一步操作。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        asOfDate?.let { "记为 $it" } ?: "记为现在",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = { showDatePicker = true }) {
                        Text(if (asOfDate == null) "补录到某天" else "改日期")
                    }
                    if (asOfDate != null) {
                        TextButton(onClick = { asOfDate = null }) { Text("恢复现在") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    if (isQuoted) {
                        // 单价改了就写一条今天的行情 —— 顺序在前，好让快照写完后立刻能用上。
                        // ⚠️ 这条**始终写今天的行情**，不跟着 asOfDate 走 —— 手填单价本来就
                        // 是"我现在知道的价"，补录历史市值和"今天的行情是多少"是两件事。
                        if (manualPrice != null && manualPrice != valuation.quote?.price) {
                            onSetManualPrice(
                                snapshot.quoteSymbol,
                                manualPrice,
                                valuation.quote?.currency ?: valuation.asset.currency,
                            )
                        }
                        onConfirmQuoted(
                            quantity!!,
                            // isQuoted 已经保证了类型，智能转换在这里成立
                            snapshot.quoteSymbol,
                            cost?.let { Money(it) },
                            asOfDate,
                        )
                    } else {
                        onConfirmManual(Money(amount!!), cost?.let { Money(it) }, asOfDate)
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showDatePicker) {
        // M3 的 DatePicker 内部按 **UTC** 存取 selectedDateMillis（不管用户本地时区，
        // 都是"那一天 00:00 UTC"）——这是它文档里明说的设计，混用本地时区会在时区偏移
        // 跨天时选错一天。所以这里用 TimeZone.UTC 做换算，不用 currentSystemDefault()。
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (asOfDate ?: Clock.System.now()
                .toLocalDateTime(TimeZone.currentSystemDefault()).date)
                .atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
            selectableDates = object : SelectableDates {
                // 不能补录未来 —— "这项资产明天值多少"不是一个能回答的问题。
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis <= Clock.System.now().toEpochMilliseconds()
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        asOfDate = Instant.fromEpochMilliseconds(millis)
                            .toLocalDateTime(TimeZone.UTC).date
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) {
            DatePicker(state = state)
        }
    }
}

/** 更新弹窗里输入框的无障碍标识，UI 测试按这些字符串定位。 */
const val FIELD_UPDATE_AMOUNT = "field-update-amount"
const val FIELD_UPDATE_COST = "field-update-cost"

/**
 * 份额字符串 → 定点整数（scale = 8）。委托给 [com.boomsset.domain.parseQuantity]。
 */
internal fun String.toQuantityOrNull(): Quantity? = parseQuantity(this)
