package com.boomsset.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.boomsset.db.BoomssetDatabase

class AndroidDatabaseDriverFactory(private val context: Context) : DatabaseDriverFactory {
    override fun create(): SqlDriver =
        AndroidSqliteDriver(
            schema = BoomssetDatabase.Schema,
            context = context,
            name = DATABASE_NAME,
        )
}
