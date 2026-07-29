package com.boomsset.network

import com.boomsset.domain.Quote
import com.boomsset.domain.parseUnitPrice
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/** 取股票/基金行情。实现可替换 —— 测试用 fake，将来换供应商只改这一层。 */
interface QuoteSource {
    /**
     * 批量取价。
     *
     * @return 成功取到的行情。**取不到的代码不出现在返回值里**，不返回 0 价 ——
     *   0 价会让资产静默归零。
     */
    suspend fun fetch(symbols: Set<String>, on: LocalDate): List<Quote>
}

/**
 * 腾讯财经行情（`qt.gtimg.cn`）。
 *
 * ## 为什么用它，以及风险
 *
 * A 股和场内基金的行情没有像 ECB 汇率那样的官方免费源 —— 交易所数据有授权成本。
 * 实测下来（2026-07）腾讯这个接口是唯一无需 key 且 A 股覆盖完整的可用选项：
 * 新浪返回 403、天天基金返回 HTML、Yahoo 非官方接口 429 限流。
 *
 * ⚠️ **这是非官方接口**：公开无鉴权，但没有文档、没有 ToS 保障、可能随时变更或失效。
 * 产品定位是自用/小范围，这个风险是明确接受的。失效时的表现是**取不到价 → 资产显示
 * "无法估值"**，不会静默算错数 —— 这一点由 [QuoteSource] 的契约保证。
 *
 * ## 代码格式
 *
 * - A 股：`sh600519`（上交所）、`sz000858`（深交所）
 * - 港股：`hk00700`
 * - 美股：`usAAPL`
 *
 * 前缀决定币种，见 [currencyOf]。
 *
 * ## 响应格式与 GBK
 *
 * 返回形如：`v_sh600519="1~贵州茅台~600519~1329.62~1320.00~...";`
 * 字段按 `~` 分隔，**索引 3 是当前价**。
 *
 * **报文是 GBK 编码的**，而 Kotlin/Native 没有内置 GBK 解码器。做法是把字节按
 * Latin-1（byte → char 一一对应）读进来 —— 这样 ASCII 部分（价格、代码、日期）
 * 完全无损，只有中文名称字段会是乱码，而我们**根本不用那个字段**（资产名是用户自己起的）。
 * 这比引入一套 GBK 码表简单得多，也没有解码失败的可能。
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
                // 一次请求拿多只，少发请求也少暴露信息
                url.parameters.append("q", symbols.joinToString(","))
            }
        }.getOrNull() ?: return emptyList()

        if (!response.status.isSuccess()) return emptyList()

        val bytes = runCatching { response.body<ByteArray>() }.getOrNull() ?: return emptyList()
        return parse(decodeLatin1(bytes), on)
    }

    /**
     * 按 Latin-1 解码：每个字节映射成同码位的字符。
     *
     * ASCII 字节（价格、代码）完全无损；GBK 的中文字节会变成无意义字符，但我们不读那些字段。
     * **不要改成 UTF-8 解码** —— GBK 字节序列不是合法 UTF-8，解码器会插入替换字符，
     * 可能吃掉相邻的分隔符从而错位。
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
        // 停牌或代码不存在时腾讯返回极短的串，字段不够就当取不到
        if (fields.size <= PRICE_INDEX) return null

        val price = parseUnitPrice(fields[PRICE_INDEX]) ?: return null
        // 价格为 0 通常意味着停牌或无效代码 —— 当作取不到，绝不写 0 价
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

        /** `~` 分隔后当前价的下标。实测确认：0=市场标志 1=名称 2=代码 **3=当前价** 4=昨收 5=今开 */
        internal const val PRICE_INDEX = 3

        /**
         * 从代码前缀推断币种。
         *
         * 这很重要：估值时市值按 `asset.currency` 折算，而价格是 [Quote.currency] 计价的。
         * 两者不一致就会算错，所以创建 QUOTED 资产时要用这个函数强制对齐资产币种。
         */
        fun currencyOf(symbol: String): String = when {
            symbol.startsWith("hk") -> "HKD"
            symbol.startsWith("us") -> "USD"
            // sh / sz 以及基金代码都是人民币计价
            else -> "CNY"
        }

        /** 代码是否是我们认得的格式。UI 上用来在创建资产前挡住明显的错误输入。 */
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
