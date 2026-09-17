package com.boomsset.network

import com.boomsset.domain.ExchangeRate
import com.boomsset.domain.FxRate
import com.boomsset.domain.parseExchangeRate
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Fetches FX rates. Implementations are swappable; tests use a fake. */
interface FxRateSource {
    /**
     * Fetches the [from] → [to] rate for every business day in the [start]..[end] range.
     *
     * **There is only a range-based interface, no single-day interface.** The net worth
     * curve needs 12 points at once, each of which must use **the rate at that point in
     * time** (domain.md: converting history using today's rate pollutes the curve). One
     * request per day would take dozens of round trips, whereas a single range request is
     * enough; when `start == end` it's simply "fetch one day", so no separate code path is
     * needed.
     *
     * @return The days within the range that have a quote (weekends/holidays aren't
     *   included; the service provider automatically falls back to the previous business
     *   day). **asOfDay is the date the service actually returned**, not the requested
     *   date. Returns an empty list on failure — the caller should fall back to a stored
     *   stale rate rather than treating the asset as unable to be valued.
     */
    suspend fun fetchRange(
        from: String,
        to: String,
        start: LocalDate,
        end: LocalDate,
    ): List<FxRate>
}

/**
 * Implementation based on [Frankfurter](https://frankfurter.dev). Official ECB data, no
 * API key required, **supports historical dates** — this was the decisive reason for
 * choosing it, since the domain model requires "convert historical net worth using the
 * rate at that point in time".
 *
 * Two things confirmed through real testing:
 *
 * 1. **Weekends/holidays return the actual business day.** Requesting 2026-07-26 (a
 *    Sunday) returns `"date":"2026-07-24"` in the response. So **the date in the response
 *    must be stored, not the requested date** — storing the requested date would
 *    misattribute the rate to a day ECB never published one for.
 * 2. **Only 30 currencies are supported, and TWD is not one of them.** CNY/USD/HKD/EUR/JPY/
 *    GBP/SGD/AUD/KRW are all supported. An unsupported currency simply can't get a rate,
 *    and the corresponding asset will show "unable to value" (rather than silently
 *    converting at 1:1).
 *
 * Known limitation: ECB data goes back to 1999, but **only for weekdays**. This doesn't
 * affect us — the carry-forward rule is already "take the closest one before that point
 * in time".
 *
 * The range endpoint `GET /v1/{start}..{end}` has a **different** payload shape than the
 * single-day one: `rates` is a two-level structure ("date → {currency: rate}"), not a
 * single level. Three things confirmed through real testing:
 * - `start == end` is valid and returns the one record for that day
 * - When the requested range **falls entirely on a weekend**, the service provider shifts
 *   the range to the previous business day and returns that (never returns empty)
 * - A ten-year range (~2560 business days) yields a ~74KB response — a one-time backfill
 *   of that size is acceptable
 */
class FrankfurterFxRateSource(
    private val client: HttpClient,
    private val baseUrl: String = "https://api.frankfurter.dev/v1",
) : FxRateSource {

    override suspend fun fetchRange(
        from: String,
        to: String,
        start: LocalDate,
        end: LocalDate,
    ): List<FxRate> {
        // Same currency is 1:1 and needs no (and shouldn't make an) outbound request. The
        // valuation layer uses IDENTITY directly for the same currency, so this record
        // doesn't even need to be persisted — return an empty list rather than fabricating one
        if (from == to) return emptyList()
        if (start > end) return emptyList()

        val response = runCatching {
            client.get("$baseUrl/$start..$end") {
                url.parameters.append("base", from)
                url.parameters.append("symbols", to)
            }
        }.getOrNull() ?: return emptyList()

        if (!response.status.isSuccess()) return emptyList()

        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }
            .getOrNull() ?: return emptyList()

        return parseRange(body, from, to)
    }

    internal fun parseRange(body: JsonObject, from: String, to: String): List<FxRate> {
        val rates = body["rates"]?.jsonObject ?: return emptyList()
        return rates.mapNotNull { (day, perCurrency) ->
            val rateText = runCatching { perCurrency.jsonObject[to]?.jsonPrimitive?.content }
                .getOrNull() ?: return@mapNotNull null
            // Parsed as a fixed-point value straight from the raw string, **never through Double** — FX rates participate in net worth accumulation
            val rate = parseExchangeRate(rateText) ?: return@mapNotNull null
            // The date comes from the payload's key, i.e. the day the service provider actually published it — see point 1 in the class doc comment
            FxRate(base = from, quote = to, asOfDay = day, rate = rate)
        }
    }
}
