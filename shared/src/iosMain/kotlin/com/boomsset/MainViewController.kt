package com.boomsset

import androidx.compose.ui.window.ComposeUIViewController
import com.boomsset.ui.App
import platform.UIKit.UIViewController

/**
 * iOS 侧的 Compose 入口，由 iosApp 的 SwiftUI 包一层 UIViewControllerRepresentable 使用。
 *
 * 待办：iOS 没有内置 ViewModelStoreOwner（AGENTS.md 约束 3），等接入 ViewModel 时
 * 要在这里把生命周期手动绑到 SwiftUI，不然 ViewModel 不会被正确回收。
 */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
