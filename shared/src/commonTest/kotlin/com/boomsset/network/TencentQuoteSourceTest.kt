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
 * 用 MockEngine，不打真网络。响应字节是从真实接口抓下来的。
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
     * 真实报文的字节。中文名「贵州茅台」在 GBK 下是 b9f3 d6dd c3a9 cca8 ——
     * 这几个字节不是合法 UTF-8，是这个测试的重点。
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
    fun `GBK 中文名不会破坏价格解析`() = runTest {
        // 这是选 Latin-1 解码的全部理由：GBK 字节不是合法 UTF-8，
        // 用 UTF-8 解码会插入替换字符、可能吃掉相邻的 ~ 分隔符导致字段错位。
        val quotes = source(bytesEngine(maotaiBytes())).fetch(setOf("sh600519"), day)

        quotes shouldHaveSize 1
        quotes.single().symbol shouldBe "sh600519"
        quotes.single().price shouldBe UnitPrice(1329_62000000L)  // 1329.62
        quotes.single().currency shouldBe "CNY"
        quotes.single().asOfDay shouldBe "2026-07-29"
    }

    @Test
    fun `批量响应逐条解析`() = runTest {
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
    fun `港股三位小数价格不丢精度`() = runTest {
        // 腾讯控股报 462.400。若单价用 scale-2 的 Money 存，这里会被拒或截断。
        val body = """v_hk00700="100~Z~00700~462.400~460.00~";"""
        val quote = source(bytesEngine(body.encodeToByteArray())).fetch(setOf("hk00700"), day)
            .single()

        quote.price shouldBe UnitPrice(462_40000000L)
        // 100 股 × 462.40 = 46240.00 港币
        Quantity.ofUnits(100).valueAt(quote.price) shouldBe Money(46_240_00)
    }

    // ---------- 失败路径：取不到就是取不到，绝不写 0 价 ----------

    @Test
    fun `停牌或无效代码返回的零价被丢弃而不是写入`() = runTest {
        // 写 0 价会让该资产静默归零 —— 比"无法估值"糟糕得多
        val body = """v_sh000000="1~X~000000~0.00~0.00~";"""
        source(bytesEngine(body.encodeToByteArray())).fetch(setOf("sh000000"), day).shouldBeEmpty()
    }

    @Test
    fun `字段不足的短响应被丢弃`() = runTest {
        val body = """v_shbad="1~";"""
        source(bytesEngine(body.encodeToByteArray())).fetch(setOf("shbad"), day).shouldBeEmpty()
    }

    @Test
    fun `HTTP 错误返回空列表而不是抛异常`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        source(engine).fetch(setOf("sh600519"), day).shouldBeEmpty()
    }

    @Test
    fun `空代码集合不发请求`() = runTest {
        var called = false
        val engine = MockEngine { called = true; respondError(HttpStatusCode.InternalServerError) }
        source(engine).fetch(emptySet(), day).shouldBeEmpty()
        called shouldBe false
    }

    // ---------- 代码格式与币种 ----------

    @Test
    fun `币种由代码前缀决定`() {
        TencentQuoteSource.currencyOf("sh600519") shouldBe "CNY"
        TencentQuoteSource.currencyOf("sz000858") shouldBe "CNY"
        TencentQuoteSource.currencyOf("hk00700") shouldBe "HKD"
        TencentQuoteSource.currencyOf("usAAPL") shouldBe "USD"
    }

    @Test
    fun `代码格式校验挡住明显错误`() {
        TencentQuoteSource.isRecognized("sh600519") shouldBe true
        TencentQuoteSource.isRecognized("hk00700") shouldBe true
        TencentQuoteSource.isRecognized("usAAPL") shouldBe true
        // 缺前缀、位数不对、纯中文都挡掉
        TencentQuoteSource.isRecognized("600519") shouldBe false
        TencentQuoteSource.isRecognized("sh60051") shouldBe false
        TencentQuoteSource.isRecognized("贵州茅台") shouldBe false
        TencentQuoteSource.isRecognized("") shouldBe false
    }
}
