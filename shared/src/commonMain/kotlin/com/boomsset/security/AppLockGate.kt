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
 * The app lock gate. While locked, protected content is **not composed at all** —
 * it's not just covered by an overlay.
 *
 * This distinction matters: if it were just an overlay, the content would still be
 * composed, could show up in the system's task-switcher screenshot, and might flash
 * into view for an instant due to animation/alpha. Not composing it at all avoids
 * all of that.
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

    // Auto-trigger once when locked and authentication is available, so the user
    // doesn't have to tap a button first
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
                // Also blocked while settings are loading — otherwise a user with the
                // lock enabled would see their asset data flash on screen
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
