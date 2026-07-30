package com.boomsset.security

/**
 * 设备上可用的认证能力。
 *
 * 区分这几种状态是必要的，因为它们对用户的意义完全不同：
 * 「设备不支持」是无解的，「没录入」是用户去系统设置里加一下就行的。
 * 把两者都显示成"无法开启"会让后者的用户不知道该干什么。
 */
enum class AuthCapability {
    /** 可用（生物识别或设备密码任一可用） */
    AVAILABLE,

    /** 硬件支持但用户没录入任何生物特征、也没设密码 —— 引导去系统设置 */
    NOT_ENROLLED,

    /** 设备没有相应硬件 —— 无解，别让用户白折腾 */
    NO_HARDWARE,

    /** 暂时不可用（比如多次失败被锁定） */
    TEMPORARILY_UNAVAILABLE,
}

/** 一次认证的结果。 */
sealed interface AuthResult {
    data object Success : AuthResult

    /** 用户主动取消（点了取消或返回）。**不是错误**，不要报错提示。 */
    data object Cancelled : AuthResult

    /** 认证失败或不可用。[message] 面向用户，可为 null（无额外信息可说）。 */
    data class Failed(val message: String?) : AuthResult
}

/**
 * 生物识别 / 设备密码认证。
 *
 * 用接口 + 各平台实现，不用 `expect class` —— 和 [com.boomsset.data.DatabaseDriverFactory]
 * 同样的理由（expect class 在 Kotlin 2.4 仍是 Beta，且 Android 实现需要 Activity）。
 *
 * **故意不引第三方 KMP 生物识别库。** docs/stack.md 记了实测结论：这个领域没有成熟的
 * KMP 库（moko-biometry 三年未维护、biometrik 只有 9 star、KMPAuth 其实是 OAuth）。
 * 自己写两端各几十行，比把一个小众项目当成安全边界靠谱。
 */
interface AppLockAuthenticator {
    fun capability(): AuthCapability

    /**
     * 弹出系统认证界面。挂起直到用户完成或取消。
     *
     * @param reason 展示给用户的说明。iOS 的 `LAContext` 要求非空，Android 显示在副标题。
     */
    suspend fun authenticate(reason: String): AuthResult
}
