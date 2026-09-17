package com.boomsset.di

import com.boomsset.data.DatabaseDriverFactory
import com.boomsset.data.IosDatabaseDriverFactory
import com.boomsset.security.AppLockAuthenticator
import com.boomsset.security.IosAppLockAuthenticator
import org.koin.dsl.module

val iosModule = module {
    single<DatabaseDriverFactory> { IosDatabaseDriverFactory() }
    single<AppLockAuthenticator> { IosAppLockAuthenticator() }
}

/** Called from Swift. In Swift this is `KoinKt.doInitKoinIos()`. */
fun initKoinIos() = initKoin(iosModule)
