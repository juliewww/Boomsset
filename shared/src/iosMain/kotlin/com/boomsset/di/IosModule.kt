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

/** 从 Swift 调这个。Swift 里是 `KoinKt.doInitKoinIos()`。 */
fun initKoinIos() = initKoin(iosModule)
