# 技术选型与版本决策

所有版本号取自 2026-07-28 对 `repo1.maven.org` / `dl.google.com/dl/android/maven2` 的
`maven-metadata.xml` 实测，不是搜索结果。锁定值在 `gradle/libs.versions.toml`。

> 顺带一提：`search.maven.org` 的索引当时严重滞后（把 CMP 报成 1.8.2、Ktor 报成 3.2.0）。
> 核版本请直接读 maven-metadata.xml。

## 版本兼容性

| 组件 | 我们锁定 | 当时最新 | 为什么 |
|---|---|---|---|
| AGP | 9.3.1 | 9.3.1 | 见下面「被迫升 AGP」 |
| Gradle | 9.5.0 | 9.6.1 | Kotlin 2.4.x 测试上限；同时满足 AGP 9.3 要求的 ≥9.5.0 |
| compileSdk | 37 | — | androidx 生态的硬下限，见下 |
| DataStore | 1.2.1 | 1.3.0-alpha09 | 1.3.0 连续九个 alpha 没进 beta |

### 被迫升 AGP：一个原计划没走通的地方

**原计划**是锁 AGP 9.1.0，留在 Kotlin 2.4.x 官方测试范围（AGP ≤9.1.0 + Gradle ≤9.5.0）内。
**实测走不通**，构建报错逼出来的：

1. `androidx.lifecycle 2.11.0` 要求 compileSdk ≥ 37
2. 降到 lifecycle 2.10.0 后，`androidx.core 1.19.0` 同样要求 compileSdk ≥ 37
3. 而 AGP 9.1.0 的 compileSdk 上限是 36

也就是说 androidx 生态的下限已经整体移到 37 了。继续锁 36 意味着要把一堆 androidx 库
逐个往回降版本，而且新增依赖时会不断复发。

**结论：升到 AGP 9.3.1 + compileSdk 37**，代价是超出 Kotlin 官方测试的 AGP 上限。
Gradle 保持 9.5.0（同时满足 Kotlin 测试上限和 AGP 9.3 的最低要求）。
`targetSdk` 保持 36 —— compileSdk 用最新、targetSdk 用测过的，这是常规做法。

本地需要 `platforms;android-37.0`（已装）。

### CMP 的 material3 走独立版本线

`org.jetbrains.compose.material3:material3` 的版本**和 compose 插件版本不一致** ——
插件 1.11.1 时它是 **1.9.0**。用 1.11.1 去引会报 `Could not find ...material3:1.11.1`。
（原来那套 `compose.material3` 简写能自动对齐版本，但 CMP 1.11 起简写已废弃，
改用显式坐标后就得自己管这个版本。已实测 material3 1.9.0 + CMP 1.11.1 在两端都能解析和编译。）

lifecycle 方面：CMP 1.11.1 自带的是 **2.11.0-beta01**，stable 2.11.0 只接到 1.12.0-beta 线上。
我们手动顶到 stable 2.11.0（这也是上面 compileSdk 37 的来源之一）。

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

### 搭脚手架时实际撞到的四个（调研没覆盖到的）

1. **`org.jetbrains.kotlin.android` 插件不能加。** AGP 9.0 起内置 Kotlin 支持，
   在 androidApp 里应用它会**直接构建失败**：
   `The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0`。
   androidApp 只需要 `com.android.application` + `org.jetbrains.kotlin.plugin.compose`。
2. **`androidLibrary {}` 已废弃，用 `kotlin { android {} }`。** 前者仍能工作但报 deprecation。
   注意这个 `android {}` 在 `kotlin {}` **里面**，和顶层那个（对 KMP 模块已不存在）不是一回事。
3. **测试 target 要显式开。** `withHostTestBuilder {}` 不写，`commonTest` 里的测试
   **在 JVM 上无处运行，且没有任何报错提示** —— 会以为"测试通过了"，其实一个都没跑。
   跑的任务名是 `testAndroidHostTest`（不是 `androidHostTest`）。
4. **`compose.runtime` 那套简写已废弃**，改显式坐标后要自己处理 material3 的独立版本线（见上）。

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

### 图表：Vico 3.2.3，坐标是 `:compose-m3`

