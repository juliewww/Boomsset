package com.boomsset.network

import com.boomsset.domain.ExchangeRate
import io.kotest.matchers.nulls.shouldBeNull
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
 * 用 MockEngine，**测试绝不打真网络**。
 *
 * 这里的响应报文是从真实 API 抓下来的（`api.frankfurter.dev`），
 * 包括周末那个案例 —— 那不是我编的，是实测确认的行为。
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
    fun `解析正常响应`() = runTest {
        val body = """{"amount":1.0,"base":"USD","date":"2026-06-30","rates":{"CNY":6.7855}}"""
        val rate = source(jsonEngine(body)).fetch("USD", "CNY", LocalDate(2026, 6, 30))!!

        rate.base shouldBe "USD"
        rate.quote shouldBe "CNY"
        rate.asOfDay shouldBe "2026-06-30"
        // 6.7855 → scale 8 定点：678550000。**没有经过 Double。**
        rate.rate shouldBe ExchangeRate(678_550_000)
    }

    @Test
    fun `周末请求时存服务方返回的营业日而不是请求日`() = runTest {
        // 实测：请求 2026-07-26（周日），ECB 没有数据，返回 07-24（周五）
        val body = """{"amount":1.0,"base":"USD","date":"2026-07-24","rates":{"CNY":6.7722}}"""
        val requested = LocalDate(2026, 7, 26)

        val rate = source(jsonEngine(body)).fetch("USD", "CNY", requested)!!

        // 关键断言：存的是 07-24，不是请求的 07-26。
        // 存请求日期会把汇率错误归到 ECB 从未发布的那一天。
        rate.asOfDay shouldBe "2026-07-24"
        rate.asOfDay shouldNotBe requested.toString()
    }

    @Test
    fun `汇率精度不因浮点丢失`() = runTest {
        // 一个 Double 表示不精确的值
        val body = """{"amount":1.0,"base":"USD","date":"2026-07-01","rates":{"CNY":7.12345678}}"""
        val rate = source(jsonEngine(body)).fetch("USD", "CNY", LocalDate(2026, 7, 1))!!
        rate.rate shouldBe ExchangeRate(712_345_678)
    }

    @Test
    fun `同币种直接返回一比一不发请求`() = runTest {
        var called = false
        val engine = MockEngine { called = true; respondError(HttpStatusCode.InternalServerError) }

        val rate = source(engine).fetch("CNY", "CNY", LocalDate(2026, 7, 1))!!

        rate.rate shouldBe ExchangeRate.IDENTITY
        called shouldBe false
    }

    // ---------- 失败路径：一律返回 null，让调用方退回 stale 汇率 ----------

    @Test
    fun `HTTP 错误返回null而不是抛异常`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests) }
        source(engine).fetch("USD", "CNY", LocalDate(2026, 7, 1)).shouldBeNull()
    }

    @Test
    fun `不支持的币种返回null而不是当成一比一`() = runTest {
        // TWD 不在 ECB 列表里，rates 里没有它。
        // 绝不能退化成 1:1 —— 那会把台币资产按人民币等额计入净值。
        val body = """{"amount":1.0,"base":"TWD","date":"2026-07-01","rates":{}}"""
        source(jsonEngine(body)).fetch("TWD", "CNY", LocalDate(2026, 7, 1)).shouldBeNull()
    }

    @Test
    fun `响应不是合法JSON时返回null`() = runTest {
        source(jsonEngine("not json at all")).fetch("USD", "CNY", LocalDate(2026, 7, 1))
            .shouldBeNull()
    }

    @Test
    fun `响应缺date字段时返回null`() = runTest {
        val body = """{"amount":1.0,"base":"USD","rates":{"CNY":6.78}}"""
        source(jsonEngine(body)).fetch("USD", "CNY", LocalDate(2026, 7, 1)).shouldBeNull()
    }

    private infix fun String.shouldNotBe(other: String) {
        if (this == other) throw AssertionError("期望不等于 $other，但实际相等")
    }
}
