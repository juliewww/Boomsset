package com.boomsset.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.boomsset.domain.AssetClass

/**
 * Chart color palette. **Not hand-picked.**
 *
 * Asset-class colors are "categorical colors" (encode identity, not magnitude); deviation
 * colors are "divergent colors" (encode positive vs. negative sides) — the two roles
 * follow different rules, and mixing them up would make readers conflate "which class"
 * with "over- or under-allocated."
 *
 * ## Asset classes: five fixed-order hues
 *
 * ⚠️ **"Protection" used to be purple `#585CA2`, now it's gold `#977E00`.**
 * The reason isn't a problem with the color scheme itself — it's that **the brand color
 * needs to be purple** (auspicious in Chinese color symbolism), and purple must have
 * ΔE ≥ 15 against these five colors — with protection occupying purple, brand purple had
 * nowhere to stand. The direction to move it was computed, not guessed: magenta (H340) was
 * tried first, and **that was wrong** — magenta instead crowds the H300~330 purple range,
 * forcing brand purple to need ≥0.19 chroma (too vivid). Protection had to move to the
 * **side farthest from purple** (warm/green), which is what let brand purple's chroma
 * floor drop from 0.135 to 0.060 — i.e. only then could a low-chroma, refined purple be
 * produced.
 *
 * ⚠️ This change **rebuilds user expectations** — on real devices, "purple = protection"
 * had already been running for a while.
 *
 * The order itself is a **colorblind-safety mechanism**, not an aesthetic choice:
 * candidate orderings are verified one by one, and only passing ones are chosen from. So
 * the order of [assetClassColors] should **not be reshuffled**, and no color should be
 * "casually generated" for a hypothetical sixth asset class.
 *
 * ## Hues are taken from Youzhiyouxing, but **must be snapped**
 *
 * The first four hues are copied from Youzhiyouxing's design tokens (blue `#4287CE` /
 * green `#2EB88A` / orange `#E5881E` / cyan `#66B5CC`), but **can't be used directly**:
 * they're meant for small-area accents and text, and when used to fill five categorical
 * slots, gold and pink's lightness falls outside the acceptable range, and cyan and
 * purple's chroma falls below the floor (reading as gray).
 *
 * So they're handled by snap-to-passing: **hue angle stays fixed**, only lightness and
 * chroma are moved into compliance. Out of 2520 combinations searched, 588 passing groups
 * were found, and the one **closest to the original colors** was chosen — total deviation
 * across the five colors is only ΔE 5.8, with green not changed by a single pixel. Gold and
 * pink were automatically excluded (their snap cost was the highest, ΔE 8.7 / 5.7
 * respectively).
 *
 * Measured (OKLab ΔE ×100, protanopia/deuteranopia simulation, thresholds 8 / 15):
 * - Light background `#FFFFFF` (the least favorable case; the actual card is `#FAFAFA`):
 *   worst adjacent pair 11.5, normal vision 20.4
 * - Dark background `#1C1C1C`: worst adjacent pair 10.8, normal vision 16.6, **all color
 *   block contrasts ≥ 3:1**
 *
 * ⚠️ **In light mode, green and orange fall below 3:1 color-block contrast.**
 * This isn't waivable and must have a compensating channel — every row of ours shows both
 * the **asset class name and percentage**, and the bar's **length** is itself readable
 * without depending on color. So the compensation is structural:
 * **don't remove those labels when reworking the layout.**
 *
 * ## Deviation: red ↔ blue + neutral gray
 *
 * Red for over-allocation is a product requirement. Blue rather than green for
 * under-allocation: **green reads as "down" in the Chinese financial-management context**,
 * and using it for "under-allocated" would be read as a loss. The neutral state
 * (on-target) uses neutral gray — the midpoint of a divergent color scheme **must not be
 * a hue**, otherwise "no deviation" would also look like a state of its own.
 *
 * Under-allocation's blue is **one step darker** than asset-class slot 1's blue, so it
 * stays distinct from the "liquid assets" color block. These three are **text colors**,
 * verified against the WCAG body-text standard (≥ 4.5:1, measured light mode 4.89 / 5.34 /
 * 6.69). Over-allocation's red is taken from Youzhiyouxing's `#E5605C`, but that's only
 * 3.41:1 on a white background — not compliant for body text — so light mode darkens it to
 * `#C5453F`; dark mode's background is dark enough that the original value works.
 *
 * Deviation colors **don't reuse `error`**: over-allocation isn't an error, it's a
 * difference from the plan. Painting it as an error color would dilute the weight of
 * actual errors (validation failures).
 */
