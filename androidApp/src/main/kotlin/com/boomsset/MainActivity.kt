package com.boomsset

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.boomsset.ui.App

/**
 * 必须继承 FragmentActivity，**不是** ComponentActivity。
 *
 * CMP 模板默认给的是 ComponentActivity，但 BiometricPrompt 的构造函数硬性要求
 * FragmentActivity。FragmentActivity 本身继承自 ComponentActivity，setContent {} 照常工作。
 * 见 AGENTS.md 约束 6 —— 等做应用锁时才发现就要返工。
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
