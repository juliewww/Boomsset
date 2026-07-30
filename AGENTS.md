# AGENTS.md — 旺资（Boomsset）

## 项目状态

**核心循环已闭环（Android 实机验证过）。** 三个页面：净值曲线 / 资产配置 / 资产列表。
添加资产 → 定期更新估值 → 归档，全流程可用。**77 个单元测试全绿。**

实跑验证过（含直接查 SQLite 确认）：更新是**追加快照**而非改写（成本正确结转），
归档追加 0 值快照且历史一字未改，配置比例加总 100%。

**行情与多币种都已打通（实机验证）：**
- 汇率：Ktor + Frankfurter（ECB，无需 key，**支持历史日期**）。实测 $1000 → ¥6,771.30
- 行情：Ktor + 腾讯财经 `qt.gtimg.cn`（**非官方接口**，无需 key，A 股/港股/美股）。
  实测 100 股 sh600519 @1334.05 → ¥133,405.00，盈亏 +11.17%
- 基准币种可切换且持久化；切换只改展示口径，快照一条都不会被改写

⚠️ **腾讯接口是非官方的**：没有文档和 ToS 保障，可能随时失效。产品定位自用/小范围，
这个风险是明确接受的。失效时的表现是资产显示「无法估值」，**不会静默算错**。
报文是 GBK 编码，用 Latin-1 逐字节读入以保住 ASCII 的价格字段 —— 别改成 UTF-8 解码。

**目标配置可编辑（实机验证）：** 多套预设可切换对比、比例可改、内置的可「恢复默认」
但不可删除。**保存强制之和为 100%** —— 不闭合的配置会让偏离度全错且不报错。

**资产元信息可编辑（实机验证）：** 改名/大类/品种/是否计入配置随时可改；
**币种和负债标记只在资产仅有一条快照时可改** —— 它们会追溯性地重新解释全部历史快照
（金额数字不变但含义变了），判据在 `AssetEditPolicy`。品种可自定义添加，归档可取消。

**iOS 已验证（Xcode 26.6 + iOS Simulator 26.5 SDK）：** framework 链接通过、
**126 个 iOS 模拟器测试全绿**，其中包含专门验 `NativeSqliteDriver` 的 `NativeDatabaseTest`
（schema 创建、枚举 adapter、CHECK 约束、事务、按天 upsert）和用到 Turbine 的
`PortfolioFlowTest`。

**iOS App 已在模拟器里跑起来（实测）：** 共享 Compose UI 正常渲染、Koin 启动成功、
SQLDelight 的 native driver 在真实 App 里创建并 seed 了数据库。
`iosApp/` 用 **XcodeGen** 生成工程 —— 提交的是 `project.yml`，`.xcodeproj` 和 `Info.plist`
都是生成物且已 gitignore。见 iosApp/README.md。

**行情有手动兜底（实机验证）：** 每条 QUOTED 资产显示行情日期，超过 3 天标记过期；
用户可手填单价覆盖。**这是腾讯接口失效时唯一的补救手段** —— 没有它，取不到价的资产
会永久显示「无法估值」。手填的价写进同一张 `quote` 表，所以当天一次成功的自动刷新会
覆盖它（有意如此：真取到市场价当然比手填准）。

**应用锁已做（Android 实机验证）：** 生物识别/锁屏密码二选一，
开启前必须先认证成功、解锁状态**不持久化**（回后台或重启都要重验）。
锁着时**完全不组合**受保护内容而不是盖遮罩 —— 后者会进任务切换截图、也可能一瞬间露出来。

**还没做的：** iOS 上的交互流程验证（没有 iOS UI 自动化，应用锁的 iOS 侧只验了能编译）。

**教训（五次都是实跑才发现、编译和单测全绿）：**
1. 空状态判据用了 `series.latest == null`，但零资产时序列仍有一串 0 值点 → 空状态永不出现
2. 预填用带千分位的 `formatAmount()`，而解析器拒绝逗号 → **≥¥1000 的资产无法更新**
3. 汇率刷新只在 ViewModel `init` 跑一次，那时还没有资产、需要的币种是空集 →
   **之后新增外币资产永远拉不到汇率**。改成随「需要的币种集合」变化触发，
   并用「已尝试」集合防止失败时无限重试（写 fx_rate 会让数据流重新发射）