data class ChartColors(
    /** The five asset classes' categorical colors, **fixed order**. Index corresponds to
     * [AssetClass.displayOrder]. */
    val assetClassColors: List<Color>,
    /** The bar's track color (the unfilled portion). Neutral, so the fill's length reads
     * clearly. */
    val track: Color,
    /** Over-allocated. */
    val over: Color,
    /** Under-allocated. */
    val under: Color,
    /** On-target — the neutral midpoint of the divergent color scheme. */
    val onTarget: Color,
    /**
     * Area fill for the net worth page's **trend chart (total assets)**.
     *
     * ⚠️ **Don't use `primaryContainer`.** That's the hero card's background color, the
     * lightest step in the whole set — against the page background it's only **1.33:1** in
     * light mode, 1.87:1 in dark, filled in name only. Real-device feedback said "the trend
     * chart isn't solid," while the code had actually already dropped alpha — **"solid"
     * isn't just about opacity, the color value itself has to be visible enough.**
     *
     * ⚠️ **This value tracks primary's lightness, so it must be re-solved whenever the
     * brand color changes** — it's already been swapped twice: with deep-rosewood
     * (L 0.40), it was light purple `#C398D6`; after switching to bright rose (L 0.68) the
     * line and fill blurred together (down to 1.31:1), so it was changed to a very deep
     * `#8C0553`; now that primary is mid-rose (L 0.59), that deep fill no longer works
     * either, in the other direction (2.04:1).
     *
     * **The principle in one sentence: the fill and the line must be separated in
     * lightness, and the line's lightness is fixed by primary.** Every time primary's
     * lightness changes, the fill has to move to the opposite end. Mid-rose sits in the
     * middle, so this time the fill goes to the **light** end: `#F1B8CD` (L 0.84, same
     * brand hue H 355).
     *
     * Currently light mode: fill against page **1.61:1**, line against fill **2.70:1**.
     * ⚠️ This fill-against-page number is **lower than every previous version**, which
     * looks like it violates the earlier rule "don't use primaryContainer (1.33:1), filled
     * in name only" — **the difference is that this time the line is dark and clear**: the
     * shape's boundary is carried by that 2.70:1 line, and the fill only needs to softly
     * hint "this area is below the line," no longer needing to carry visibility on its own.
     * Dark mode still uses the old approach (dark fill + bright line) — opposite strategies
     * on each side, but both hold up.
     */
    val trendArea: Color,
) {
    /**
     * Gets the color for a given asset class.
     *
     * Looked up by index in [AssetClass.displayOrder], **not** the enum's ordinal —
     * display order is a product decision, and reordering the enum's declaration shouldn't
     * silently swap all the colors.
     */
    fun of(assetClass: AssetClass): Color {
        val index = AssetClass.displayOrder.indexOf(assetClass)
        // An out-of-range index can only mean a new asset class was added without a color.
        // Fall back to the track color rather than crashing, and don't "generate" a color —
        // a generated color isn't protected by colorblind-safety verification.
        return assetClassColors.getOrElse(index) { track }
    }
}

/**
 * ⚠️ **Brightened version: shifted up by 0.05 overall, the gradient narrowed slightly,
 * chroma untouched** (feedback: "the colors shouldn't be this dark"). The deepest blue and
 * gold went from L 0.599 up to 0.649, orange and cyan from 0.715 to 0.743.
 *
 * ## Two pitfalls hit here, both caught by the official validator
 *
 * 1. **Don't touch chroma.** A version was tried midway that "capped chroma uniformly at
 *    0.125 + blended 4% toward the brand color," trying to make the five colors read as
 *    softer, closer to pink. Result: the visual change was almost imperceptible, **but the
 *    cost was that adjacent colors' colorblind separation dropped from 11.5 to 8.6** (the
 *    dark set was worse — "alternative/physical assets" got pushed to the chroma floor and
 *    directly FAILed as "reads as gray"). **Trading real separation for near-zero-visible
 *    "softening" is a losing trade** — that version was rolled back. Now each color's
 *    original chroma is kept, only lightness is shifted, and the separation **stays intact
 *    at 11.5**.
 * 2. **Don't flatten lightness into a single line.** Also tried "unifying all five colors to
 *    the same lightness" for tidiness — protanopia/deuteranopia both passed, but
 *    **tritanopia (blue-yellow colorblindness) separation collapsed from 9.6 to 3.3** —
 *    because **blue↔green discrimination under tritan vision relies entirely on their
 *    lightness difference**, and flattening it removes the only channel. So this keeps the
 *    "preserve the gradient, shift it up overall" approach, not "flatten it."
 *
 * ⚠️ **tritan is a report-only item in the validator, not a gate** (protan/deutan are the
 * gates), which is easy to overlook; my own mirror implementation didn't compute it at all,
 * and it nearly slipped through. **When changing color values, run the official validator,
 * not the mirror.**
 *
 * ⚠️ **Cost: the lowest contrast against the page dropped from 2.34 → 2.04.** This is a
 * WARN in the validator, not a FAIL, relying on the existing convention that "a color block
 * is always accompanied by a name + percentage." **After brightening, this compensation
 * carries even more weight — reworking the layout must not remove those labels.**
 */
