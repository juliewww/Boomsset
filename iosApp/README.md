# iosApp

## 工程文件是生成的，不提交

仓库里只有 **`project.yml`**（XcodeGen 的定义）。`.xcodeproj` **和 `Info.plist`** 都是
生成物、已进 .gitignore —— XcodeGen 会按 project.yml 里的 `properties` 原地重写 Info.plist，
所以那份 YAML 是唯一事实来源。

这样做的原因：AGENTS.md 说 `.pbxproj`「尽量别手改，冲突极难解」。把它变成生成物之后，
需要 review 和合并的是一份二十几行的 YAML，那条约束的根因就消掉了。

## 怎么跑

```bash
brew install xcodegen        # 只需一次
cd iosApp && xcodegen generate
open iosApp.xcodeproj        # 或者用下面的命令行
```

命令行构建 + 跑模拟器：

```bash
UDID=$(xcrun simctl list devices available | grep -m1 'iPhone 16 (' | grep -oE '[0-9A-F-]{36}')
cd iosApp
xcodebuild -project iosApp.xcodeproj -scheme iosApp -destination "id=$UDID" -configuration Debug build
APP=$(find ~/Library/Developer/Xcode/DerivedData/iosApp-*/Build/Products/Debug-iphonesimulator -maxdepth 1 -name 'iosApp.app' | head -1)
xcrun simctl install "$UDID" "$APP"
xcrun simctl launch --console "$UDID" com.boomsset     # --console 很重要，见下
```

`project.yml` 里的 Run Script 会自动调 `:shared:embedAndSignAppleFrameworkForXcode`，
不需要先手动构建 framework。

## 三个实际踩过的坑（都写在 project.yml 的注释里）

1. **必须显式 `-lsqlite3`**，否则链接失败在 `_sqlite3_step` 未定义。
   SQLDelight 的 native driver 走 SQLiter，它的 cinterop 不会替消费方链接。
   **注意：iOS 单元测试通过并不能证明 App 能链接** —— Kotlin/Native 链接测试可执行文件时
   会继承 cinterop 的 linker opts，但静态 framework 交给 Xcode 后那些 opts 不传递。

2. **`Info.plist` 必须有 `CADisableMinimumFrameDurationOnPhone`**，否则 App 一启动就崩：
   Compose Multiplatform 的 `PlistSanityCheck` 会主动抛 `IllegalStateException`。

3. **`NSFaceIDUsageDescription`** 必须有，否则首次调用 Face ID 直接崩溃（应用锁还没做，但先放好）。

## 排查 iOS 启动崩溃：一定要用 `--console`

上面第 2 条那个崩溃**没有崩溃报告、系统日志里也查不到**（异常发生在 dispatch queue 上），
`simctl launch` 只会返回一个 PID 然后 App 静静消失。唯一能看到 Kotlin 异常和堆栈的方式是：

```bash
xcrun simctl launch --console "$UDID" com.boomsset
```

**排查 iOS 启动问题从这条命令开始，不要从崩溃报告开始。**

## 已验证 / 未验证

**已验证（Xcode 26.6 / iOS Simulator 26.5）**：App 能构建、安装、启动；
共享 Compose UI 正常渲染（三个 tab、空状态、FAB）；Koin 启动成功；
SQLDelight 的 NativeSqliteDriver 在真实 App 里创建并 seed 了数据库
（8 张表、25 个内置品种、3 套目标配置，「平衡」生效）。

**未验证**：iOS 上的交互流程（添加/更新/归档资产）—— 没有 iOS 的 UI 自动化，
Android 侧那些流程是实机点过的。真机（非模拟器）也没跑过，需要签名配置。
