# AGENTS.md — 旺资（Boomsset）

## 项目状态

**脚手架已就绪，能构建。** 已验证：`:androidApp:assembleDebug` 出 APK、
`:shared:compileKotlinIosSimulatorArm64` 产出 klib、`:shared:testAndroidHostTest` 3 个测试通过、
iOS 依赖全部解析成功。

**还没做的：** 领域模型（domain.md 已定稿但未落成代码和 SQLDelight schema）、
数据层、导航、真实页面。当前 UI 只是个验证构建链路的占位屏。
`iosApp/` 缺 .xcodeproj，见 iosApp/README.md。

## 这是什么

旺资是一款**多类资产净值追踪 + 资产配置监控**应用，Android + iOS 双端。

和传统记账 App 的根本区别：**不记流水，记快照。** 用户不逐笔录入收支，而是定期更新每类资产的当前市值。

两个核心视图：

1. **净值趋势** —— "我现在身价多少、比上季度涨了还是跌了"（可按月/季/年）
2. **资产配置** —— "我的配置和预期目标差多少"（五大类占比 vs 目标配置，看偏离）

做任何功能决策时用这条判断：**它服务于「资产整体视图」还是「流水明细」？** 后者不做。

## 技术栈

版本一律以 `gradle/libs.versions.toml` 为准（那是唯一事实来源，本文不重复列版本号）。
选型理由、被否掉的方案、以及升级风险见 **[docs/stack.md](docs/stack.md)** —— 改动依赖前必读。

| 层 | 选型 |
|---|---|
| 语言 / UI | Kotlin Multiplatform + Compose Multiplatform（UI 也共享，不只共享逻辑） |
| 架构 | MVVM + 单向数据流，`ViewModel` 用 `org.jetbrains.androidx.lifecycle` |
| 导航 | `org.jetbrains.androidx.navigation:navigation-compose` |
| DI | Koin |
| 本地库 | SQLDelight（本地优先，无后端、无账号） |
| 偏好 | DataStore Preferences |
| 网络 | Ktor（只用于拉汇率/行情，不同步用户数据） |
| 图表 | Vico（坐标是 `:compose-m3`，**不是** `:multiplatform` —— 见 stack.md，这里极易搞错） |
| 测试 | kotlin-test + Kotest 断言 + Turbine + Compose ui-test；mock 默认手写 fake |

## 项目结构

```
shared/          KMP library，绝大部分代码在这
  src/commonMain/   领域模型、数据层、ViewModel、Compose UI —— 默认都写这里
  src/androidMain/  仅 Android 平台实现（SQLDelight driver、Keystore…）
  src/iosMain/      仅 iOS 平台实现（native driver、Keychain…）
  src/commonTest/   共享测试
androidApp/      Android 应用入口（com.android.application）
iosApp/          Xcode 工程
docs/            详细文档，按需查阅
```

**为什么 `androidApp` 是独立模块：** AGP 9 不再允许在 KMP 模块里应用 application 插件。
`shared` 用的是 `com.android.kotlin.multiplatform.library`，**不是** `com.android.library`。
这不是风格选择，是硬性要求。细节见 docs/stack.md。

**默认写 commonMain。** 只有真正调用平台 API 时才落到 androidMain/iosMain，通过 `expect/actual` 暴露。

