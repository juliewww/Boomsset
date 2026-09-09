package com.boomsset.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 应用锁的门。锁着时**完全不组合**受保护的内容 —— 不是盖一层遮罩。
 *
 * 这个区别很重要：如果只是盖遮罩，内容仍然会被组合、可能出现在系统的任务切换截图里，
 * 也可能因为动画/透明度在一瞬间露出来。不组合就不存在这些问题。
 */
@Composable
fun AppLockGate(
    state: AppLockUiState,
    onAuthenticate: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!state.shouldBlockContent) {
        content()
        return
    }

    // 锁着且能认证时自动弹一次，省得用户还要先点一下按钮
    LaunchedEffect(state.lockEnabled, state.capability) {
        if (state.lockEnabled && state.capability == AuthCapability.AVAILABLE) {
            onAuthenticate()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.loading) {
                // 读取设置期间也挡着 —— 否则开了锁的用户会看到资产数据闪一下
                Text("…", style = MaterialTheme.typography.headlineSmall)
                return@Column
            }

            Text("猪满仓已锁定", style = MaterialTheme.typography.headlineSmall)

            when (state.capability) {
                AuthCapability.AVAILABLE -> {
                    Text(
                        "验证身份后查看你的资产。",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onAuthenticate) { Text("验证") }
                }

                AuthCapability.NOT_ENROLLED -> Text(
                    "这台设备还没有设置锁屏密码或生物识别。去系统设置里加上之后就能解锁了。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )

                AuthCapability.NO_HARDWARE -> Text(
                    "这台设备不支持生物识别，也没有锁屏密码可用 —— 应用锁在这里无法工作。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )

                AuthCapability.TEMPORARILY_UNAVAILABLE -> {
                    Text(
                        "验证暂时不可用（可能是多次失败被锁定）。稍后再试。",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onAuthenticate) { Text("重试") }
                }
            }

            state.lastError?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
