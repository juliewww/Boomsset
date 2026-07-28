# iosApp

## 当前状态：Xcode 工程文件（.xcodeproj）尚未创建

这里只有 Swift 源码和 `Info.plist`。**`project.pbxproj` 是故意没有生成的** ——
AGENTS.md 里明确写了那个文件尽量别手写/手改（冲突极难解），手写一份几百行的 pbxproj
出错率高且难以校验。

## 怎么补上

任选其一：

1. **Xcode 新建**（推荐）：New Project → iOS App → Interface 选 SwiftUI，
   产品名 `iosApp`，Bundle ID `com.boomsset`。然后：
   - 删掉 Xcode 自动生成的 `ContentView.swift` / `xxxApp.swift`
   - 把本目录的 `BoomssetApp.swift` 和 `Info.plist` 加进 target
   - 按下面「连接 shared framework」配置

2. **KMP 向导**：去 [kmp.jetbrains.com](https://kmp.jetbrains.com) 生成一个同名工程，
   把它的 `iosApp/iosApp.xcodeproj` 拷过来，再按需改 Bundle ID 和 framework 名。

## 连接 shared framework

`shared` 模块产出的 framework 名是 **`SharedKit`**（静态库），见 `shared/build.gradle.kts`。

Xcode target 需要：

- **Build Phases → 新增 Run Script**（放在 Compile Sources 之前）：
  ```sh
  cd "$SRCROOT/.."
  ./gradlew :shared:embedAndSignAppleFrameworkForXcode
  ```
- **Build Settings → Framework Search Paths** 加上：
  `$(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`
- **iOS Deployment Target 设为 15.0**（Kotlin 2.4 的下限）
- **架构只有 arm64** —— `iosX64` 已被移除，Intel Mac 跑不了模拟器

## 前置条件

需要**完整 Xcode**，不是 Command Line Tools。当前机器上只有 CLT，
所以 `linkDebugFrameworkIosSimulatorArm64` 会失败在 `xcrun xcodebuild -version`。

注意 `:shared:compileKotlinIosSimulatorArm64`（只编 klib）**不需要 Xcode 就能跑** ——
改共享代码后的第一道验证不受此阻塞。
