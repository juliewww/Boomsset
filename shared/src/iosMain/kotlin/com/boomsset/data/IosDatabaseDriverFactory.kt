package com.boomsset.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.boomsset.db.BoomssetDatabase

class IosDatabaseDriverFactory : DatabaseDriverFactory {
    override fun create(): SqlDriver =
        NativeSqliteDriver(
            schema = BoomssetDatabase.Schema,
            name = DATABASE_NAME,
        )
}
