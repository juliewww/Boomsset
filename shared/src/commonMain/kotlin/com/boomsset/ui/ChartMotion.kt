package com.boomsset.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween

/**
 * Vico's built-in diff-animation defaults are 500ms for Cartesian charts and 1000ms for pie
 * charts (`Animation.DIFF_DURATION` / `Animation.PIE_DIFF_DURATION` in
 * `com.patrykandpatrick.vico.compose.common.Defaults`, vico 3.2.3). Both run well past the M3
 * motion spec's guidance for ordinary UI transitions (~200-300ms; only large-scale, full-screen
 * transitions go past 450ms). Every chart host in this app passes this instead so switching
 * period/mode/style, or toggling an allocation target, doesn't feel sluggish.
 */
val chartAnimationSpec: AnimationSpec<Float> = tween(durationMillis = 300)
