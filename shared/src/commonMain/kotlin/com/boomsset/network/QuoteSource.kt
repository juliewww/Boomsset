package com.boomsset.network

import com.boomsset.domain.Quote
import com.boomsset.domain.parseUnitPrice
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/** Fetches stock/fund quotes. Implementation is swappable — tests use a fake, and switching vendors in the future only touches this layer. */
interface QuoteSource {
    /**
     * Batch-fetches prices.
     *
     * @return The quotes successfully fetched. **Symbols that couldn't be fetched don't
     *   appear in the return value**; never returns a zero price — a zero price would
     *   silently zero out the asset.
     */
    suspend fun fetch(symbols: Set<String>, on: LocalDate): List<Quote>
}

/**
 * Tencent Finance quotes (`qt.gtimg.cn`).
 *
 * ## Why this source, and the risks
 *
 * A-share and on-exchange fund quotes have no official free source like ECB does for FX
 * rates — exchange data carries licensing costs. Testing (2026-07) found Tencent's
 * endpoint to be the only key-free option with full A-share coverage: Sina returns 403,
 * Tiantian Fund returns HTML, and Yahoo's unofficial endpoint is rate-limited with 429s.
 *
 * ⚠️ **This is an unofficial API**: publicly accessible without authentication, but with
 * no documentation and no ToS guarantee — it could change or stop working at any time.
 * The product is positioned for personal/small-scale use, and this risk is knowingly
 * accepted. When it fails, the behavior is **unable to fetch a price → the asset shows
 * "unable to value"**, never a silently wrong number — this is guaranteed by the
 * [QuoteSource] contract.
 *
 * ## Symbol format
 *
 * - A-shares: `sh600519` (Shanghai Stock Exchange), `sz000858` (Shenzhen Stock Exchange)
 * - Hong Kong stocks: `hk00700`
 * - US stocks: `usAAPL`
 *
 * The prefix determines the currency — see [currencyOf].
 *
 * ## Response format and GBK
 *
 * The response looks like: `v_sh600519="1~贵州茅台~600519~1329.62~1320.00~...";`
 * Fields are separated by `~`, and **index 3 is the current price**.
 *
 * **The payload is GBK-encoded**, and Kotlin/Native has no built-in GBK decoder. The
 * approach here is to read the bytes as Latin-1 (byte → char, one to one) — this leaves
 * the ASCII portions (price, symbol, date) completely intact; only the Chinese name field
 * comes out garbled, and we **never use that field anyway** (the asset name is something
 * the user chooses themselves). This is far simpler than introducing a full GBK code
 * table, and there's no possibility of a decoding failure.
 */
class TencentQuoteSource(
    private val client: HttpClient,
    private val baseUrl: String = "https://qt.gtimg.cn",
    private val clock: Clock = Clock.System,
) : QuoteSource {

    override suspend fun fetch(symbols: Set<String>, on: LocalDate): List<Quote> {
        if (symbols.isEmpty()) return emptyList()

        val response = runCatching {
            client.get(baseUrl) {
                // Fetch multiple symbols in one request — fewer requests and less information exposed
                url.parameters.append("q", symbols.joinToString(","))
            }
        }.getOrNull() ?: return emptyList()

        if (!response.status.isSuccess()) return emptyList()

        val bytes = runCatching { response.body<ByteArray>() }.getOrNull() ?: return emptyList()
        return parse(decodeLatin1(bytes), on)
    }

    /**
     * Decodes as Latin-1: each byte maps to the character at the same code point.
     *
     * ASCII bytes (price, symbol) survive completely intact; GBK's Chinese-character bytes
     * turn into meaningless characters, but we never read those fields. **Do not switch
     * this to UTF-8 decoding** — GBK byte sequences aren't valid UTF-8, and the decoder
     * would insert replacement characters that could swallow adjacent delimiters and shift
     * everything out of alignment.
     */
    internal fun decodeLatin1(bytes: ByteArray): String =
        buildString(bytes.size) {
            bytes.forEach { append(((it.toInt()) and 0xFF).toChar()) }
        }

    internal fun parse(text: String, on: LocalDate): List<Quote> {
        val now = clock.now()
        return text.split(';')
            .mapNotNull { entry -> parseEntry(entry, on, now) }
    }

    private fun parseEntry(entry: String, on: LocalDate, now: kotlin.time.Instant): Quote? {
        val trimmed = entry.trim()
        if (!trimmed.startsWith(VAR_PREFIX)) return null

        val eq = trimmed.indexOf('=')
        if (eq < 0) return null

        val symbol = trimmed.substring(VAR_PREFIX.length, eq)
        if (symbol.isEmpty()) return null

        val payload = trimmed.substring(eq + 1).trim().trim('"')
        val fields = payload.split('~')
        // Tencent returns a very short string when a symbol is suspended or doesn't exist; treat too few fields as unfetchable
        if (fields.size <= PRICE_INDEX) return null

        val price = parseUnitPrice(fields[PRICE_INDEX]) ?: return null
        // A price of 0 usually means suspended trading or an invalid symbol — treat it as unfetchable, never write a 0 price
        if (price.isZero) return null

        return Quote(
            symbol = symbol,
            asOfDay = on.toString(),
            price = price,
            currency = currencyOf(symbol),
            fetchedAt = now,
        )
    }

    companion object {
        private const val VAR_PREFIX = "v_"

        /** Index of the current price after splitting on `~`. Confirmed via real testing: 0=market flag 1=name 2=symbol **3=current price** 4=previous close 5=today's open */
        internal const val PRICE_INDEX = 3

        /**
         * Infers currency from the symbol prefix.
         *
         * This matters: when valuing, market value is converted via `asset.currency`,
         * while the price is denominated in [Quote.currency]. A mismatch between the two
         * would produce a wrong calculation, so this function is used to force alignment
         * with the asset's currency when creating a QUOTED asset.
         */
        fun currencyOf(symbol: String): String = when {
            symbol.startsWith("hk") -> "HKD"
            symbol.startsWith("us") -> "USD"
            // sh / sz symbols, as well as fund symbols, are all RMB-denominated
            else -> "CNY"
        }

        /** Whether the symbol matches a format we recognize. Used in the UI to block obviously invalid input before creating an asset. */
        fun isRecognized(symbol: String): Boolean {
            val s = symbol.trim()
            return when {
                s.startsWith("sh") || s.startsWith("sz") -> s.length == 8 && s.drop(2).all { it.isDigit() }
                s.startsWith("hk") -> s.length >= 7 && s.drop(2).all { it.isDigit() }
                s.startsWith("us") -> s.length >= 3
                else -> false
            }
        }
    }
}
