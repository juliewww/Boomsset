package com.boomsset.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * 共享的 HttpClient。
 *
 * 不需要 expect/actual：Ktor 会自动选用 classpath 上的引擎 ——
 * Android 是 OkHttp，iOS 是 Darwin（走 NSURLSession）。各平台的依赖在
 * `shared/build.gradle.kts` 里按 source set 声明。
 *
 * ## 隐私边界
 *
 * AGENTS.md 规定「网络层只出不进用户数据 —— 只拉公开行情，不发用户持仓」。
 * 这里只发币种代码和日期，**不发任何金额、份额、资产名或设备标识**。
 *
 * 但有一点必须诚实说明：**请求某个代码的价格，本身就向服务方透露了用户持有它。**
 * 这是任何取价功能的固有性质，不是本实现的缺陷。汇率没这个问题（币种不敏感），
 * 但将来接股票行情时，"我查了 600519" 是可推断的信息。
 * 若要消除，需要批量拉取全市场或走中间层 —— 那是另一个量级的工程。
 */
internal fun createHttpClient(): HttpClient = HttpClient {
    expectSuccess = false  // 自己处理状态码，不靠异常控制流程

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                // 汇率不能走 Double —— 用 JsonPrimitive 拿原始字符串再定点解析
                isLenient = false
            },
        )
    }

    install(HttpTimeout) {
        // 取价失败不该让界面卡住：宁可用 stale 价格也不要转圈
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 5_000
    }
}
