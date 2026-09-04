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
    fun `解析区间响应`() = runTest {
        // 区间报文的 rates 是**两层**（日期 → 币种 → 汇率），和单日的一层不一样
        val body = """{"amount":1.0,"base":"CNY","start_date":"2026-06-29","end_date":"2026-07-01",
            "rates":{"2026-06-29":{"USD":0.14719},"2026-06-30":{"USD":0.14737},
            "2026-07-01":{"USD":0.14718}}}"""
        val rates = source(jsonEngine(body))
            .fetchRange("CNY", "USD", LocalDate(2026, 6, 29), LocalDate(2026, 7, 1))

        rates.size shouldBe 3
        rates.map { it.asOfDay } shouldBe
            listOf("2026-06-29", "2026-06-30", "2026-07-01")
        // 0.14719 → scale 8 定点。**没有经过 Double。**
        rates.first().rate shouldBe ExchangeRate(14_719_000)
        rates.first().base shouldBe "CNY"
        rates.first().quote shouldBe "USD"
    }

    @Test
    fun `日期取自报文的key而不是请求的区间`() = runTest {
        // 实测：请求 2026-08-29..2026-08-30（周六周日），ECB 没有数据，
        // 服务方把区间挪到 08-28（周五）再返回。存请求日期会把汇率错误归到
        // ECB 从未发布的那两天
        val body = """{"amount":1.0,"base":"CNY","start_date":"2026-08-28",
            "end_date":"2026-08-28","rates":{"2026-08-28":{"USD":0.14879}}}"""

        val rates = source(jsonEngine(body))
            .fetchRange("CNY", "USD", LocalDate(2026, 8, 29), LocalDate(2026, 8, 30))

        rates.single().asOfDay shouldBe "2026-08-28"
    }

    @Test
    fun `一天的区间也走同一条路径`() = runTest {
        // start == end 是合法请求（实测），所以不需要再留一个单日接口
        val body = """{"amount":1.0,"base":"CNY","start_date":"2026-09-03",
            "end_date":"2026-09-03","rates":{"2026-09-03":{"USD":0.14883}}}"""

        val rates = source(jsonEngine(body))
            .fetchRange("CNY", "USD", LocalDate(2026, 9, 3), LocalDate(2026, 9, 3))

        rates.single().asOfDay shouldBe "2026-09-03"
        rates.single().rate shouldBe ExchangeRate(14_883_000)
    }

    @Test
    fun `汇率精度不因浮点丢失`() = runTest {
        // 一个 Double 表示不精确的值
        val body = """{"amount":1.0,"base":"USD","start_date":"2026-07-01",
            "end_date":"2026-07-01","rates":{"2026-07-01":{"CNY":7.12345678}}}"""
        val rates = source(jsonEngine(body))
            .fetchRange("USD", "CNY", LocalDate(2026, 7, 1), LocalDate(2026, 7, 1))

        rates.single().rate shouldBe ExchangeRate(712_345_678)
    }

    @Test
    fun `同币种不发请求也不造记录`() = runTest {
        var called = false
        val engine = MockEngine { called = true; respondError(HttpStatusCode.InternalServerError) }

        // 估值层对同币种直接用 IDENTITY，不需要落库
        source(engine).fetchRange("CNY", "CNY", LocalDate(2026, 7, 1), LocalDate(2026, 7, 2))
            .isEmpty() shouldBe true
        called shouldBe false
    }

    @Test
    fun `区间反过来时不发请求`() = runTest {
        var called = false
        val engine = MockEngine { called = true; respondError(HttpStatusCode.InternalServerError) }

        source(engine).fetchRange("USD", "CNY", LocalDate(2026, 7, 5), LocalDate(2026, 7, 1))
            .isEmpty() shouldBe true
        called shouldBe false
    }

    // ---------- 失败路径：一律返回空列表，让调用方退回 stale 汇率 ----------

    @Test
    fun `HTTP 错误返回空列表而不是抛异常`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests) }
        source(engine).fetchRange("USD", "CNY", LocalDate(2026, 7, 1), LocalDate(2026, 7, 2))
            .isEmpty() shouldBe true
    }

    @Test
    fun `不支持的币种返回空列表而不是当成一比一`() = runTest {
        // TWD 不在 ECB 列表里，每天的 map 里都没有它。
        // 绝不能退化成 1:1 —— 那会把台币资产按人民币等额计入净值。
        val body = """{"amount":1.0,"base":"TWD","start_date":"2026-07-01",
            "end_date":"2026-07-02","rates":{"2026-07-01":{},"2026-07-02":{}}}"""
        source(jsonEngine(body))
            .fetchRange("TWD", "CNY", LocalDate(2026, 7, 1), LocalDate(2026, 7, 2))
            .isEmpty() shouldBe true
    }

    @Test
    fun `响应不是合法JSON时返回空列表`() = runTest {
        source(jsonEngine("not json at all"))
            .fetchRange("USD", "CNY", LocalDate(2026, 7, 1), LocalDate(2026, 7, 2))
            .isEmpty() shouldBe true
    }

    @Test
    fun `响应缺rates字段时返回空列表`() = runTest {
        val body = """{"amount":1.0,"base":"USD","start_date":"2026-07-01"}"""
        source(jsonEngine(body))
            .fetchRange("USD", "CNY", LocalDate(2026, 7, 1), LocalDate(2026, 7, 2))
            .isEmpty() shouldBe true
    }

}
