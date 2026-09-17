package com.boomsset.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * The shared HttpClient.
 *
 * No expect/actual needed: Ktor automatically picks the engine available on the classpath
 * — OkHttp on Android, Darwin (via NSURLSession) on iOS. Each platform's dependency is
 * declared per source set in `shared/build.gradle.kts`.
 *
 * ## Privacy boundary
 *
 * AGENTS.md mandates that "the network layer only sends out, never takes in, user data —
 * it only fetches public market data, and never sends user holdings." Here we only send
 * currency codes and dates, **never any amount, quantity, asset name, or device
 * identifier**.
 *
 * One thing must be stated honestly, though: **requesting the price of a given symbol
 * inherently reveals to the service provider that the user holds it.** This is an
 * inherent property of any price-fetching feature, not a flaw in this implementation. FX
 * rates don't have this problem (currencies aren't sensitive), but once stock quotes are
 * wired in, "I looked up 600519" becomes inferable information. Eliminating this would
 * require batch-fetching the entire market or routing through a middle layer — that's an
 * engineering effort of an entirely different scale.
 */
internal fun createHttpClient(): HttpClient = HttpClient {
    expectSuccess = false  // Handle status codes ourselves; don't rely on exceptions for control flow

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                // FX rates must never go through Double — take the raw string via JsonPrimitive and parse it as fixed-point
                isLenient = false
            },
        )
    }

    install(HttpTimeout) {
        // A failed price fetch shouldn't freeze the UI: better to use a stale price than spin forever
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 5_000
    }
}
