package com.boomsset.di

import android.content.Context
import com.boomsset.data.AndroidDatabaseDriverFactory
import com.boomsset.data.DatabaseDriverFactory
import com.boomsset.security.AndroidAppLockAuthenticator
import com.boomsset.security.AppLockAuthenticator
import org.koin.dsl.module

fun androidModule(context: Context) = module {
    single<DatabaseDriverFactory> { AndroidDatabaseDriverFactory(context) }
    single<AppLockAuthenticator> { AndroidAppLockAuthenticator(context) }
}