private val LightChartColors = ChartColors(
    assetClassColors = listOf(
        Color(0xFF4D95E0), // Liquid assets — blue
        Color(0xFF40C596), // Fixed income — green
        Color(0xFFF29637), // Equity — orange
        Color(0xFF4EBFDE), // Alternative/physical — cyan
        Color(0xFFA68D21), // Protection — gold
    ),
    track = Color(0xFFE0E0E0),
    over = Color(0xFFC5453F),
    under = Color(0xFF2F6DB0),
    onTarget = Color(0xFF5C5C5C),
    // Re-solved after switching to mid-rose — see the trendArea doc below.
    trendArea = Color(0xFFF1B8CD),
)

/**
 * Dark mode is **a separately chosen set of steps**, not an automatic flip of light mode —
 * the same five hues, with lightness re-derived and independently verified against the
 * dark background.
 */
private val DarkChartColors = ChartColors(
    // ⚠️ **This dark set wasn't brightened along with the light one, because there's no
    // room.** The validator uses **a different, narrower lightness band** for dark mode:
    // light mode is [0.43, 0.77], **dark mode is only [0.48, 0.67]**. These five colors'
    // brightest step is already at 0.655, only 0.015 from the ceiling — effectively no room
    // to shift up; forcing it would be flagged as out of range immediately.
    // ⚠️ My validator mirror **applied the light-mode band to both**, so it let an
    // out-of-range set of values through; it was the official validator's `--mode dark`
    // that caught it. **Changing dark-mode color values must be run through `--mode dark`.**
    //
    // This also **rolls back the previous round's "chroma capped at 0.125 + 4% pink
    // blend"** — it pushed alternative/physical assets to the chroma floor, triggering a
    // FAIL ("reads as gray"), and also dragged colorblind separation down from 10.8 to 8.5.
    // These are the original values after rolling that back; the official validator passes
    // all five checks.
    assetClassColors = listOf(
        Color(0xFF4186CE),
        Color(0xFF00AB79),
        Color(0xFFCF7600),
        Color(0xFF219FBC),
        Color(0xFF9B8100),
    ),
    track = Color(0xFF3A3A3A),
    over = Color(0xFFE5605C),
    under = Color(0xFF8FBBE8),
    onTarget = Color(0xFFA8A8A8),
    // This dark-mode value **wasn't changed alongside the others**: it's still 2.73:1
    // against dark mode's primary, and ΔE 20.1 against the new dark asset-class colors —
    // both still hold. The light side had to change because primary was pressed down from
    // bright rose to mid-rose (see the trendArea doc); dark mode's primary wasn't touched
    // this round, so this doesn't need to change either.
    trendArea = Color(0xFF664176),
)

/**
 * Provided by [BoomssetTheme].
 *
 * Goes through CompositionLocal rather than letting each chart call
 * `isSystemInDarkTheme()` itself — the theme's light/dark mode can be overridden by an
 * explicit parameter, and each chart reading the system setting independently would go out
 * of sync with the theme (especially likely in previews and tests).
 */
val LocalChartColors = staticCompositionLocalOf { LightChartColors }

internal fun chartColorsFor(darkTheme: Boolean): ChartColors =
    if (darkTheme) DarkChartColors else LightChartColors

/** Access point for the chart color palette. */
val chartColors: ChartColors
    @Composable @ReadOnlyComposable get() = LocalChartColors.current
