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
 * The shared DI graph. Platform-specific bindings (like [DatabaseDriverFactory]) are
 * provided by each platform's own module.
 *
 * ⚠️ ViewModels **must be given an explicit initializer** (here, `factory { }`) —
 * Kotlin/Native has no reflection, so they can't be auto-constructed via `viewModel()`.
 * See AGENTS.md constraint 2.
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
 * The startup entry point shared by both platforms. Android passes in the module that
 * includes a Context; iOS passes in the one that needs no parameters.
 */
fun initKoin(platformModule: Module, appDeclaration: KoinAppDeclaration = {}) = startKoin {
    appDeclaration()
    modules(sharedModule, platformModule)
}
