package com.boomsset.network

import com.boomsset.domain.ExchangeRate
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test

/**
 * Uses MockEngine -- **tests must never hit the real network**.
 *
 * The response payloads here were captured from the real API (`api.frankfurter.dev`),
 * including the weekend case -- that wasn't made up, it's behavior confirmed by a real test run.
 */
class FrankfurterFxRateSourceTest {

    private fun source(handler: MockEngine) =
        FrankfurterFxRateSource(HttpClient(handler), baseUrl = "https://fake/v1")

    private fun jsonEngine(body: String) = MockEngine {
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    @Test
    fun `parses a date-range response`() = runTest {
        // A range response's rates are **two levels deep** (date -> currency -> rate), unlike a single-day response's one level
        val body = """{"amount":1.0,"base":"CNY","start_date":"2026-06-29","end_date":"2026-07-01",
            "rates":{"2026-06-29":{"USD":0.14719},"2026-06-30":{"USD":0.14737},
            "2026-07-01":{"USD":0.14718}}}"""
        val rates = source(jsonEngine(body))
            .fetchRange("CNY", "USD", LocalDate(2026, 6, 29), LocalDate(2026, 7, 1))

        rates.size shouldBe 3
        rates.map { it.asOfDay } shouldBe
            listOf("2026-06-29", "2026-06-30", "2026-07-01")
        // 0.14719 -> scale-8 fixed point. **Never passes through a Double.**
        rates.first().rate shouldBe ExchangeRate(14_719_000)
        rates.first().base shouldBe "CNY"
        rates.first().quote shouldBe "USD"
    }

    @Test
    fun `the date is taken from the response's key, not the requested range`() = runTest {
        // Observed behavior: requesting 2026-08-29..2026-08-30 (Saturday/Sunday), the ECB has
        // no data, and the service shifts the range back to 08-28 (Friday) before responding.
        // Storing the requested date would misattribute the rate to two days the ECB never published
        val body = """{"amount":1.0,"base":"CNY","start_date":"2026-08-28",
            "end_date":"2026-08-28","rates":{"2026-08-28":{"USD":0.14879}}}"""

        val rates = source(jsonEngine(body))
            .fetchRange("CNY", "USD", LocalDate(2026, 8, 29), LocalDate(2026, 8, 30))

        rates.single().asOfDay shouldBe "2026-08-28"
    }

    @Test
    fun `a one-day range goes through the same code path`() = runTest {
        // start == end is a valid request (confirmed by testing), so there's no need to keep a separate single-day endpoint
        val body = """{"amount":1.0,"base":"CNY","start_date":"2026-09-03",
            "end_date":"2026-09-03","rates":{"2026-09-03":{"USD":0.14883}}}"""

        val rates = source(jsonEngine(body))
            .fetchRange("CNY", "USD", LocalDate(2026, 9, 3), LocalDate(2026, 9, 3))

        rates.single().asOfDay shouldBe "2026-09-03"
        rates.single().rate shouldBe ExchangeRate(14_883_000)
    }

    @Test
    fun `exchange rate precision is not lost to floating point`() = runTest {
        // A value that a Double cannot represent exactly
        val body = """{"amount":1.0,"base":"USD","start_date":"2026-07-01",
            "end_date":"2026-07-01","rates":{"2026-07-01":{"CNY":7.12345678}}}"""
        val rates = source(jsonEngine(body))
            .fetchRange("USD", "CNY", LocalDate(2026, 7, 1), LocalDate(2026, 7, 1))

        rates.single().rate shouldBe ExchangeRate(712_345_678)
    }

    @Test
    fun `the same currency on both sides sends no request and creates no records`() = runTest {
        var called = false
        val engine = MockEngine { called = true; respondError(HttpStatusCode.InternalServerError) }

        // The valuation layer uses IDENTITY directly for same-currency pairs; nothing needs to be persisted
        source(engine).fetchRange("CNY", "CNY", LocalDate(2026, 7, 1), LocalDate(2026, 7, 2))
            .isEmpty() shouldBe true
        called shouldBe false
    }

    @Test
    fun `no request is sent when the range is reversed`() = runTest {
        var called = false
        val engine = MockEngine { called = true; respondError(HttpStatusCode.InternalServerError) }

        source(engine).fetchRange("USD", "CNY", LocalDate(2026, 7, 5), LocalDate(2026, 7, 1))
            .isEmpty() shouldBe true
        called shouldBe false
    }

    // ---------- Failure paths: always return an empty list, letting the caller fall back to a stale rate ----------

    @Test
    fun `an HTTP error returns an empty list instead of throwing`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests) }
        source(engine).fetchRange("USD", "CNY", LocalDate(2026, 7, 1), LocalDate(2026, 7, 2))
            .isEmpty() shouldBe true
    }

    @Test
    fun `an unsupported currency returns an empty list instead of being treated as 1-to-1`() = runTest {
        // TWD isn't in the ECB's list, so it's absent from every day's map.
        // It must never degrade to 1:1 -- that would count a TWD asset toward net worth at face value in CNY.
        val body = """{"amount":1.0,"base":"TWD","start_date":"2026-07-01",
            "end_date":"2026-07-02","rates":{"2026-07-01":{},"2026-07-02":{}}}"""
        source(jsonEngine(body))
            .fetchRange("TWD", "CNY", LocalDate(2026, 7, 1), LocalDate(2026, 7, 2))
            .isEmpty() shouldBe true
    }

    @Test
    fun `an invalid-JSON response returns an empty list`() = runTest {
        source(jsonEngine("not json at all"))
            .fetchRange("USD", "CNY", LocalDate(2026, 7, 1), LocalDate(2026, 7, 2))
            .isEmpty() shouldBe true
    }

    @Test
    fun `a response missing the rates field returns an empty list`() = runTest {
        val body = """{"amount":1.0,"base":"USD","start_date":"2026-07-01"}"""
        source(jsonEngine(body))
            .fetchRange("USD", "CNY", LocalDate(2026, 7, 1), LocalDate(2026, 7, 2))
            .isEmpty() shouldBe true
    }

}
