package com.boomsset.data

import app.cash.sqldelight.db.SqlDriver

/**
 * SQLDelight driver 的平台抽象。
 *
 * 用**接口 + 各平台实现类**而不是 `expect class`：后者在 Kotlin 2.4 仍是 Beta（KT-61573），
 * 而且 Android 实现需要 `Context`、iOS 不需要 —— 构造参数不同的情况用接口更自然。
 * 具体实现由 Koin 在各平台的 module 里提供。
 */
interface DatabaseDriverFactory {
    fun create(): SqlDriver
}

internal const val DATABASE_NAME = "boomsset.db"
