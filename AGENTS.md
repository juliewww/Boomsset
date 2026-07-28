# AGENTS.md — 旺资（Boomsset）

## 项目状态

**脚手架尚未生成。** 目前仓库只有 README、.gitignore、本文件、`docs/` 和 `gradle/libs.versions.toml`。
下一步是按下面的结构初始化 Gradle 工程。在那之前，本文里的构建命令都还跑不通。

## 这是什么

旺资是一款**多类资产净值追踪**应用，Android + iOS 双端。

和传统记账 App 的根本区别：**不记流水，记快照。** 用户不逐笔录入收支，而是定期更新每类资产的当前市值，
App 负责算总净值、折算币种、画趋势曲线。核心问题是"我现在身价多少、比上季度涨了还是跌了"，
不是"这个月餐饮花了多少"。

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
./gradlew :shared:compileKotlinIosSimulatorArm64   # iOS 编译（最容易崩的一环，改完先跑这个）
./gradlew :shared:allTests                          # 全平台测试
./gradlew :androidApp:assembleDebug                 # Android 构建
```

改了共享代码后，**至少要过 `compileKotlinIosSimulatorArm64`**。只跑 Android 构建会漏掉
Kotlin/Native 特有的失败（反射、线程、依赖缺 iOS variant）。

iOS 跑模拟器需要 Xcode，用 `iosApp/` 里的 Xcode 工程或 IDE run configuration。

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
- 资产分 `QUOTED`（市值只读，= 份额 × 单价，可改份额）和 `MANUAL`（市值可改，不刷新）
- 快照**不可变、只追加**，改历史要新增记录而不是原地改
- 基准币种默认 CNY、可切换，作为查询参数传入，**不落到 Asset/Snapshot 上**
- 折算历史净值用**当时的汇率**，不是今天的

## 边界

- **不做流水记账。** 见开头那条判断标准。
- **不上传用户资产数据。** 网络层只出不进用户数据 —— 只拉公开行情，不发用户持仓。
  任何要把资产数据发到服务端的改动，先问过再动手。
- **`iosApp/` 里的 Xcode 工程文件（.pbxproj）尽量别手改**，冲突极难解。

## 给 agent 的工作约定

- 改依赖版本 → 先读 docs/stack.md，那里记了哪些版本是故意不取最新的。
- 不确定某个库在 iOS 上能不能用 → 去查 Maven 实际发布的 variant，不要凭印象回答。
- 完成一处改动后跑对应的验证命令，**如实报告结果**，测试没过就说没过。
