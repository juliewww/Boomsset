package com.boomsset.security

import com.boomsset.data.SettingsRepository
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class AppLockStateTest {

    @Test
    fun `加载中也要挡住内容`() {
        // 这是应用锁最常见的实现缺陷：先渲染内容、读到设置后才遮住 ——
        // 开了锁的用户会在那一瞬间看到自己的资产数据。
        AppLockUiState(loading = true, lockEnabled = false).shouldBlockContent shouldBe true
        AppLockUiState(loading = true, lockEnabled = true).shouldBlockContent shouldBe true
    }

    @Test
    fun `没开锁就不挡`() {
        AppLockUiState(loading = false, lockEnabled = false).shouldBlockContent shouldBe false
    }

    @Test
    fun `开了锁未解锁则挡住`() {
        AppLockUiState(loading = false, lockEnabled = true, unlocked = false)
            .shouldBlockContent shouldBe true
    }

    @Test
    fun `开了锁已解锁则放行`() {
        AppLockUiState(loading = false, lockEnabled = true, unlocked = true)
            .shouldBlockContent shouldBe false
    }
}

/**
 * ViewModel 的测试必须替换 `Dispatchers.Main` —— `viewModelScope` 用的就是它，
 * 单测环境里没有 Main dispatcher，`launch` 块会静默不执行，
 * 测试就变成「什么都没发生所以断言全挂」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeSettings : SettingsRepository {
        val lockEnabled = MutableStateFlow(false)
        override fun observeBaseCurrency(): Flow<String> = MutableStateFlow("CNY")
        override suspend fun setBaseCurrency(code: String) {}
        override fun observeAppLockEnabled(): Flow<Boolean> = lockEnabled
        override suspend fun setAppLockEnabled(enabled: Boolean) { lockEnabled.value = enabled }
        val amountsHidden = MutableStateFlow(false)
        override fun observeAmountsHidden(): Flow<Boolean> = amountsHidden
        override suspend fun setAmountsHidden(hidden: Boolean) { amountsHidden.value = hidden }
    }

    private class FakeAuthenticator(
        private val capability: AuthCapability = AuthCapability.AVAILABLE,
        private val result: AuthResult = AuthResult.Success,
    ) : AppLockAuthenticator {
        var calls = 0
        override fun capability() = capability
        override suspend fun authenticate(reason: String): AuthResult {
            calls++
            return result
        }
    }

    @Test
    fun `认证成功后解锁`() = runTest {
        val settings = FakeSettings().apply { lockEnabled.value = true }
        val vm = AppLockViewModel(settings, FakeAuthenticator())

        vm.state.value.shouldBlockContent shouldBe true
        vm.authenticate()
        vm.state.value.unlocked shouldBe true
        vm.state.value.shouldBlockContent shouldBe false
    }

    @Test
    fun `用户取消不算错误 不显示失败提示`() = runTest {
        // 弹「认证失败」是在指责用户 —— 他只是按了取消
        val settings = FakeSettings().apply { lockEnabled.value = true }
        val vm = AppLockViewModel(settings, FakeAuthenticator(result = AuthResult.Cancelled))

        vm.authenticate()
        vm.state.value.unlocked shouldBe false
        vm.state.value.lastError.shouldBeNull()
    }

    @Test
    fun `认证失败保留原因供提示`() = runTest {
        val settings = FakeSettings().apply { lockEnabled.value = true }
        val vm = AppLockViewModel(settings, FakeAuthenticator(result = AuthResult.Failed("指纹不匹配")))

        vm.authenticate()
        vm.state.value.unlocked shouldBe false
        vm.state.value.lastError shouldBe "指纹不匹配"
    }

    @Test
    fun `开启应用锁前必须先认证成功`() = runTest {
        // 否则拿到手机的人能直接开锁把主人锁在外面；
        // 或者用户在认证不了的设备上开了锁，自己再也进不来
        val settings = FakeSettings()
        val auth = FakeAuthenticator(result = AuthResult.Failed("不匹配"))
        val vm = AppLockViewModel(settings, auth)

        vm.setLockEnabled(true)

        auth.calls shouldBe 1
        settings.lockEnabled.value shouldBe false   // 没开成
    }

    @Test
    fun `认证成功才真的开启`() = runTest {
        val settings = FakeSettings()
        val vm = AppLockViewModel(settings, FakeAuthenticator())

        vm.setLockEnabled(true)

        settings.lockEnabled.value shouldBe true
        // 开启的同时视为已解锁 —— 否则刚开完就被自己挡在外面
        vm.state.value.unlocked shouldBe true
    }

    @Test
    fun `关闭不需要再认证一次`() = runTest {
        // 能点到这个开关说明已经通过认证进来了，再验一次是多余的摩擦
        val settings = FakeSettings().apply { lockEnabled.value = true }
        val auth = FakeAuthenticator()
        val vm = AppLockViewModel(settings, auth)

        vm.setLockEnabled(false)

        settings.lockEnabled.value shouldBe false
        auth.calls shouldBe 0
    }

    @Test
    fun `重新上锁后需要再次认证`() = runTest {
        // 解锁状态**不持久化** —— 回后台或重启都要重新验
        val settings = FakeSettings().apply { lockEnabled.value = true }
        val vm = AppLockViewModel(settings, FakeAuthenticator())

        vm.authenticate()
        vm.state.value.shouldBlockContent shouldBe false

        vm.relock()
        vm.state.value.shouldBlockContent shouldBe true
    }

    @Test
    fun `设备不支持时能力如实反映`() = runTest {
        val vm = AppLockViewModel(
            FakeSettings(),
            FakeAuthenticator(capability = AuthCapability.NO_HARDWARE),
        )
        // 「不支持」和「没录入」要分开 —— 后者用户去系统设置能解决
        vm.state.value.capability shouldBe AuthCapability.NO_HARDWARE
    }
}
