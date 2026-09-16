package com.boomsset

import androidx.compose.ui.window.ComposeUIViewController
import com.boomsset.ui.App
import platform.UIKit.UIViewController

/**
 * iOS-side Compose entry point, wrapped by iosApp's SwiftUI in a UIViewControllerRepresentable.
 *
 * TODO: iOS has no built-in ViewModelStoreOwner (AGENTS.md constraint 3) — once
 * ViewModels are wired up, the lifecycle needs to be manually bound to SwiftUI here,
 * otherwise ViewModels won't be reclaimed correctly.
 */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
