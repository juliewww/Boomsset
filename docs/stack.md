# 技术选型与版本决策

所有版本号取自 2026-07-28 对 `repo1.maven.org` / `dl.google.com/dl/android/maven2` 的
`maven-metadata.xml` 实测，不是搜索结果。锁定值在 `gradle/libs.versions.toml`。

> 顺带一提：`search.maven.org` 的索引当时严重滞后（把 CMP 报成 1.8.2、Ktor 报成 3.2.0）。
> 核版本请直接读 maven-metadata.xml。

## 版本兼容性：几个故意不取最新的地方

| 组件 | 我们锁定 | 当时最新 | 为什么 |
|---|---|---|---|
| AGP | 9.1.0 | 9.3.1 | Kotlin 2.4.x 官方测试上限是 AGP 9.1.0 |
| Gradle | 9.5.0 | 9.6.1 | 同上，Kotlin 2.4.x 测试上限 9.5.0 |
| DataStore | 1.2.1 | 1.3.0-alpha09 | 1.3.0 连续九个 alpha 没进 beta |

"未测试"不等于"坏掉"，但新项目没必要去当那个第一个踩坑的人。

CMP 1.11.1 自带的 lifecycle 是 **2.11.0-beta01**；stable 2.11.0 只接到了 1.12.0-beta 线上。
我们在 catalog 里手动顶到 stable 2.11.0。

## AGP 9 的模块结构要求（硬性）

AGP 9 起：

- `com.android.library` + KMP 不再能共存 → KMP 模块用 **`com.android.kotlin.multiplatform.library`**
- `com.android.application` + KMP 也不能共存 → **Android 入口必须是独立 subproject**

所以老教程里那种单 `composeApp` 模块的布局已经作废了，别照抄。其他连带变化：

- 顶层 `android { }` 块没了，配置移到 `kotlin { android { ... } }` 里
- 源码目录 `src/main` → `src/androidMain`
- 新插件的限制：**只支持单 variant，没有 build type / product flavor**，没有 BuildConfig、
  没有 view binding、没有 NDK。Java 编译、host test、device test、Android 资源都要显式 opt-in
  （`withJava()`、`withHostTestBuilder {}`、`withDeviceTestBuilder {}`、`androidResources { enable = true }`）
- 逃生舱 `android.enableLegacyVariantApi=true` 在 AGP 10 会失效，别依赖它

JetBrains 的 wizard（kmp.jetbrains.com）2026 年 5 月起已经输出新结构，需要
IntelliJ 2026.1.2+ / Android Studio Otter 3 Feature Drop+。

## 有争议的几个选择

### 数据库：SQLDelight（否掉 Room）

两个都真正支持 iOS，不是稳定 vs alpha 的问题。选 SQLDelight 的理由：

1. **不需要 KSP。** Room 要给每个 target 单独注册（`kspAndroid` / `kspIosArm64` /
   `kspIosSimulatorArm64` / `kspIosX64`），这是 KMP 构建最常见的摩擦源。而且当时
   **KSP 停在 2.3.10、Kotlin 已经 2.4.10** —— 这个版本差是 day-one 最可能卡住的地方。
   SQLDelight 用自己的 Gradle 插件，绕开整个问题。
2. **SQL-first 契合这个 App。** 净值计算本质是时间序列聚合（按日期分组、按类别汇总、
   币种折算），这些用 SQL 写比用 DAO 注解自然，而且 SQLDelight 会对着真实 schema
   做编译期校验。
3. **iOS 支持没有星号。** Room 在非 Android 平台有一串排除项（预置数据库、
   `setQueryCallback`、多实例失效通知都不可用），且 **DAO 函数在非 Android 上必须全是
   `suspend`**。SQLDelight 是 KMP 原生设计，没这些例外。
4. Room 最后一个 release 是 2025-11，八个月没动静；SQLDelight 2.3.2 是 2026-03。

**什么情况下该换回 Room：** 团队 Room 经验深、想要注解式 entity 而非 `.sq` 文件、
或者要 Google 一方支持和 Paging 集成。Room 2.8.4 + `androidx.sqlite:sqlite-bundled:2.7.0`
（用 `BundledSQLiteDriver`，别用平台 SQLite，否则两端 SQLite 版本会漂移）。

### 导航：navigation-compose 2.9.2（暂不上 Nav3）

Nav3 在 CMP 上**是可用的**，但有个坑：Google 的 `navigation3-runtime` 是真 KMP，
可 Google 的 `navigation3-ui` 只发 `-android` 和 stubs —— `NavDisplay` 是 Android-only。
要在 CMP 用 Nav3，UI 层必须换成 JetBrains 的 `org.jetbrains.androidx.navigation3:navigation3-ui`。
只看 Google 的 Nav3 文档会被误导。

暂时不上的原因：Nav3 在 iOS 上路由序列化要手写 `SerializersModule`（反射式路由是 JVM 限定），
`adaptive-navigation3` 还在 alpha，而 navigation-compose 2.9.2 自 2025-09 稳定、文档充分。
起步阶段选稳的。

**Voyager 否掉：** 没有 stable 1.1.0，2024-10 之后没有功能更新，最新构建还停在 CMP 1.10.3。
**Decompose 3.5.0** 只在"某些页面要用原生 SwiftUI"时才值得考虑。

### DI：Koin 4.2.2

规模不大的情况下 Koin 的运行时开销可以忽略，好处很实在：不用 codegen，
迭代最快，没有 per-target KSP 配置。`koin-compose-viewmodel` 直接对接 multiplatform ViewModel。
运行时报错的老问题可以用 `verify()` 测试兜住。

