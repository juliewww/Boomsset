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

/** 取汇率。实现类可替换，测试用 fake。 */
interface FxRateSource {
    /**
     * 拉取 [from] → [to] 在 [start]..[end] 区间内每个营业日的汇率。
     *
     * **只有区间接口，没有单日接口。** 净值曲线一次要 12 个时点，各自都得用**当时的**汇率
     * （domain.md：用今天的汇率折算历史会污染曲线）。一天一个请求要几十次往返，
     * 而区间一次就够；`start == end` 时它就是"取一天"，不需要两套代码。
     *
     * @return 区间内有报价的那些天（周末/节假日不在内，服务方会自动落到前一个营业日）。
     *   **asOfDay 是服务方实际返回的日期**，不是请求的日期。
     *   失败返回空列表 —— 调用方应当退回已存的 stale 汇率，而不是把资产当成无法估值。
     */
    suspend fun fetchRange(
        from: String,
        to: String,
        start: LocalDate,
        end: LocalDate,
    ): List<FxRate>
}

/**
 * 基于 [Frankfurter](https://frankfurter.dev) 的实现。ECB 官方数据，无需 API key，
 * **支持历史日期** —— 这是选它的决定性理由，因为领域模型要求"折算历史净值用当时的汇率"。
 *
 * 实测确认的两件事：
 *
 * 1. **周末/节假日会返回实际营业日。** 请求 2026-07-26（周日）时响应里
 *    `"date":"2026-07-24"`。所以**必须存响应里的 date，不能存请求的日期** ——
 *    存请求日期会把汇率错误归到 ECB 从未发布的那一天。
 * 2. **只有 30 种币种，TWD 不在其中。** CNY/USD/HKD/EUR/JPY/GBP/SGD/AUD/KRW 都支持。
 *    不支持的币种会取不到汇率，对应资产会显示"无法估值"（而不是静默按 1:1 折算）。
 *
 * 已知限制：ECB 数据从 1999 年起，但**只有工作日**。这对我们无影响 ——
 * 结转规则本来就是"取该时点前最近的一条"。
 *
 * 区间接口 `GET /v1/{start}..{end}` 的报文和单日的**不一样**：`rates` 是
 * "日期 → {币种: 汇率}" 两层，而不是一层。实测确认的三件事：
 * - `start == end` 合法，返回那一天的一条
 * - 请求区间**整段都是周末**时，服务方把区间挪到前一个营业日再返回（不会返回空）
 * - 十年区间（约 2560 个营业日）响应约 74KB，一次性回补是可以接受的
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
        // 同币种 1:1，不需要（也不该）向外发请求。估值层对同币种直接用 IDENTITY，
        // 连这条记录都不需要落库，所以返回空列表而不是造一条假数据
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
            // 从原始字符串定点解析，**不经过 Double** —— 汇率会参与净值累加
            val rate = parseExchangeRate(rateText) ?: return@mapNotNull null
            // 日期取自报文的 key，也就是服务方实际发布那天 —— 见类注释第 1 点
            FxRate(base = from, quote = to, asOfDay = day, rate = rate)
        }
    }
}
