package com.boomsset.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.boomsset.db.BoomssetDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 用户偏好。
 *
 * 基准币种**作为查询参数传入净值计算，不落到 Asset 或 Snapshot 上** ——
 * 切换币种只改变展示口径，不改变任何已记录的事实。见 docs/domain.md。
 */
interface SettingsRepository {
    fun observeBaseCurrency(): Flow<String>
    suspend fun setBaseCurrency(code: String)

    /** 应用锁是否开启。默认关闭 —— 不替用户做安全决策。 */
    fun observeAppLockEnabled(): Flow<Boolean>
    suspend fun setAppLockEnabled(enabled: Boolean)
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

    companion object {
        const val KEY_BASE_CURRENCY = "base_currency"
        const val KEY_APP_LOCK = "app_lock_enabled"
        const val DEFAULT_BASE_CURRENCY = "CNY"
    }
}

/**
 * Frankfurter（ECB）支持的、我们在 UI 里提供的币种。
 *
 * ⚠️ **TWD 不在 ECB 的列表里**，所以台币资产取不到汇率、会显示"无法估值"。
 * 这是数据源限制，不是 bug —— 但别把 TWD 放进这个列表让用户以为能用。
 */
val SUPPORTED_CURRENCIES: List<String> =
    listOf("CNY", "USD", "HKD", "EUR", "JPY", "GBP", "SGD", "AUD", "KRW")