**Metro 1.3.2** 是真正的替代选项 —— 2026-04 发的 1.0，编译期校验，KMP 原生，
而且是**编译器插件而非 KSP**（所以没有 per-target 配置问题），JetBrains 自己的 KotlinConf App
已经从 Koin 迁到了 Metro。如果后面模块数量涨起来、或者受不了运行时 DI 失败，换它。
代价是编译器插件会绑死 Kotlin 版本，可能挡住 Kotlin 升级。

**kotlin-inject 不要选**：五年了还是 0.9.0，Metro 基本是它的超集。

Google 和 JetBrains **都没有**官方的 KMP DI 推荐，这块是社区自选。

### 图表：Vico multiplatform 2.5.2

调研里最意外的一条：Vico 现在有真正的 CMP artifact，发布了 `iosarm64` /
`iossimulatorarm64` variant，稳定在 2.5.x。**注意用 `:multiplatform` 而不是 `:compose`** ——
后者线上是 `3.3.0-next.1` 预发布版。

KoalaPlot 0.12.0 也支持 iOS 但还是 pre-1.0，做内部工具行，做产品依赖偏险。

如果最后只画一两条简单趋势线，直接用 `Canvas` / `drawPath` 也完全合理，样式完全可控，
代价是轴、手势、tooltip 都得自己写。

### 时间：用标准库的 `kotlin.time`，不用 kotlinx-datetime 的

`kotlin.time.Instant` 和 `kotlin.time.Clock` 从 Kotlin 2.3.0 起是稳定的，
而 kotlinx-datetime 0.7.0 **移除了**自己的 `Instant`/`Clock`。
kotlinx-datetime 0.8.0 只用来拿 `LocalDate`、`TimeZone` 和格式化。

注意有些三方库还在用旧类型，有 `-0.6.x-compat` artifact 过渡，但 JetBrains 在 0.8.x 之后不再发。

kotlinx-datetime 本身还是 0.x 且自称 experimental，这是已知风险。

### 网络：Ktor 3.5.1，跳过 3.5.0

3.5.1 修了几个 iOS Darwin 的 bug，其中一个 WebSocket 在 close 后收到 PONG 会直接崩进程。
用 `ktor-client-darwin`，`DarwinLegacy` 在 3.4.0 已废弃。

## 安全存储：生态最薄弱的一环，要有心理预期

- `androidx.security:security-crypto` 虽然到了 1.1.0，但 **`EncryptedSharedPreferences` 已废弃**
  （Keystore 可靠性和性能问题）。新项目别用。
- Google 的替代品 `androidx.datastore:datastore-tink` **只发 `-android` 和 `-jvm`，不是 multiplatform**，
  没法作为共享层方案。
- **可行做法**：`multiplatform-settings 1.3.0` 的 `KeychainSettings`（iOS）+ 自己写一层
  expect/actual 包 Android Keystore。接受两端底层原语不同、只统一接口。
  （注意 multiplatform-settings 最后发布是 2024-11，已停滞近 20 个月，但它接口很薄，风险可控。）
- **生物识别：没有成熟的 KMP 库。** `androidx.biometric` 最新 stable 是 2021 年的 1.1.0，
  `biometric-compose` 只有 1.4.0-alpha07。自己写 expect/actual 包 Android 的 `BiometricPrompt`
  和 iOS 的 `LAContext`，大约一百行，比引入小众依赖靠谱。
- **数据库加密**：SQLCipher 在 iOS 侧没有干净的 KMP 对应物。iOS 上依赖系统的
  Data Protection（文件级）而不是指望共享的 SQLCipher 方案。

## 测试

`kotlin-test` + Turbine 1.2.1 + Compose `ui-test` 1.11.1 都能在 `iosSimulatorArm64Test` 上跑。

- Compose UI 测试在 iOS 上可用但仍是 `@ExperimentalTestApi`，且 JUnit `TestRule` API 是
  desktop 限定 —— 用 `runComposeUiTest`。
- **MockK 是 JVM-only**，跨平台用 Mokkery 3.4.2，或者干脆手写 fake。
- Kotest 6.2.3 确实发了 iOS klib（和它"JVM-only"的旧印象相反），但我们暂时不引入，
  kotlin-test 够用。

## 已知未解风险

1. **KSP 2.3.10 落后于 Kotlin 2.4.10。** 我们选 SQLDelight 已经绕开了主要影响面，
   但如果后面引入任何 KSP-based 库（Room、koin-annotations、Mokkery 某些配置），
   先验证这个组合能不能构建。
2. **KMP + Gradle configuration cache** 和 Kotlin/Native task 长期有兼容问题（KT-44900），
   没有找到一方声明完全支持。遇到诡异构建失败先试着关掉它。
3. Metro 在 native/Wasm 上的 contribution hint 生成据其文档需要 Kotlin 2.3.20-Beta1+，
   未能直接确认现状 —— 若将来迁 Metro 需复核。

## 环境要求

- **Apple Silicon Mac**（iosX64 已移除，Intel 机器跑不了 iOS 模拟器）
- **Xcode**（Kotlin 2.4 面向 Xcode 26.4；目前本机只有 Command Line Tools，需安装完整 Xcode）
- JDK 17+（本机 21，可用）
- Android Studio + KMP 插件（本机 Android Studio 已装，**KMP 插件未装**）
- CocoaPods 可选但推荐；若装，`~/.zprofile` 需要 `export LANG=en_US.UTF-8` 和 `export LC_ALL=en_US.UTF-8`
