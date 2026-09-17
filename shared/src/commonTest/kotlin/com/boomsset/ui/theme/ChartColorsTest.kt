package com.boomsset.ui.theme

import androidx.compose.ui.graphics.Color
import com.boomsset.domain.AssetClass
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

/**
 * Locks down the chart color palette.
 *
 * Unit tests can't run color-blindness simulation or contrast calculations — those are
 * run offline with the dataviz skill's validator. What these tests do is **prevent
 * someone from casually changing an already-validated color value without
 * re-validating it**: change a color value and these assertions break, and once they
 * break you have to go back and rerun the validator.
 *
 * How to rerun it (both modes are required; the background color is **the layer the
 * shapes actually render on**):
 * ```
 * node scripts/validate_palette.js "<five light hex values>" --mode light --surface "#FEF9F4"
 * node scripts/validate_palette.js "<five dark hex values>" --mode dark  --surface "#211C17"
 * ```
 * WARNING: **the `--mode dark` run must actually be run — you can't just run the light
 * one and reuse it.** The validator gives the two modes **different lightness bands**:
 * light is [0.43, 0.77], **dark is only [0.48, 0.67]**. Judging dark against the light
 * band would let through a set of values that are actually out of range (this really
 * happened once).
 *
 * WARNING: the validator also reports a **tritan (blue-yellow color blindness)
 * separation** score, which is a **reported figure, not a gate**, so it's easy to
 * overlook — but it's a real degradation. In this project, **the blue↔green tritan
 * distinction relies entirely on their lightness difference**, so any idea of "pulling
 * all five colors to the same lightness" would break it (measured 9.6 → 3.3).
 */
class ChartColorsTest {

    @Test
    fun `every asset class has a color, and they are all mutually distinct`() {
        listOf(chartColorsFor(darkTheme = false), chartColorsFor(darkTheme = true)).forEach { c ->
            c.assetClassColors shouldHaveSize AssetClass.displayOrder.size
            // The five colors must be mutually distinct — a duplicate would make two
            // asset classes indistinguishable on the chart
            c.assetClassColors.toSet() shouldHaveSize AssetClass.displayOrder.size
            AssetClass.displayOrder.forEach { assetClass ->
                c.of(assetClass) shouldNotBe c.track
            }
        }
    }

    /**
     * `of()` must take the color by [AssetClass.displayOrder]'s index, **not the enum's
     * ordinal**.
     *
     * Display order is a product decision; if it were taken by ordinal, reordering the
     * enum declaration in the future would silently swap every asset class's color —
     * and the color order is exactly the mechanism that guarantees color-blind safety,
     * so swapping it would invalidate the already-validated set.
     */
    @Test
    fun `colors are taken by display order, not enum declaration order`() {
        val c = chartColorsFor(darkTheme = false)
        AssetClass.displayOrder.forEachIndexed { index, assetClass ->
            c.of(assetClass) shouldBe c.assetClassColors[index]
        }
    }

    /**
     * The validated color values. Changing any one of them requires rerunning the
     * validator — see the class comment.
     *
     * Measured result (OKLab ΔE ×100):
     * light worst adjacent pair 11.5 / normal vision 20.4; dark 10.8 / normal vision 19.4.
     * The thresholds are 8 / 15.
     *
     * The first four hues are taken from Youzhiyouxing, but passed through
     * snap-to-passing (hue angle unchanged, lightness and chroma nudged into compliance).
     *
     * WARNING: **the fifth slot, "Protection", was swapped out later**: it used to be
     * purple `#585CA2`, and was moved to gold to make room for the brand purple. The
     * direction of the move wasn't picked arbitrarily — see the class comment on
     * ChartColors for details. After the swap, both palettes were rerun through the
     * dataviz validator and all six checks passed.
     */
    @Test
    fun `color values are exactly the validated set`() {
        // Light is a brightened variant ("shift everything up by 0.05, narrow the
        // gradient slightly, chroma unchanged"), from feedback that "the colors
        // shouldn't be this dark"; dark **has no room to spare** (the validator's
        // lightness band for dark is only [0.48,0.67] and the current values are
        // already pinned to the ceiling), so it's kept as-is.
        // WARNING: both were run through the **official validator** (not a hand-rolled
        // mirror — a mirror once missed computing tritan, and once wrongly applied the
        // light lightness band to dark; both times it nearly let bad values through):
        //   node scripts/validate_palette.js "<five light hex values>" --mode light --surface "#FEF9F4"
        //   node scripts/validate_palette.js "<five dark hex values>" --mode dark  --surface "#211C17"
        chartColorsFor(darkTheme = false).assetClassColors shouldBe listOf(
            Color(0xFF4D95E0), Color(0xFF40C596), Color(0xFFF29637),
            Color(0xFF4EBFDE), Color(0xFFA68D21),
        )
        chartColorsFor(darkTheme = true).assetClassColors shouldBe listOf(
            Color(0xFF4186CE), Color(0xFF00AB79), Color(0xFFCF7600),
            Color(0xFF219FBC), Color(0xFF9B8100),
        )
    }

    /**
     * Deviation uses a **diverging color scheme**: two opposing hues + a neutral
     * midpoint.
     *
     * The midpoint must be a neutral color — if "on target" were also a hue, readers
     * would treat "no deviation" as a state that also needs attention.
     */
    @Test
    fun `the three deviation states are mutually distinct, and over-allocation does not reuse the error color`() {
        listOf(chartColorsFor(false), chartColorsFor(true)).forEach { c ->
            setOf(c.over, c.under, c.onTarget) shouldHaveSize 3
            // Over-allocation is not an error, so it can't reuse `error`. The light theme's error is #B3261E
            c.over shouldNotBe Color(0xFFB3261E)
        }
    }

    /**
     * The asset-class colors must not include the red/blue used for deviation —
     * otherwise "blue" would mean both a specific asset class and "under-allocated" on
     * the same screen.
     */
    @Test
    fun `asset-class colors and deviation colors do not overlap`() {
        listOf(chartColorsFor(false), chartColorsFor(true)).forEach { c ->
            val deviation = setOf(c.over, c.under, c.onTarget)
            c.assetClassColors.forEach { it shouldNotBe null }
            (c.assetClassColors.toSet() intersect deviation) shouldBe emptySet()
        }
    }

    /** When the asset-class count grows but no color was given, it must fall back to the neutral track color — never crash, and never "generate" an unvalidated color. */
    @Test
    fun `an asset class with no assigned color falls back to the track color`() {
        val short = chartColorsFor(false).copy(assetClassColors = listOf(Color(0xFF2A78D6)))
        short.of(AssetClass.displayOrder.first()) shouldBe Color(0xFF2A78D6)
        short.of(AssetClass.displayOrder.last()) shouldBe short.track
    }
}
