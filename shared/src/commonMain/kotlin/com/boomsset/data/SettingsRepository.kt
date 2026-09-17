package com.boomsset.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.boomsset.db.BoomssetDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * User preferences.
 *
 * The base currency is **passed into net worth calculations as a query parameter and never
 * lands on Asset or Snapshot** — switching currencies only changes the display convention,
 * never any recorded fact. See docs/domain.md.
 */
interface SettingsRepository {
    fun observeBaseCurrency(): Flow<String>
    suspend fun setBaseCurrency(code: String)

    /** Whether the app lock is enabled. Off by default — we don't make security decisions for the user. */
    fun observeAppLockEnabled(): Flow<Boolean>
    suspend fun setAppLockEnabled(enabled: Boolean)

    /**
     * Whether amounts on the net worth screen are hidden (the eye icon's state). Shown by default.
     *
     * **Must be persisted.** This toggle exists for "someone else is nearby" — if it reset
     * to visible on every restart, the user would have to race to tap it again every time
     * they opened the app before someone else could see — which is the same as not having
     * the feature at all. It only affects display and never rewrites a single snapshot, so
     * like the base currency, it lives in the settings table.
     */
    fun observeAmountsHidden(): Flow<Boolean>
    suspend fun setAmountsHidden(hidden: Boolean)
}

class SqlDelightSettingsRepository(
    private val db: BoomssetDatabase,
    private val dispatcher: CoroutineDispatcher,
) : SettingsRepository {

    override fun observeBaseCurrency(): Flow<String> =
        db.settingsQueries.selectAll().asFlow().mapToList(dispatcher).map { rows ->
            rows.firstOrNull { it.key == KEY_BASE_CURRENCY }?.value_ ?: DEFAULT_BASE_CURRENCY
        }

    override suspend fun setBaseCurrency(code: String): Unit = withContext(dispatcher) {
        db.settingsQueries.upsert(KEY_BASE_CURRENCY, code)
    }

    override fun observeAppLockEnabled(): Flow<Boolean> =
        db.settingsQueries.selectAll().asFlow().mapToList(dispatcher).map { rows ->
            rows.firstOrNull { it.key == KEY_APP_LOCK }?.value_ == "true"
        }

    override suspend fun setAppLockEnabled(enabled: Boolean): Unit = withContext(dispatcher) {
        db.settingsQueries.upsert(KEY_APP_LOCK, enabled.toString())
    }

    override fun observeAmountsHidden(): Flow<Boolean> =
        db.settingsQueries.selectAll().asFlow().mapToList(dispatcher).map { rows ->
            rows.firstOrNull { it.key == KEY_AMOUNTS_HIDDEN }?.value_ == "true"
        }

    override suspend fun setAmountsHidden(hidden: Boolean): Unit = withContext(dispatcher) {
        db.settingsQueries.upsert(KEY_AMOUNTS_HIDDEN, hidden.toString())
    }

    companion object {
        const val KEY_BASE_CURRENCY = "base_currency"
        const val KEY_APP_LOCK = "app_lock_enabled"
        const val KEY_AMOUNTS_HIDDEN = "amounts_hidden"
        const val DEFAULT_BASE_CURRENCY = "CNY"
    }
}

/**
 * Currencies supported by Frankfurter (ECB) that we expose in the UI.
 *
 * ⚠️ **TWD is not in ECB's list**, so TWD-denominated assets can't get a rate and will
 * show "unable to value". This is a data source limitation, not a bug — but don't add TWD
 * to this list and mislead users into thinking it's supported.
 */
val SUPPORTED_CURRENCIES: List<String> =
    listOf("CNY", "USD", "HKD", "EUR", "JPY", "GBP", "SGD", "AUD", "KRW")
