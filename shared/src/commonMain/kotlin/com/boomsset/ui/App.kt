package com.boomsset.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boomsset.domain.Money
import com.boomsset.platformName

/**
 * 共享 UI 入口。两端（androidApp 的 MainActivity、iosApp 的 ComposeUIViewController）
 * 都调这个。
 *
 * 当前只是个骨架占位，用来验证构建链路。真正的导航和页面在领域层落地后再接。
 */
@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("旺资 Boomsset", style = MaterialTheme.typography.headlineMedium)
                Text("脚手架就绪 · $platformName", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "净值 ${Money(123456).minorUnits} 分",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
