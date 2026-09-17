package com.boomsset.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.boomsset.ui.LocalGainLossColors
import com.boomsset.ui.gainLossColorsFor

/**
 * Boomsset's brand color — **mid-rose** (derived from the "flying pig" mascot icon's body
 * pink, H 354).
 *
 * ## How this version came about: deriving the theme color from the mascot's color
 *
 * After the app icon changed from the "breaking out of the ring" abstract design to a
 * flying pink pig, the natural question came up: "can the pig's body pink just be the
 * theme color directly?" — **no, but the same hue can.** The pig body's original color
 * `#EFA8C4` (L 0.807, a light pink meant for the mascot) is only **1.81:1** against the
 * page background — elements that need to stand out from the page, like the FAB and nav
 * indicator, would blend into the background. The same hue H 354 was pressed down into
 * three candidate steps (bright rose L 0.68 / mid-rose L 0.59 / deep berry-plum L 0.43),
 * and each was tried in a real UI preview.
 *
 * ## ⚠️ Bright rose shipped first; real-device feedback said "disjointed from the other
 * colors," which is what led to switching to mid-rose
 *
 * Bright rose `#E961A0`'s problem **isn't the hue, it's the intensity**: its chroma
 * **0.180 is higher than all five asset-class colors** (asset-class colors run 0.110~0.152),
 * and its lightness 0.68 **lands right in the middle of the asset-class colors' range**
 * (0.60~0.72) — visually, it effectively "enrolled itself into the asset-class color
 * group," and was the loudest member of it. On a real device this showed up as: the net
 * worth page's six high-chroma colors competing for attention, the allocation page's brand
 * color becoming invisible by comparison, and the assets page (which has almost no
 * asset-class fill) being the only harmonious one. Mid-rose brings the lightness down to
 * **0.590, below all five asset-class colors**, dropping it back to the "framework color"
 * layer: data gets data colors, brand gets brand color, the hierarchy is separated again.
 * **This is the same mechanism that made the old deep-rosewood (L 0.40) never clash.**
 *
 * This incidentally fixed two other awkward spots: **white text finally passes (4.54:1)**,
 * so `onPrimary` goes back to white, and the switch thumb no longer needs to be manually
 * specified (M3's default already uses `onPrimary`); the nav bar's selected state can also
 * use `primary` directly (4.07:1 against the nav bar background), no longer needing a
 * separately-solved "bright enough rose" constant (the bright-rose version was only 2.82:1,
 * forcing a separate constant to be created).
 *
 * ## "Protection" doesn't need to move again
 *
 * The previous version (deep-rosewood, H 315) moved "protection" from purple to gold,
 * because purple was occupying that hue slot. Rose H 354 is farther from all five
 * asset-class colors (minimum ΔE 21.4), **so no further move is needed** — ChartColors.kt
 * keeps gold unchanged; this note just records "no knock-on effect this time," not a new
 * decision.
 *
 * ## Three criteria that must not be violated (changing color values requires re-verifying
 * with the validator described in ChartColorsTest's comments)
 *
 * 1. **Minimum ΔE ≥ 15 against the five asset-class chart colors** (OKLab ×100, normal
 *    vision). Measured **21.4** (light) / **20.7** (dark).
 * 2. **primary against the page background / dark background ≥ 3:1**. Measured
 *    **4.34:1** (light) / **6.26:1** (dark). The bright-rose version was only 3.00:1
 *    (right at the line); switching to mid-rose brought the margin back to a comfortable
 *    level.
 * 3. **onPrimary against primary ≥ 4.5:1**. Measured white text at **4.54:1** (light,
 *    right at the line but passing). ⚠️ Dark mode's primary is brighter and more vivid,
 *    where white text is only 2.98:1 and **fails**, so `onPrimary` on the dark side
 *    remains near-black wine-red. **The light and dark sets' onPrimary are deliberately
 *    not the same color.**
 *
 * ## Neutral surfaces still **haven't** followed the hue change
 *
 * Surfaces and text remain warm-toned (H 70) — the constraint "don't change the background
 * color" still holds through this round of theme-color changes. The independence of the
 * warm/cool hues hasn't changed; only the brand-color track moved from H 315 to H 354.
 *
 * ⚠️ **`tools/appicon/generate.py`'s `BRAND_HUE` hasn't been updated to match yet** — the
 * "breaking out of the ring" abstract icon set is in the process of being replaced by the
 * "flying pig," and the icon redesign is a separate task; generate.py is left untouched
 * until that's finalized, to avoid the icon and theme-color workstreams interrupting each
 * other. Remember to come back and sync this constant once the icon redesign lands.
 *
 * **Every role must be written out explicitly — coverage can't stop at primary.**
 * `lightColorScheme()` falls back to baseline defaults for any parameter not passed, and
 * the baseline's surface family is a purple-tinted gray — changing only primary would make
 * the whole thing look half-migrated.
 *
 * The brand color and the **rise/fall semantic colors are separate things** — the latter
 * is in [com.boomsset.ui.GainLossColors].
 */
private val BrandRose = Color(0xFFC94385)