**这里的模块命名极易搞错，务必看清。** Vico 3.0.0（2026-02）做了一次重组：
把原来 Android-only 的 `compose` 模块**删掉**，然后把跨平台的 `multiplatform` 模块**改名为
`compose`**。所以现在：

- ✅ `com.patrykandpatrick.vico:compose-m3:3.2.3` —— 跨平台，Material 3，当前 stable
- ⚠️ `com.patrykandpatrick.vico:multiplatform:2.5.2` —— 2.x 遗留线，仍在打补丁但不要用于新项目

Vico 官方发布说明的原话是 Compose Multiplatform 模块"is now stable"。已确认 3.2.3
发布了 `compose-iosarm64` / `compose-iossimulatorarm64` 的 klib，对着 CMP 1.11.1 + Kotlin 2.4.10 构建，
仓库每周有更新，Software Mansion 赞助。折线/趋势图正是它的主场（`LineCartesianLayer`）。

注意：`compose-iosx64` 停在 3.1.0，3.2.0 起砍了 Intel 模拟器 —— 对我们无影响（本来就只 arm64）。
另一个代价：3.x 每个 minor 都有 deprecation 或小破坏性改动，升级要读 release notes。

**其他选项的实测结论**（别信 README，这些是查了实际发布的 artifact 的）：
- KoalaPlot 0.12.0 支持 iOS 但 pre-1.0，且 0.12.0 真的删了一批 API。做产品依赖偏险。
- ComposeCharts 1.0.0（2026-07）、HDCharts 2.3.0 都可用且活跃，但没有 Vico 的里程数。
- **aay-chart 不能用于 iOS** —— README 声称支持，但实际 artifact 只有 android/desktop/js/wasm，
  iOS variant 停在 2023 年。
- **Kandy（JetBrains 自家）是 JVM-only**，发的是普通 jar，没有 klib。不是 CMP 方案。
- Charty 停滞：最新发布literally 打的是 `-test` tag，仓库 2025-12 之后没动过。

如果最后只在列表行里画迷你 sparkline，直接用 `Canvas` / `drawPath` 也完全合理 ——
CMP 在 iOS 上走 Skia，`DrawScope`/`Path`/`TextMeasurer` 都是共享代码，行为一致。
真正费事的是轴刻度取整、标签避让、日期轴格式化、缩放惯性、hit-testing 这些，
这才是图表库赚钱的地方。**混合用法值得考虑：正经图表用 Vico，列表行里的小 sparkline 手写 Canvas。**

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
- **multiplatform-settings 不能用来存密钥。** 两个常见误解，都已实测证伪：
  `multiplatform-settings-keychain` 这个 artifact **不存在**（`KeychainSettings` 在核心
  artifact 的 `appleMain` 里，且标注 experimental）；而且整个库**没有任何加密的 Android 后端** ——
  Android 侧只有明文 `SharedPreferencesSettings` / `DataStoreSettings`。
  存非敏感偏好可以，存钱相关的东西不行。
- **DataStore 是跨平台的，但它只给你文件容器，不给加密。** iOS 上要自己写 `Serializer`，
  密钥放 Keychain。

**结论：存储和应用锁都自己写 expect/actual。** 两个平台加起来存储约 300 行、认证约 120 行，
不值得把一个 9 star 或者仓库只有 14 个月的项目当成安全边界。

- **`androidMain` 存储**：256-bit DEK 用 AndroidKeyStore 的 AES/GCM key 包起来，
  包好的 DEK + 密文塞进 DataStore Preferences。**不要用 EncryptedSharedPreferences。**
  另外记得**把这个安全存储排除出 Android Auto Backup** —— 恢复到新设备上的密文解不开。
- **`iosMain` 存储**：`platform.Security.SecItemAdd` + `kSecClassGenericPassword` +
  `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`。可以考虑
  `SecAccessControlCreateWithFlags(..., kSecAccessControlBiometryCurrentSet)`，
  让录入的生物特征变更时条目自动失效 —— 对财务类 App 挺有价值。
- **生物识别没有成熟 KMP 库**，但好消息是 **iOS 侧不需要 cinterop**：`LocalAuthentication`
  是 Kotlin/Native 的一等 platform library，`iosMain` 里直接
  `import platform.LocalAuthentication.LAContext` 就行，零 Gradle 配置、零 `.def`、零 Obj-C shim。
  Android 侧用 stable 的 `androidx.biometric:1.1.0` + `BiometricPrompt`；
  **不要**为了 Compose 原生 API 就在财务 App 的认证路径上用 1.4.0-alpha。
  （顺带：KMPAuth 是 OAuth/社交登录，不是生物识别，别搞混；moko-biometry 已三年未维护。）
