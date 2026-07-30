package com.boomsset.di

import com.boomsset.data.DatabaseDriverFactory
import com.boomsset.data.PortfolioRepository
import com.boomsset.data.RateRefresher
import com.boomsset.data.SettingsRepository
import com.boomsset.data.SqlDelightPortfolioRepository
import com.boomsset.data.SqlDelightSettingsRepository
import com.boomsset.network.FrankfurterFxRateSource
import com.boomsset.network.FxRateSource
import com.boomsset.network.QuoteSource
import com.boomsset.network.TencentQuoteSource
import com.boomsset.network.createHttpClient
import com.boomsset.security.AppLockViewModel
import com.boomsset.data.createDatabase
import com.boomsset.ui.allocation.AllocationViewModel
import com.boomsset.ui.assets.AssetListViewModel
import com.boomsset.ui.networth.NetWorthViewModel
import kotlinx.coroutines.Dispatchers
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * 共享的 DI 图。平台特有的绑定（[DatabaseDriverFactory]）由各平台的 module 提供。
 *
 * ⚠️ ViewModel **必须显式给 initializer**（这里是 `factory { }`）——
 * Kotlin/Native 没有反射，不能靠 `viewModel()` 自动构造。见 AGENTS.md 约束 2。
 */
val sharedModule: Module = module {
    single { createDatabase(get<DatabaseDriverFactory>()) }

    single<PortfolioRepository> {
        SqlDelightPortfolioRepository(
            db = get(),
            dispatcher = Dispatchers.Default,
            clock = Clock.System,
        )
    }

    single<SettingsRepository> {
        SqlDelightSettingsRepository(db = get(), dispatcher = Dispatchers.Default)
    }

    single { createHttpClient() }
    single<FxRateSource> { FrankfurterFxRateSource(client = get()) }
    single<QuoteSource> { TencentQuoteSource(client = get()) }
    single {
        RateRefresher(
            repository = get(),
            fxSource = get(),
            quoteSource = get(),
            dispatcher = Dispatchers.Default,
        )
    }

    factory {
        NetWorthViewModel(repository = get(), settings = get(), rateRefresher = get())
    }
    factory { AllocationViewModel(repository = get(), settings = get()) }
    factory { AssetListViewModel(repository = get(), settings = get()) }
    factory { AppLockViewModel(settings = get(), authenticator = get()) }
}

/**
 * 两端共用的启动入口。Android 传入包含 Context 的 module，iOS 传入不需要参数的那个。
 */
fun initKoin(platformModule: Module, appDeclaration: KoinAppDeclaration = {}) = startKoin {
    appDeclaration()
    modules(sharedModule, platformModule)
}
