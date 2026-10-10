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
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.boomsset.ui.AmountField
import com.boomsset.ui.HintedField
import com.boomsset.ui.formatDisplay
import com.boomsset.ui.priceDescription
import com.boomsset.ui.toMinorUnitsOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Update a valuation. This is the app's core action — it doesn't record transactions, only
 * periodically answers "what is this asset worth right now".
 *
 * **Key rule: the cost basis is prefilled from the previous snapshot.** A snapshot is a complete
 * state rather than a delta; if the user only changes the market value while leaving the cost
 * field blank, the new snapshot's cost basis would become null and the return rate would vanish
 * out of nowhere. So both fields are prefilled, making "forgetting to carry over the cost basis"
 * structurally impossible.
 *
 * **Defaults to recording "now", but can be changed to backfill a specific day.** `asOf` and
 * `recordedAt` were already separate (see "time handling" in docs/domain.md); the data model has
 * always supported backfilling history, it's just that this entry point hadn't wired it up yet.
 * The date picker is **collapsed by default** — most updates are for "now", and always showing
 * a date picker would add a step to the most common operation, the same idea as the "only shown
 * on tap" entry points like InfoTooltip/AllocationPicker.
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
    val currency = valuation.asset.currency
    val isQuoted = snapshot is Snapshot.Quoted

    // What's there now. These are **hints, not prefilled text**: the fields start empty so the
    // user can just type the new number, and an empty field means "keep this" — a snapshot is a
    // complete state, so each blank is resolved to the current value below rather than left null.
    // That is what keeps "forgot to carry the cost over" structurally impossible, which is why the
    // old version prefilled in the first place.
    val currentAmount: Money? = when (snapshot) {
        is Snapshot.Manual -> snapshot.value
        else -> valuation.localValue
    }
    val currentQuantity: Quantity? = (snapshot as? Snapshot.Quoted)?.quantity
    val currentCost: Money? = snapshot?.costBasisMinor

    var amountText by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("") }
    var costText by remember { mutableStateOf("") }
    // Unit price is prefilled with the current quote (which may be stale); left blank means don't override it
    var priceText by remember { mutableStateOf("") }
    // The backfill date. Null means "now" — the case for the vast majority of updates, so it's
    // given no other default.
    var asOfDate by remember { mutableStateOf<LocalDate?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    val typedAmount = amountText.toMinorUnitsOrNull()
    val typedQuantity = quantityText.toQuantityOrNull()
    val typedCost = costText.takeIf { it.isNotBlank() }?.toMinorUnitsOrNull()
    val manualPrice = priceText.takeIf { it.isNotBlank() }?.let { parseUnitPrice(it) }
    val amountInvalid = amountText.isNotBlank() && typedAmount == null
    val quantityInvalid = quantityText.isNotBlank() && typedQuantity == null
    val costInvalid = costText.isNotBlank() && typedCost == null
    val priceInvalid = priceText.isNotBlank() && manualPrice == null

    // Blank → current. Null only when there is no current value to fall back on (an asset with no
    // snapshot), in which case the field is required.
    val amount = if (amountText.isBlank()) currentAmount?.minorUnits else typedAmount
    val quantity = if (quantityText.isBlank()) currentQuantity else typedQuantity
    val cost = if (costText.isBlank()) currentCost?.minorUnits else typedCost

    // Saving with nothing typed and no date would append a snapshot identical to the last one.
    val hasEdits = amountText.isNotBlank() || quantityText.isNotBlank() ||
        costText.isNotBlank() || priceText.isNotBlank() || asOfDate != null

    val canConfirm = hasEdits && !costInvalid && if (isQuoted) {
        quantity != null && !quantityInvalid && !priceInvalid
    } else {
        amount != null && !amountInvalid
    }

    // Open straight into the field the user is about to type in, so "update" is: tap, type, save.
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新「${valuation.asset.name}」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isQuoted) {
                    HintedField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        label = "持有份额",
                        current = currentQuantity?.formatDisplay(),
                        reading = null,
                        isError = quantityInvalid,
                        modifier = Modifier.fillMaxWidth().focusRequester(firstField),
                    )
                    Text(
                        "市值由份额 × 行情单价算出。加仓减仓就是改这里的份额。",
                        style = MaterialTheme.typography.labelSmall,
                    )

                    HintedField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = "行情单价",
                        current = valuation.quote?.price?.formatDisplay(valuation.quote?.currency ?: currency),
                        reading = null,
                        isError = priceInvalid,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        valuation.priceDescription(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (valuation.isPriceStale) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    AmountField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = if (valuation.asset.isLiability) "新的欠款（元）" else "新的市值（元）",
                        currency = currency,
                        current = currentAmount,
                        isError = amountInvalid,
                        modifier = Modifier.fillMaxWidth().focusRequester(firstField).semantics {
                            contentDescription = FIELD_UPDATE_AMOUNT
                        },
                    )
                }

                if (!valuation.asset.isLiability) {
                    AmountField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = "总投入成本（元）",
                        currency = currency,
                        current = currentCost,
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
                            "留空就沿用上次的成本，改动市值不会影响它。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Text(
                    "这会新增一条快照，历史记录不会被改写。",
                    style = MaterialTheme.typography.labelSmall,
                )

                // Backfill date: collapsed by default into one line of text + a button, only
                // expanding the date picker on tap — always showing a date picker would add a
                // step to "now", the most common case.
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
                        // If the unit price changed, write a quote for today — done first, so it's
                        // immediately usable once the snapshot is written.
                        // ⚠️ This **always writes today's quote**, regardless of asOfDate — a
                        // manually entered unit price is inherently "the price I know right now";
                        // backfilling a historical market value and "what's today's quote" are
                        // two separate things.
                        if (manualPrice != null && manualPrice != valuation.quote?.price) {
                            onSetManualPrice(
                                snapshot.quoteSymbol,
                                manualPrice,
                                valuation.quote?.currency ?: valuation.asset.currency,
                            )
                        }
                        onConfirmQuoted(
                            quantity!!,
                            // isQuoted already guarantees the type, so the smart cast holds here
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
        // M3's DatePicker internally stores/reads selectedDateMillis in **UTC** (regardless of
        // the user's local timezone, it's always "00:00 UTC on that day") — this is documented
        // as intentional design, and mixing in the local timezone would pick the wrong day
        // whenever the timezone offset crosses a day boundary. So TimeZone.UTC is used for the
        // conversion here, not currentSystemDefault().
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (asOfDate ?: Clock.System.now()
                .toLocalDateTime(TimeZone.currentSystemDefault()).date)
                .atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
            selectableDates = object : SelectableDates {
                // Can't backfill into the future — "what will this asset be worth tomorrow" is
                // not an answerable question.
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

/** Accessibility identifiers for the input fields in the update dialog; UI tests locate them by these strings. */
const val FIELD_UPDATE_AMOUNT = "field-update-amount"
const val FIELD_UPDATE_COST = "field-update-cost"

/**
 * Quantity string → fixed-point integer (scale = 8). Delegates to [com.boomsset.domain.parseQuantity].
 */
internal fun String.toQuantityOrNull(): Quantity? = parseQuantity(this)
