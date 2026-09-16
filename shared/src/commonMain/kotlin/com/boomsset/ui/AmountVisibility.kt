package com.boomsset.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp

/**
 * Placeholder that replaces the digits when an amount is hidden.
 *
 * **Fixed length, not generated from the digit count.** Using `"•".repeat(digits)` would
 * make the placeholder's width vary with the amount's magnitude — a seven-digit and a
 * four-digit number would be distinguishable at a glance, leaking "roughly how much money"
 * out, which is exactly what's supposed to be hidden.
 *
 * No currency symbol: the symbol carries no information on its own (the "view currency"
 * control next to it already states it), and appending it would let the minus sign in
 * `-¥••••••` leak the sign back out.
 */
const val MASKED_AMOUNT: String = "••••••"

/**
 * Returns the original text when the amount is visible, the placeholder when hidden.
 *
 * Callers should always go through this one function rather than each writing their own
 * `if (hidden)` — missing one spot means one leaked number, and "hidden but leaking one"
 * is worse than "not hidden at all": the user believes it's already hidden.
 */
fun maskAmount(hidden: Boolean, text: String): String = if (hidden) MASKED_AMOUNT else text

/**
 * Eye icon: tap to toggle "are amounts visible."
 *
 * **The shape is hand-drawn, no icon dependency pulled in.** The "+"/"ⓘ"/"▾"/"✓" elsewhere
 * in the project are all written directly as glyphs, but there's no usable glyph for an
 * eye: Unicode's 👁 is a color emoji (doesn't pick up the theme color, and renders
 * differently per platform font), and a "crossed-out eye" has no single code point at all —
 * it would have to be pieced together from combining characters, which isn't guaranteed to
 * render on real device fonts. Pulling in material-icons-extended for one icon is too heavy
 * (that package needs a separate dependency on CMP). The advantage of hand-drawing it is
 * that it follows [LocalContentColor] automatically, so light/dark mode and card background
 * colors don't need separate handling.
 *
 * ⚠️ **Shapes drawn on Canvas don't produce accessibility nodes**, so [contentDescription]
 * is mandatory — without it, screen reader users would just hear "button." The description
 * states the **action**, not the state ("hide amounts" rather than "amounts visible"),
 * because it's attached to a button — what a screen reader should announce is what happens
 * when you tap it. This is also the only way UI tests can locate it.
 *
 * ⚠️ The shape itself has **no automated coverage** (the same kind of gap as the vertical
 * line in AllocationBar) — changing the drawing logic here must be eyeballed on a real
 * device/simulator.
 */