4. 手误输入 1 亿股茅台 → `FixedPoint` 的溢出保护抛异常 → 异常从估值层一路逃到
   ViewModel 的 combine → **App 崩溃**。抛异常本身是对的（金额不能静默回绕），
   但**异常绝不能到达 UI**。现在估值层把溢出降级成「无法估值」：既没算错，也没崩。

5. 归档掉**最后一项**资产后，资产页走空状态分支提前 `return`，而「查看已归档」的
   展开按钮只渲染在 `LazyColumn` 里 → **那项资产在 UI 上彻底不可达**，再也取消不了归档。
   数据层一直是对的，纯粹是 UI 路径缺失。现在空状态和列表走同一条渲染路径。

**由此得出一条通则：`PortfolioCalculator` 里任何会抛异常的运算都必须在该层内被降级
成 null，不能让异常穿过 ViewModel。** 那一层是纯函数，但纯函数也会抛。

**另一条通则：空状态不要用提前 `return` 实现。** 提前 return 会把「空状态下仍然需要的
入口」（已归档、设置、帮助）一并砍掉。第 1 条和第 5 条都属于这一类。

第 2 条的教训是：格式化和解析各自都有测试，**但没有测试跨过它们之间的接缝**。
现在有 `InputRoundTripTest` 锁住「预填的字符串必须能被自己的解析器读回原值」。
**给输入框预填数值一律用 `formatForInput()`，不要用 `formatAmount()`。**

**领域计算的规则都在 [PortfolioCalculator](shared/src/commonMain/kotlin/com/boomsset/domain/PortfolioCalculator.kt)**，
纯函数无 IO，改之前先读 docs/domain.md。

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
./gradlew :shared:testAndroidHostTest              # 共享代码的单元测试（跑在 JVM 上，146 个）
./gradlew :shared:iosSimulatorArm64Test            # iOS 模拟器测试（126 个，需要 Xcode）
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64  # iOS 链接（需要 Xcode）
./gradlew :androidApp:assembleDebug                # Android 构建
./gradlew :shared:allTests                         # 两端一起
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

**JVM 和 iOS 的测试数不一样（146 vs 126），这是对的**：
- 数据库测试（`DatabaseSchemaTest` / `AllocationEditingTest` / `AssetEditingTest`）在
  `androidHostTest`，用 JVM 的 JDBC driver
- `iosTest/NativeDatabaseTest` 单独验 iOS 的 `NativeSqliteDriver`（**不同的 SQLite 构建**，
  CHECK 约束能否拦住是运行时行为）

注意 `androidHostTest` 这个 target 是在 `shared/build.gradle.kts` 里用
`withHostTestBuilder {}` **显式开启**的 —— 新的 KMP Android 插件默认不建测试 target，
不开的话 commonTest 无处运行且没有任何提示。

**加了跨平台的库之后，别只看 `compileKotlinIosSimulatorArm64` 通过就算完。**
链接器会丢掉没被引用的符号，所以一个「声明了但没有任何测试导入」的依赖，
连编译都证明不了它在 iOS 上能用。Turbine 就当了很久这样的依赖 ——
现在 `PortfolioFlowTest` 真正用到它了。

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
8. **iOS 的 `Info.plist` 还必须有 `CADisableMinimumFrameDurationOnPhone`**，否则
   **App 一启动就崩** —— CMP 的 `PlistSanityCheck` 主动抛异常。
   这个崩溃**没有崩溃报告、系统日志里也查不到**（异常在 dispatch queue 上），
   只有 `xcrun simctl launch --console` 能看到。**排查 iOS 启动问题从 --console 开始。**
9. **`BiometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` 不能只查组合值。**
   没录入生物识别时它返回 `NONE_ENROLLED`，**即使设备设了锁屏密码、认证实际能成功** ——
   结果是设备明明能用应用锁却告诉用户开不了。必须分别查两种再取「任一可用」。
10. **Xcode target 必须显式 `-lsqlite3`**，否则链接失败在 `_sqlite3_step` 未定义。
   ⚠️ **iOS 单元测试全过不能证明 App 能链接** —— Kotlin/Native 链接测试可执行文件时
   继承了 cinterop 的 linker opts，但静态 framework 交给 Xcode 后那些 opts 不传递。

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
