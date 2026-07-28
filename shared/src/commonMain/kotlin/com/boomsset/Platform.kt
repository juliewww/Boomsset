package com.boomsset

/**
 * 平台信息。存在的意义主要是验证 expect/actual 链路在两端都通。
 * 真正的平台实现（SQLDelight driver、Keychain/Keystore、生物识别）之后按同样模式加。
 */
expect val platformName: String
