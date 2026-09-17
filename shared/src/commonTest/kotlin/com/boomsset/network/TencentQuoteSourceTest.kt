package com.boomsset.network

import com.boomsset.domain.Quantity
import com.boomsset.domain.UnitPrice
import com.boomsset.domain.Money
import com.boomsset.domain.valueAt
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Uses MockEngine, never hits the real network. The response bytes were captured from the real endpoint.
 */
class TencentQuoteSourceTest {

    private val day = LocalDate(2026, 7, 29)
    private val fixedClock = object : Clock {
        override fun now() = Instant.fromEpochMilliseconds(1_785_000_000_000)
    }

    private fun source(engine: MockEngine) =
        TencentQuoteSource(HttpClient(engine), baseUrl = "https://fake", clock = fixedClock)

    private fun bytesEngine(bytes: ByteArray) = MockEngine {
        respond(content = bytes, status = HttpStatusCode.OK)
    }

    /**
     * The bytes of a real response. The Chinese name "贵州茅台" (Kweichow Moutai) is
     * b9f3 d6dd c3a9 cca8 in GBK -- these bytes are not valid UTF-8, which is exactly the point of this test.
     */
    private fun maotaiBytes(): ByteArray {
        val prefix = "v_sh600519=\"1~"
        val gbkName = byteArrayOf(
            0xB9.toByte(), 0xF3.toByte(), 0xD6.toByte(), 0xDD.toByte(),
            0xC3.toByte(), 0xA9.toByte(), 0xCC.toByte(), 0xA8.toByte(),
        )
        val suffix = "~600519~1329.62~1320.00~1333.83~26087~14371~11715~1328.71~\";"
        return prefix.encodeToByteArray() + gbkName + suffix.encodeToByteArray()
    }

    @Test
    fun `a GBK-encoded Chinese name does not break price parsing`() = runTest {
        // This is the entire reason for choosing Latin-1 decoding: GBK bytes are not valid
        // UTF-8, and decoding as UTF-8 would insert replacement characters, potentially
        // swallowing an adjacent ~ delimiter and shifting the fields out of place.
        val quotes = source(bytesEngine(maotaiBytes())).fetch(setOf("sh600519"), day)

        quotes shouldHaveSize 1
        quotes.single().symbol shouldBe "sh600519"
        quotes.single().price shouldBe UnitPrice(1329_62000000L)  // 1329.62
        quotes.single().currency shouldBe "CNY"
        quotes.single().asOfDay shouldBe "2026-07-29"
    }

    @Test
    fun `a batch response is parsed entry by entry`() = runTest {
        val body = """
            v_sh600519="1~X~600519~1329.22~1320.00~";
            v_sz000858="51~Y~000858~75.35~74.00~";
            v_hk00700="100~Z~00700~462.400~460.00~";
        """.trimIndent()

        val quotes = source(bytesEngine(body.encodeToByteArray())).fetch(
            setOf("sh600519", "sz000858", "hk00700"), day,
        )

        quotes shouldHaveSize 3
        quotes.first { it.symbol == "hk00700" }.currency shouldBe "HKD"
        quotes.first { it.symbol == "sz000858" }.currency shouldBe "CNY"
    }

    @Test
    fun `Hong Kong stock 3-decimal prices keep full precision`() = runTest {
        // Tencent Holdings quotes 462.400. If unit price were stored as scale-2 Money, this would be rejected or truncated.
        val body = """v_hk00700="100~Z~00700~462.400~460.00~";"""
        val quote = source(bytesEngine(body.encodeToByteArray())).fetch(setOf("hk00700"), day)
            .single()

        quote.price shouldBe UnitPrice(462_40000000L)
        // 100 shares x 462.40 = 46240.00 HKD
        Quantity.ofUnits(100).valueAt(quote.price) shouldBe Money(46_240_00)
    }

    // ---------- Failure paths: if it can't be fetched, it can't be fetched -- never write a price of 0 ----------

    @Test
    fun `a zero price for a suspended or invalid symbol is discarded, not written`() = runTest {
        // Writing a price of 0 would silently zero out that asset -- much worse than "cannot be valued"
        val body = """v_sh000000="1~X~000000~0.00~0.00~";"""
        source(bytesEngine(body.encodeToByteArray())).fetch(setOf("sh000000"), day).shouldBeEmpty()
    }

    @Test
    fun `a short response with too few fields is discarded`() = runTest {
        val body = """v_shbad="1~";"""
        source(bytesEngine(body.encodeToByteArray())).fetch(setOf("shbad"), day).shouldBeEmpty()
    }

    @Test
    fun `an HTTP error returns an empty list instead of throwing`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        source(engine).fetch(setOf("sh600519"), day).shouldBeEmpty()
    }

    @Test
    fun `an empty symbol set sends no request`() = runTest {
        var called = false
        val engine = MockEngine { called = true; respondError(HttpStatusCode.InternalServerError) }
        source(engine).fetch(emptySet(), day).shouldBeEmpty()
        called shouldBe false
    }

    // ---------- Symbol format and currency ----------

    @Test
    fun `currency is determined by the symbol's prefix`() {
        TencentQuoteSource.currencyOf("sh600519") shouldBe "CNY"
        TencentQuoteSource.currencyOf("sz000858") shouldBe "CNY"
        TencentQuoteSource.currencyOf("hk00700") shouldBe "HKD"
        TencentQuoteSource.currencyOf("usAAPL") shouldBe "USD"
    }

    @Test
    fun `symbol format validation blocks obvious mistakes`() {
        TencentQuoteSource.isRecognized("sh600519") shouldBe true
        TencentQuoteSource.isRecognized("hk00700") shouldBe true
        TencentQuoteSource.isRecognized("usAAPL") shouldBe true
        // Missing prefix, wrong digit count, and plain Chinese text are all rejected
        TencentQuoteSource.isRecognized("600519") shouldBe false
        TencentQuoteSource.isRecognized("sh60051") shouldBe false
        TencentQuoteSource.isRecognized("贵州茅台") shouldBe false
        TencentQuoteSource.isRecognized("") shouldBe false
    }
}
