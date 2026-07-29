package com.boomsset.di

import com.boomsset.data.DatabaseDriverFactory
import com.boomsset.data.IosDatabaseDriverFactory
import org.koin.dsl.module

val iosModule = module {
    single<DatabaseDriverFactory> { IosDatabaseDriverFactory() }
}

/** 从 Swift 调这个。Swift 里是 `KoinKt.doInitKoinIos()`。 */
fun initKoinIos() = initKoin(iosModule)
