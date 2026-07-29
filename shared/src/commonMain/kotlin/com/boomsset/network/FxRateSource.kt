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
     * 拉取 [from] → [to] 在 [on] 当天（或之前最近营业日）的汇率。
     *
     * @return 成功则返回 [FxRate]（**注意 asOfDay 是服务方实际返回的日期**），
     *   失败返回 null —— 调用方应当退回到已存的 stale 汇率，而不是把资产当成无法估值。
     */
    suspend fun fetch(from: String, to: String, on: LocalDate): FxRate?
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
 */
class FrankfurterFxRateSource(
    private val client: HttpClient,
    private val baseUrl: String = "https://api.frankfurter.dev/v1",
) : FxRateSource {

    override suspend fun fetch(from: String, to: String, on: LocalDate): FxRate? {
        if (from == to) return FxRate(from, to, on.toString(), ExchangeRate.IDENTITY)

        val response = runCatching {
            client.get("$baseUrl/$on") {
                url.parameters.append("base", from)
                url.parameters.append("symbols", to)
            }
        }.getOrNull() ?: return null

        if (!response.status.isSuccess()) return null

        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }
            .getOrNull() ?: return null

        return parse(body, from, to)
    }

    internal fun parse(body: JsonObject, from: String, to: String): FxRate? {
        // 用服务方返回的日期，不用请求的日期 —— 见类注释第 1 点
        val actualDay = body["date"]?.jsonPrimitive?.content ?: return null

        val rateText = body["rates"]
            ?.jsonObject
            ?.get(to)
            ?.jsonPrimitive
            ?.content
            ?: return null

        // 从原始字符串定点解析，**不经过 Double** —— 汇率会参与净值累加
        val rate = parseExchangeRate(rateText) ?: return null

        return FxRate(base = from, quote = to, asOfDay = actualDay, rate = rate)
    }
}