private val LightScheme = lightColorScheme(
    primary = BrandRose,
    // White — 4.54:1, right at the line but passing. The bright-rose version was only
    // 3.14:1, which forced dark wine-red text at the time; once pressed down to mid-rose,
    // white text became viable again, and M3's default components (switch thumb, etc.)
    // fall into place automatically.
    onPrimary = Color(0xFFFFFFFF),
    // Net worth hero card background — pale rose. Only 1.34:1 against the page background,
    // **must have its outline drawn with a stroke**, see NetWorthScreen's SummaryCard
    // (the stroke logic is generic, no need to change it when swapping the brand color).
    primaryContainer = Color(0xFFFBCEDF),
    onPrimaryContainer = Color(0xFF45142B),
    inversePrimary = Color(0xFFDF99B5),

    // Secondary color uses a low-chroma neutral of the same hue — only one accent color is
    // kept, everything else stays near-neutral
    secondary = Color(0xFF70675E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1ECE6),
    onSecondaryContainer = Color(0xFF342C23),

    // Tertiary is deliberately kept within the same hue, **not using any asset-class
    // color's hue** — otherwise it would collide with some asset class's color and make
    // readers think the two are related
    tertiary = Color(0xFF7A4A5E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF7D3E0),
    onTertiaryContainer = Color(0xFF41162A),

    // error and "over-allocated" are two different things, with different color values too
    // (over-allocated is #C5453F)
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    background = Color(0xFFFEF9F4),
    onBackground = Color(0xFF30271D),
    surface = Color(0xFFFEF9F4),
    onSurface = Color(0xFF30271D),
    surfaceVariant = Color(0xFFEBE6E2),
    onSurfaceVariant = Color(0xFF6C6359),
    surfaceTint = BrandRose,
    inverseSurface = Color(0xFF30271D),
    // ↑ surfaceTint follows primary — don't hardcode a color value here
    inverseOnSurface = Color(0xFFF6F2ED),

    surfaceDim = Color(0xFFE5E1DC),
    surfaceBright = Color(0xFFFFFCF8),
    surfaceContainerLowest = Color(0xFFFFFEFD),
    surfaceContainerLow = Color(0xFFFAF6F1),
    surfaceContainer = Color(0xFFF6F2ED),
    surfaceContainerHigh = Color(0xFFF0ECE7),
    surfaceContainerHighest = Color(0xFFEBE6E2),

    outline = Color(0xFF9A9187),
    outlineVariant = Color(0xFFE0D8CE),
    scrim = Color(0xFF000000),
)

/**
 * Dark mode is **a separately chosen set of steps**, not an automatic flip of light mode —
 * the same hue angle (H 354), with lightness re-derived against the dark background and
 * independently verified (primary against background 6.26:1, minimum ΔE 22.0 against the
 * dark asset-class colors).
 * ⚠️ "Protection" doesn't need to move this round (see the "doesn't need to move again"
 * section in LightScheme's doc above); the dark mode gold `#9B8100` is kept unchanged.
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF53A8),
    // Also dark wine-red, not white — dark mode's vivid rose is only 2.98:1 against white
    // text, which also fails.
    onPrimary = Color(0xFF1C030F),
    primaryContainer = Color(0xFF673048),
    onPrimaryContainer = Color(0xFFFDD0E1),
    inversePrimary = BrandRose,

    secondary = Color(0xFFC5BCB3),
    onSecondary = Color(0xFF312A22),
    secondaryContainer = Color(0xFF413C36),
    onSecondaryContainer = Color(0xFFE6DED6),

    tertiary = Color(0xFFDBA4B9),
    onTertiary = Color(0xFF3A1024),
    tertiaryContainer = Color(0xFF612E44),
    onTertiaryContainer = Color(0xFFF7CDDD),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    background = Color(0xFF16120D),
    onBackground = Color(0xFFECE5DC),
    surface = Color(0xFF16120D),
    onSurface = Color(0xFFECE5DC),
    surfaceVariant = Color(0xFF3D3833),
    onSurfaceVariant = Color(0xFFC5BCB3),
    surfaceTint = Color(0xFFFF53A8),
    inverseSurface = Color(0xFFECE5DC),
    inverseOnSurface = Color(0xFF30271D),

    surfaceDim = Color(0xFF16120D),
    surfaceBright = Color(0xFF433D38),
    surfaceContainerLowest = Color(0xFF100B07),
    surfaceContainerLow = Color(0xFF1D1914),
    surfaceContainer = Color(0xFF211C17),
    surfaceContainerHigh = Color(0xFF2E2924),
    surfaceContainerHighest = Color(0xFF39342F),

    outline = Color(0xFF8A8279),
    outlineVariant = Color(0xFF3F3830),
    scrim = Color(0xFF000000),
)

/**
 * Shared theme entry point for both platforms. Follows the system's light/dark mode —
 * it used to be always-light, which was blindingly white in dark mode.
 */
@Composable
fun BoomssetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Status bar icons need to invert along with the theme — see the comment on
    // ApplySystemBarsAppearance; without this, light theme leaves the status bar with white
    // text on a white background
    ApplySystemBarsAppearance(darkTheme)

    // Chart colors and gain/loss colors both use the **same** darkTheme — letting them read
    // the system's light/dark setting independently would go out of sync with the theme
    // (especially likely in previews and tests)
    CompositionLocalProvider(
        LocalChartColors provides chartColorsFor(darkTheme),
        LocalGainLossColors provides gainLossColorsFor(darkTheme),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            content = content,
        )
    }
}
