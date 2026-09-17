package com.boomsset.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Rise/fall colors. **Not** `MaterialTheme.colorScheme.primary`/`error`, nor `over`/`under`
 * from [com.boomsset.ui.theme.ChartColors] — those two groups are, respectively, the brand
 * accent color and the divergent colors for "over-/under-allocated" on the allocation
 * page; conceptually unrelated to "did this go up or down over this period," and mixing
 * them up would blur the meaning whenever gain/loss figures appear alongside those on the
 * net worth and asset pages.
 *
 * In the Chinese stock market context, **red means up, green means down** — the opposite
 * of most Western apps' red-down/green-up — following the market context the user is
 * actually in, not a globally universal color scheme.
 *
 * ## Why this must be split into light/dark groups (the old version had one fixed set)
 *
 * These two colors are **body text**, and the criterion is ≥ 4.5:1 against **every
 * background they actually sit on**, and they appear on three backgrounds: the page
 * background, a regular card (`surfaceContainer`), and the net worth page's hero card
 * (`primaryContainer`). The old value `#C5453F` was only **3.34:1** against the warm-sand
 * hero card, so one set is chosen per mode, supplied by
 * [com.boomsset.ui.theme.BoomssetTheme] through [LocalGainLossColors] — the same approach
 * as `LocalChartColors`. **Don't call `isSystemInDarkTheme()` here on its own**: the
 * theme's light/dark mode can be overridden by an explicit parameter, and reading the
 * system setting independently would cause inconsistency.
 *
 * ⚠️ **The least-favorable background isn't the same one in light vs. dark mode — this
 * was a real pitfall.** In light mode the hero card (now pale rose `#FBCEDF`, previously
 * pale purple `#EAD2F6`) is **darker** than the page background, so it's the least
 * favorable; in dark mode the hero card (now deep wine-red `#673048`, previously deep
 * blue-purple `#563664`) is instead **lighter** than the page background, and it's also
 * the least favorable one there — but an earlier dark-mode value was only checked against
 * `surfaceContainer`, and turned out to be **only 2.50:1** against the dark hero card,
 * something only caught after switching to dark mode on a real device (Xiaomi 15 Pro /
 * Android 16). **When changing these color values, check all three backgrounds
 * individually — don't assume which one is "least favorable."**
 *
 * Measured (re-verified after the brand color switched to bright rose; numbers shifted
 * slightly but all still clear the bar): light mode rise 4.51 / fall 4.61 (against the
 * pale-rose hero card, the other two backgrounds are more forgiving); dark mode rise 4.60 /
 * fall 4.54 (against the deep wine-red hero card), more forgiving against the regular card
 * and page background.
 * ⚠️ These numbers are **all close to the 4.5:1 threshold** — if a brand-color change
 * pushes the container's lightness down even slightly, this rise/fall color set will need
 * re-solving; it isn't permanently safe.
 *
 * Only used for gain/loss and rise/fall figures on the net worth and asset pages; not used
 * on the allocation page (its red/blue mean "over-/under-allocated," a different semantic
 * — see [com.boomsset.ui.theme.ChartColors]).
 */
data class GainLossColors(
    /** Rise — red in the Chinese context. */
    val rise: Color,
    /** Fall — green in the Chinese context. */
    val fall: Color,
)

private val LightGainLoss = GainLossColors(
    rise = Color(0xFFAA3836),
    fall = Color(0xFF0A6D37),
)

private val DarkGainLoss = GainLossColors(
    rise = Color(0xFFF7958D),
    fall = Color(0xFF76BE8A),
)

val LocalGainLossColors = staticCompositionLocalOf { LightGainLoss }

internal fun gainLossColorsFor(darkTheme: Boolean): GainLossColors =
    if (darkTheme) DarkGainLoss else LightGainLoss

@Composable
@ReadOnlyComposable
fun riseColor(): Color = LocalGainLossColors.current.rise

@Composable
@ReadOnlyComposable
fun fallColor(): Color = LocalGainLossColors.current.fall