- **数据库加密：默认不做整库加密**，除非合规要求。iOS Data Protection 和 Android FBE
  已经在系统层加密了 App 私有存储。只把真正的密钥（token、应用锁 PIN 哈希）放 Keychain/Keystore。
  真要在 iOS 上跑 SQLCipher，**有个会静默失败的坑**：任何传递依赖链接了系统 `-lsqlite3`
  的库（Firebase iOS SDK 就是）会赢得符号解析，你的数据库**变成明文且不报错**，
  调整链接顺序也修不好。若非做不可，运行时务必断言 `PRAGMA cipher_version` 并检查文件头。

## 测试

组合：`kotlin-test`（runner + 基础断言）+ Kotest **assertions only** + Turbine + Compose `ui-test`，
都能在 `iosSimulatorArm64Test` 上跑。跑法：`./gradlew :shared:iosSimulatorArm64Test`
（需要 macOS + Xcode）。Kotlin/Native 上**不经过 JUnit**，是编译出测试二进制丢进模拟器跑。

按"最可能坑到你"排序：

1. **`runComposeUiTest` 已废弃。** CMP 1.11.0 起废弃了 `runComposeUiTest` /
   `runSkikoComposeUiTest` / `runDesktopComposeUiTest`，改用
   **`androidx.compose.ui.test.v2.runComposeUiTest`**（仍是 `@ExperimentalTestApi`）。
   行为有变：v2 在非 Android 平台默认 `StandardTestDispatcher` 而不是 `UnconfinedTestDispatcher`，
   照着 v1 写的测试会挂。
2. **`ui-test-junit4` 没有 iOS variant**（只有 `-android` 和 `-desktop`）。在 `commonTest` 里用 `ui-test`。
3. **不要引 Mokkery。** 它是编译器插件，兼容表只列到 Kotlin **2.4.0**，我们在 2.4.10。
   MockK 是 JVM-only。**默认手写 fake** —— 这个 App 的领域层就是几个窄接口的 repository，
   手写 fake 配 `MutableStateFlow` + Turbine 很自然，且零编译器插件/KSP/Kotlin 版本耦合。
   真要 mock 验证再上 **Mockative 3.3.2**（KSP 式，不锁死 Kotlin 版本）。
4. **`IdlingResource` 在 iOS 上不可用**（已移出 commonMain），用 `waitUntil {}`。
5. **Kotest 只用断言，不用它的 framework。** `kotest-assertions-core` 有 iOS klib，
   可以在 `commonTest` 里直接 `shouldBe`，**不需要 `io.kotest` Gradle 插件、不需要 KSP**。
   它的 framework engine 虽然确实支持 native，但注解式配置（`@EnabledIf`、`@Tags`）
   在非 JVM 平台**静默失效** —— 因为 Kotlin 不在运行时暴露注解。踩上去很难查。

⚠️ **Turbine 1.2.1 的 iOS klib 是对着 Kotlin stdlib 2.1.21 / coroutines 1.10.2 编的**，
和我们的 2.4.10 差三个 minor。2.x 内 klib 兼容一般没问题，但**早点写个冒烟测试验证**，
别等到项目中期才发现。（Turbine 本身没死，只是 2025-06 之后只有依赖升级，无功能更新。）

**assertk 不要用**：还停在 0.28.1（2024-04），iOS klib 声明的是 stdlib 1.9.21，
1.9→2.4 的 native klib 跨度会以晦涩报错的形式炸出来。

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
- JDK 17+ —— 本机 21，已验证可用
- Android SDK `platforms;android-37.0` —— 已装
- **完整 Xcode** —— **本机目前只有 Command Line Tools，尚未安装。**
  影响范围（实测）：`linkDebugFrameworkIosSimulatorArm64` 和跑模拟器不可用；
  `compileKotlinIosSimulatorArm64` **不受影响，能正常跑**。
- Android Studio + KMP 插件（用于 IDE 内的 run configuration；命令行构建不需要）

