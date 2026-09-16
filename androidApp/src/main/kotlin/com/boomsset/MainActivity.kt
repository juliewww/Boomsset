package com.boomsset

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.boomsset.security.CurrentActivityHolder
import com.boomsset.ui.App

/**
 * Must extend FragmentActivity, **not** ComponentActivity.
 *
 * The CMP template defaults to ComponentActivity, but BiometricPrompt's constructor strictly
 * requires FragmentActivity. FragmentActivity itself extends ComponentActivity, so setContent {}
 * still works as normal. See AGENTS.md constraint 6 — finding this out only once the app lock is
 * being built means redoing the work.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // BiometricPrompt's constructor requires a FragmentActivity, and the shared layer can't
        // hold onto an Activity. Bridge it with a weak-reference holder, see CurrentActivityHolder.
        CurrentActivityHolder.set(this)
        setContent { App() }
    }

    override fun onDestroy() {
        CurrentActivityHolder.clear(this)
        super.onDestroy()
    }
}
