package com.boomsset.ui.networth

import com.boomsset.domain.Money
import com.boomsset.domain.NetWorthPoint
import com.boomsset.domain.NetWorthSeries
import com.boomsset.domain.Period
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.time.Instant

class NetWorthUiStateTest {

    private fun seriesWithZeroPoints(): NetWorthSeries {
        val t = Instant.fromEpochMilliseconds(0)
        return NetWorthSeries(
            period = Period.MONTH,
            baseCurrency = "CNY",
            dates = listOf(LocalDate(2026, 6, 30), LocalDate(2026, 7, 28)),
            points = listOf(
                NetWorthPoint(t, "CNY", Money.ZERO, Money.ZERO),
                NetWorthPoint(t, "CNY", Money.ZERO, Money.ZERO),
            ),
        )
    }

    /**
     * Regression test. This bug was found during a real run on the emulator:
     * `isEmpty` used to be judged by `series.latest == null`, but `buildSeries` generates
     * a whole run of net-worth-0 points even at zero assets (the sample dates are computed
     * from the period, independent of whether there's any data), so `latest` was never
     * null — the empty state never appeared, and new users saw ¥0.00 and a flat zero
     * line instead of onboarding copy.
     */
    @Test
    fun `zero assets is an empty state, even when the series has a bunch of zero-value points`() {
        val state = NetWorthUiState(
            loading = false,
            series = seriesWithZeroPoints(),
            hasAssets = false,
        )
        state.isEmpty shouldBe true
    }

    @Test
    fun `not an empty state once there are assets, even if current net worth happens to be zero`() {
        // Assets exist but net worth is 0 (e.g. offset entirely by fully paid-off
        // liabilities) — the normal screen should show in this case, not the "no assets
        // yet" onboarding
        val state = NetWorthUiState(
            loading = false,
            series = seriesWithZeroPoints(),
            hasAssets = true,
        )
        state.isEmpty shouldBe false
    }

    @Test
    fun `loading does not count as an empty state`() {
        NetWorthUiState(loading = true, hasAssets = false).isEmpty shouldBe false
    }
}
