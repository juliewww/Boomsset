package com.boomsset.data

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform abstraction for the SQLDelight driver.
 *
 * Uses an **interface + per-platform implementation classes** instead of `expect class`:
 * the latter is still Beta in Kotlin 2.4 (KT-61573), and the Android implementation needs
 * a `Context` while iOS doesn't — an interface is more natural when constructor parameters
 * differ. The concrete implementations are provided by each platform's Koin module.
 */
interface DatabaseDriverFactory {
    fun create(): SqlDriver
}

internal const val DATABASE_NAME = "boomsset.db"