## 尚未验证的部分

诚实记录一下哪些是"已实测"、哪些还只是"文档上应该没问题"：

**已实测通过**：
- Android 构建出 APK；iOS klib 编译（含 SQLDelight native driver）
- 35 个单元测试全绿（领域计算 + 真实 SQLite 上的 schema/约束验证）
- **SQLDelight 全链路**：代码生成、枚举 adapter 双向、CHECK 约束真的拦得住、
  seed 幂等、结转查询、按天 upsert —— 都在真实 SQLite（JDBC driver）上跑过
- Compose / lifecycle / navigation / Koin / coroutines / serialization / datetime / SQLDelight
  在 `iosSimulatorArm64` 上的依赖解析

**Vico / Koin / navigation-compose 已实跑验证**（Android 模拟器）：图表带轴渲染正常、
Koin 装配成功、底部导航切换正常、SQLDelight 写入到 UI 刷新的链路通。

Vico 3.x 的两个实测细节：
- 坐标确认为 `com.patrykandpatrick.vico:compose-m3:3.2.3`，iOS klib 存在（已解析验证）
- **`lineSeries` 已废弃，用 `lineModel`**。API 是从 sources jar 里查的 ——
  这个库的坐标和 API 都改过，不要凭印象写

**Ktor 已实跑验证**（Android 真实网络请求）：3.5.1 + OkHttp（Android）/ Darwin（iOS）引擎，
两端编译通过，Android 上实际拉到了汇率。测试用 `ktor-client-mock` 的 MockEngine，
**不打真网络**。

**汇率数据源：Frankfurter（api.frankfurter.dev）**，ECB 官方数据、无需 API key、
**支持历史日期** —— 最后这点是决定性的，因为领域模型要求"折算历史净值用当时的汇率"。
实测确认两件事：
- **周末/节假日返回实际营业日**：请求 2026-07-26（周日）时响应里是 `"date":"2026-07-24"`。
  所以**必须存响应里的 date，不能存请求的日期**，否则汇率被归到 ECB 从未发布的那天。
- **只有 30 种币种，TWD 不在其中。** 不支持的币种会显示"无法估值"，
  **不会静默按 1:1 折算**（有测试锁着）。

**DataStore 没有引入** —— 见下方「刻意的偏离」。

**还没验证**：
- **股票/基金行情源**：没有可靠的免费无 key 方案。`QuoteSource` 的接口位置已经预留
  （`quote` 表 + `upsertQuote`），但没有实现，所以 `QUOTED` 资产还不能在 UI 创建。
- **Turbine 的 klib 版本差**（它的 iOS klib 是对着 Kotlin stdlib 2.1.21 编的，我们在 2.4.10）——
  它已进 commonTest 且在 JVM 上编译通过，但 **iOS 测试还没跑过**，风险仍然悬着。需要 Xcode。
- **SQLDelight 在真实 iOS 上的运行**（NativeSqliteDriver）—— 只验证了能编译，没跑过。
  测试用的是 JVM 的 JDBC driver；SQL 和约束是同一套，但 driver 不是。
- Compose UI 测试（`runComposeUiTest` v2 API）完全没碰。
- iOS 链接和真机/模拟器运行 —— 缺 Xcode。

### 刻意的偏离之二：偏好存 SQLDelight，不用 DataStore

基准币种存在 `settings` 表（SQLDelight）而不是 DataStore。理由：目前只需要存一个字符串，
而 DataStore 要引入新依赖 + okio Path + 两个平台的路径实现，且它的**非 Android 目标
至今是 Google 官方标注 experimental**。为一个字符串付这个代价不值得，也多担一份 iOS 风险。

等真有批量偏好（主题、提醒、应用锁配置）再上 DataStore，届时 `settings` 表可以迁过去。
catalog 里的 datastore 版本先留着。

### 一处刻意的偏离：不用 `expect class`

`DatabaseDriverFactory` 用的是**接口 + 各平台实现类**，不是 `expect class`。
两个原因：`expect class` 在 Kotlin 2.4 仍是 Beta（KT-61573，会报 warning）；
而且 Android 实现需要 `Context`、iOS 不需要，构造参数不同的场景用接口更自然。
后续加平台实现（Keychain/Keystore、生物识别）建议沿用这个模式。
