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
    fun `content is blocked even while loading`() {
        // This is the most common implementation flaw for an app lock: rendering the content
        // first and only covering it once the setting is read -- a user with the lock enabled
        // would briefly see their own asset data in that window.
        AppLockUiState(loading = true, lockEnabled = false).shouldBlockContent shouldBe true
        AppLockUiState(loading = true, lockEnabled = true).shouldBlockContent shouldBe true
    }

    @Test
    fun `nothing is blocked when the lock is off`() {
        AppLockUiState(loading = false, lockEnabled = false).shouldBlockContent shouldBe false
    }

    @Test
    fun `content is blocked when the lock is on but not yet unlocked`() {
        AppLockUiState(loading = false, lockEnabled = true, unlocked = false)
            .shouldBlockContent shouldBe true
    }

    @Test
    fun `content is allowed through when the lock is on and unlocked`() {
        AppLockUiState(loading = false, lockEnabled = true, unlocked = true)
            .shouldBlockContent shouldBe false
    }
}

/**
 * ViewModel tests must replace `Dispatchers.Main` -- `viewModelScope` relies on exactly that,
 * and the unit test environment has no Main dispatcher, so `launch` blocks would silently
 * never execute, turning the test into "nothing happened, so every assertion fails."
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
    fun `unlocks after successful authentication`() = runTest {
        val settings = FakeSettings().apply { lockEnabled.value = true }
        val vm = AppLockViewModel(settings, FakeAuthenticator())

        vm.state.value.shouldBlockContent shouldBe true
        vm.authenticate()
        vm.state.value.unlocked shouldBe true
        vm.state.value.shouldBlockContent shouldBe false
    }

    @Test
    fun `user cancellation is not an error - no failure message is shown`() = runTest {
        // Popping up "authentication failed" would be blaming the user -- they simply tapped cancel
        val settings = FakeSettings().apply { lockEnabled.value = true }
        val vm = AppLockViewModel(settings, FakeAuthenticator(result = AuthResult.Cancelled))

        vm.authenticate()
        vm.state.value.unlocked shouldBe false
        vm.state.value.lastError.shouldBeNull()
    }

    @Test
    fun `a failed authentication keeps the reason around for the UI`() = runTest {
        val settings = FakeSettings().apply { lockEnabled.value = true }
        val vm = AppLockViewModel(settings, FakeAuthenticator(result = AuthResult.Failed("指纹不匹配")))

        vm.authenticate()
        vm.state.value.unlocked shouldBe false
        vm.state.value.lastError shouldBe "指纹不匹配"
    }

    @Test
    fun `authentication must succeed before the app lock can be turned on`() = runTest {
        // Otherwise whoever is holding the phone could turn the lock on and lock the owner
        // out; or the user turns it on from a device that can't authenticate and can never get back in
        val settings = FakeSettings()
        val auth = FakeAuthenticator(result = AuthResult.Failed("不匹配"))
        val vm = AppLockViewModel(settings, auth)

        vm.setLockEnabled(true)

        auth.calls shouldBe 1
        settings.lockEnabled.value shouldBe false   // failed to turn on
    }

    @Test
    fun `it only actually turns on once authentication succeeds`() = runTest {
        val settings = FakeSettings()
        val vm = AppLockViewModel(settings, FakeAuthenticator())

        vm.setLockEnabled(true)

        settings.lockEnabled.value shouldBe true
        // Turning it on is treated as already unlocked -- otherwise the user would be locked out right after enabling it
        vm.state.value.unlocked shouldBe true
    }

    @Test
    fun `turning it off does not require authenticating again`() = runTest {
        // Being able to reach this toggle means authentication already succeeded to get in; verifying again would be needless friction
        val settings = FakeSettings().apply { lockEnabled.value = true }
        val auth = FakeAuthenticator()
        val vm = AppLockViewModel(settings, auth)

        vm.setLockEnabled(false)

        settings.lockEnabled.value shouldBe false
        auth.calls shouldBe 0
    }

    @Test
    fun `re-locking requires authenticating again`() = runTest {
        // The unlocked state is **not persisted** -- going to the background or restarting always requires re-authenticating
        val settings = FakeSettings().apply { lockEnabled.value = true }
        val vm = AppLockViewModel(settings, FakeAuthenticator())

        vm.authenticate()
        vm.state.value.shouldBlockContent shouldBe false

        vm.relock()
        vm.state.value.shouldBlockContent shouldBe true
    }

    @Test
    fun `capability accurately reflects an unsupported device`() = runTest {
        val vm = AppLockViewModel(
            FakeSettings(),
            FakeAuthenticator(capability = AuthCapability.NO_HARDWARE),
        )
        // "unsupported" and "not enrolled" must be kept distinct -- the latter is something the user can fix in system settings
        vm.state.value.capability shouldBe AuthCapability.NO_HARDWARE
    }
}
