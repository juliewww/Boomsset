package com.boomsset.di

import android.content.Context
import com.boomsset.data.AndroidDatabaseDriverFactory
import com.boomsset.data.DatabaseDriverFactory
import org.koin.dsl.module

fun androidModule(context: Context) = module {
    single<DatabaseDriverFactory> { AndroidDatabaseDriverFactory(context) }
}