@Composable
fun AmountVisibilityToggle(hidden: Boolean, onToggle: (Boolean) -> Unit) {
    val color = LocalContentColor.current
    IconButton(
        onClick = { onToggle(!hidden) },
        // ⚠️ The accessibility semantics must be **declared entirely from scratch**
        // (name + button role + click action) — don't just attach a description.
        // `IconButton` installs `clickable` internally, so the semantics node attached
        // from outside is an **ancestor** of that clickable node. Tried each option in turn,
        // verifying with `uiautomator dump` every time (there's no seeing this without it):
        // - `semantics { contentDescription }`: two nodes — the 28dp one has a name but
        //   `clickable=false`, the 48dp clickable one has no name — the screen reader stops
        //   twice, once announcing the name without being able to tap, once just saying "button"
        // - `semantics(mergeDescendants = true)`: merges into one 48dp node with a name,
        //   but `clickable` is still false (the action doesn't get merged along with it)
        // - `clearAndSetSemantics` + explicit `onClick`: one node, named, clickable, 48dp ✅
        modifier = Modifier
            .size(TOUCH_SIZE)
            .clearAndSetSemantics {
                contentDescription = if (hidden) SHOW_LABEL else HIDE_LABEL
                role = Role.Button
                onClick {
                    onToggle(!hidden)
                    true
                }
            },
    ) {
        Canvas(
            Modifier
                .size(GLYPH_SIZE)
                // The slash needs to "cut" a transparent gap through the eye (see below);
                // `BlendMode.Clear` only erases to transparent within its own offscreen
                // layer — otherwise it would erase the entire card background instead.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val strokeWidth = h * STROKE
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

            // Almond-shaped eye outline: two symmetric quadratic Béziers, top and bottom.
            // The control points are computed so the curve's peak lands exactly at
            // cy ± RADIUS: a quadratic Bézier's midpoint is (P0 + 2·P1 + P2) / 4, and
            // both endpoints sit at cy, so ctrlY = cy ∓ 2·RADIUS. The control point itself
            // ends up off-canvas (fine, since it gets clipped), while the curve's peak
            // stays within the canvas.
            val lens = Path().apply {
                moveTo(0f, cy)
                quadraticTo(w / 2f, cy - 2f * h * RADIUS, w, cy)
                quadraticTo(w / 2f, cy + 2f * h * RADIUS, 0f, cy)
            }
            drawPath(lens, color, style = stroke)
            // The pupil is **drawn only in the "visible" state**. The slash cuts straight
            // through the center, and the pupil sits exactly at the center — drawing both
            // would erase the pupil into two small fragments (confirmed by zooming in on a
            // real device), and two extra fragments in an 18dp box only adds clutter. The
            // outline + slash alone is already enough to read as "crossed out."
            if (!hidden) {
                drawCircle(color, radius = h * PUPIL_RADIUS, center = Offset(w / 2f, cy))
            }

            // The hidden state adds a slash. **Can't rely on a subtle difference like
            // "whether the pupil is drawn" alone** — the two states must be distinguishable
            // at a glance at 18dp, or the user won't know whether amounts are currently
            // hidden or shown.
            //
            // ⚠️ This slash **was drawn wrong once, and it only showed up when zoomed in on
            // a real device** (real-device feedback: "the icon looks wrong"): it used to be
            // a short line from 0.1..0.9, with both ends landing right on the eye-outline
            // curve while the middle blended into the pupil — zoomed in, it read as a tangled
            // knot of lines, not "eye crossed out." Two fixes were needed:
            // 1. **Run edge to edge** (0.02..0.98): both ends must clearly extend past the
            //    eye outline to read as "crossed out," rather than "one extra stroke inside
            //    the eye."
            // 2. **Erase a gap before drawing the line**: use `BlendMode.Clear` with the same
            //    line at 3x the width to erase the outline and pupil underneath first, so the
            //    slash sits as a layer "on top of" the eye rather than blending into it.
            //    Material's VisibilityOff icon does exactly this — that gap is the key to why
            //    it looks clean.
            if (hidden) {
                val start = Offset(w * 0.02f, h * 0.98f)
                val end = Offset(w * 0.98f, h * 0.02f)
                drawLine(
                    color = Color.Black, // color is irrelevant under Clear mode, only its coverage area matters
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth * GAP_RATIO,
                    cap = StrokeCap.Round,
                    blendMode = BlendMode.Clear,
                )
                drawLine(
                    color = color,
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** Same size as the (i) in [InfoTooltip] — both can appear on the same line, and a size
 * mismatch would be noticeable. */
private val TOUCH_SIZE = 28.dp
private val GLYPH_SIZE = 18.dp

/** How far the eye outline's peak sits from the center line (as a fraction of height). */
private const val RADIUS = 0.30f
private const val PUPIL_RADIUS = 0.13f
private const val STROKE = 0.085f

/**
 * How wide the gap on either side of the slash is — the erase line is a multiple of the
 * stroke width.
 *
 * Too small and the gap doesn't show; too large and it bites the outline into two
 * disconnected arcs (which is what happened on a real device at 3x).
 */
private const val GAP_RATIO = 2.1f

internal const val HIDE_LABEL = "隐藏金额"
internal const val SHOW_LABEL = "显示金额"