## 构建与验证

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64   # iOS 编译，改完共享代码先跑这个（不需要 Xcode）
./gradlew :shared:testAndroidHostTest              # 共享代码的单元测试（跑在 JVM 上）
./gradlew :androidApp:assembleDebug                # Android 构建
```

改了共享代码后，**至少要过 `compileKotlinIosSimulatorArm64`**。只跑 Android 构建会漏掉
Kotlin/Native 特有的失败（反射、依赖缺 iOS variant）。

**哪些命令需要完整 Xcode，实测结论：**

| 命令 | 需要 Xcode？ | 能抓到什么 |
|---|---|---|
| `compileKotlinIosSimulatorArm64` | **不需要** | Kotlin/Native 编译错误。Kotlin/Native 自带 platform 库，编到 klib 不碰 iOS SDK |
| `linkDebugFrameworkIosSimulatorArm64` | **需要** | 链接期错误。缺 Xcode 会失败在 `xcrun xcodebuild -version` |
| 跑模拟器 | **需要** | 运行时问题 |

所以 CLT 环境下第一道验证照常能跑，但**过了它不等于 iOS 没问题** —— 链接错误要 Xcode 才能发现。

`:shared:allTests` 会带上 iOS 测试，在没有 Xcode 的机器上跑不过，日常用
`testAndroidHostTest`。注意 `androidHostTest` 这个 target 是在 `shared/build.gradle.kts` 里
用 `withHostTestBuilder {}` **显式开启**的 —— 新的 KMP Android 插件默认不建测试 target，
不开的话 commonTest 无处运行且没有任何提示。

iOS 工程状态见 **[iosApp/README.md](iosApp/README.md)**（.xcodeproj 尚未生成，那里写了怎么补）。

## 硬约束（踩了会浪费很多时间）

1. **iOS 只有 arm64。** `iosX64` 已移除 —— Intel Mac 连模拟器都跑不了，团队必须 Apple Silicon。iOS 最低 15.0。
2. **Kotlin/Native 没有反射。** 不能裸调 `viewModel()`，每个都要给 initializer：`viewModel { PortfolioViewModel(...) }`。
   同理，导航路由的序列化在 iOS 上要手写 `SerializersModule`，不能靠反射。
3. **iOS 没有内置 `ViewModelStoreOwner`。** 生命周期得手动绑到 SwiftUI。
4. **金额绝不用 `Double`。** 用 `Long` 存最小单位（分）或定点小数。浮点误差在净值累加上会被放大。
5. **新加依赖前先确认它有 iOS artifact。** 很多流行的 Android 库没有，**包括一些 README 明确
   声称支持 KMP 的**（实测有库的 iOS variant 三年前就停发了，README 还写着支持）。
   查 maven-metadata.xml 看有没有 `-iosarm64`，别信 README。
6. **`MainActivity` 必须继承 `FragmentActivity`，不是 `ComponentActivity`。**
   CMP 模板默认给的是 `ComponentActivity`，但 `BiometricPrompt` 的构造函数硬性要求
   `FragmentActivity`。`FragmentActivity` 本身继承自 `ComponentActivity`，`setContent {}`
   照常工作 —— 改一行的事，但等做应用锁时才发现就要返工。
7. **iOS 的 `Info.plist` 必须有 `NSFaceIDUsageDescription`**，否则首次调用 Face ID 时
   **直接崩溃**（Touch ID 不需要，Face ID 需要）。

## 领域模型

完整定义在 **[docs/domain.md](docs/domain.md)**（产品决策已定，表结构可照此实现）。要点：

- `Asset` + `Snapshot` + `Quote` + `FxRate` → 聚合出 `NetWorth` 时间序列（派生，不是表）
- **`Quote`（市场行情）和 `Snapshot`（用户持仓）必须分开。** 行情刷新只写 Quote。
  混在一起会导致快照表爆炸，且加仓会篡改历史净值 —— 原因见 domain.md
- 估值分 `QUOTED`（市值只读，= 份额 × 单价，可改**份额和成本**）和 `MANUAL`（市值和成本都可改，不刷新）。
  **成本是独立字段、与模式无关，两种模式都能填、都显示收益率** —— 别把"市值只读"误推成"成本只读"。
  存的是**总成本**，均价是派生显示值（存均价会在加仓时静默算错，见 domain.md）。
  **模式记在 `Snapshot` 上，估值一律看 `Snapshot.mode`，不看 `Asset`** ——
  `Asset` 上那个只是新快照的默认值。写反了要等到有资产退市转换后才炸，且是静默算错
- 快照**不可变、只追加**，改历史要新增记录而不是原地改；**每条是完整状态而非增量**
- 基准币种默认 CNY、可切换，作为查询参数传入，**不落到 Asset/Snapshot 上**
- 折算历史净值用**当时的汇率**，不是今天的
- 分类是**两层**：五大类（SAA 四大类 + 保障，服务配置比例）+ 品种（可自定义，服务记账）
- 配置比例的分子是**净敞口**（该类资产 − 归属到该类的负债），分母是全部净资产。
  **每条负债都必须有 `assetClass`**，漏了比例就不闭合，而且不报错、只是数字悄悄不对
- **净值增长率 ≠ 投资收益率**，前者含新增投入。两个都要显示且标签写清区别

## 边界

- **不做流水记账。** 见开头那条判断标准。
  **例外：持仓成本和浮动盈亏要做**（产品明确决定）。它记在 `Snapshot.costBasisMinor` 上，
  由用户直接填总投入，**不做逐笔买入的均价推算** —— 那才是越界。
  别把这块当成违反边界的代码删掉，详见 docs/domain.md。
- **不上传用户资产数据。** 网络层只出不进用户数据 —— 只拉公开行情，不发用户持仓。
  任何要把资产数据发到服务端的改动，先问过再动手。
- **`iosApp/` 里的 Xcode 工程文件（.pbxproj）尽量别手改**，冲突极难解。

## 给 agent 的工作约定

- 改依赖版本 → 先读 docs/stack.md，那里记了哪些版本是故意不取最新的。
- 不确定某个库在 iOS 上能不能用 → 去查 Maven 实际发布的 variant，不要凭印象回答。
- 完成一处改动后跑对应的验证命令，**如实报告结果**，测试没过就说没过。
