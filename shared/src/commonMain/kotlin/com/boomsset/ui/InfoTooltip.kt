package com.boomsset.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * An (i) icon + a tap-to-reveal explanation bubble.
 *
 * Used to tuck away explanatory text that's confusing at first glance but **doesn't need
 * to stay always visible** — sentences like the allocation page's "which target set is
 * being compared" / "the built-in preset is a common industry starting point..." or the
 * net worth page's "switching currency only changes the display basis" take up space and
 * feel wordy when shown permanently (real-device feedback). `TooltipBox` defaults to a
 * long-press/hover trigger; here `state.show()` is called manually inside `onClick`,
 * because a single tap on a touchscreen matches the intuition of "tap the (i) to see the
 * explanation" better than a long press — and the allocation page already uses "long
 * press" for the target chip. The same screen shouldn't have two gestures each bound to a
 * different meaning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InfoTooltip(text: String) {
    // isPersistent = true: the default bubble **dismisses itself after 1.5 seconds**
    // (confirmed with rapid-fire screenshots on a real device), while what's shown here is
    // three or four lines of Chinese text that takes several seconds to read — an
    // explanation the user actively opened must wait for a tap elsewhere to dismiss, or it
    // amounts to hiding the text somewhere there's no time to read it.
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = tooltipState,
    ) {
        IconButton(
            onClick = { scope.launch { tooltipState.show() } },
            modifier = Modifier.size(28.dp),
        ) {
            Text("ⓘ", style = MaterialTheme.typography.labelMedium)
        }
    }
}
