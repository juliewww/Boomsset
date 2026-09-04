# AGENTS.md — 旺资（Boomsset）

## 项目状态

**核心循环已闭环（Android 实机验证过）。** 三个页面：净值曲线 / 资产配置 / 资产列表。
添加资产 → 定期更新估值 → 归档，全流程可用。**178 个单元测试全绿。**

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
**158 个 iOS 模拟器测试全绿**，其中包含专门验 `NativeSqliteDriver` 的 `NativeDatabaseTest`
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

**品牌色与 app icon 已做（两端实机验证）：** 品牌色**琥珀棕 `#8A5A18`**，
定义在 [Theme.kt](shared/src/commonMain/kotlin/com/boomsset/ui/theme/Theme.kt)，跟随系统深浅色。
图标是「配置环 + 旺」，全部尺寸由 [tools/appicon/generate.py](tools/appicon/generate.py) 生成 ——
**那是唯一事实来源，res/ 和 Assets.xcassets 里的 PNG 是产物，别手改。**

在此之前全局只有一句裸 `MaterialTheme {}`，界面跑的是 Material 3 自带的**默认紫** ——
那不是设计决策，只是没人配过。配 ColorScheme 时**必须逐个角色写全**：没传的参数取基线默认值，
而基线的 surface 家族是带紫调的灰，只改 primary 会让 Card 和 BottomBar 仍然发紫。

**Android 真机验证过（Xiaomi 15 Pro / Android 16 / HyperOS 3）：** 启动无崩溃、
主题与空态正确、状态栏图标对比度 10.20:1；应用锁在**有真实生物识别的设备**上
`capability()` 返回 AVAILABLE（模拟器只有 PIN，没验过这条路径）。
⚠️ **`adb` 命令里的设备参数不要写成一个变量**（`D="-s xxx"` 后 `$D`）——
zsh 不对未加引号的变量做分词，会被当成单个参数，报 `-s requires an argument`。

**空状态已可用（两端实机验证）：** 净值页给出三步上手指引，并说明「记快照不记流水」
（不说清楚，用户会按记账 App 的预期去用）。**配置页零资产时照样能用** ——
显示目标比例本身，预设可切换、比例可编辑。

**净值曲线不再显示"没有数据"的时段、资产配置多了饼图（两端实机验证）：**
按季/按年看时，账号才用了几个月，原来固定取 12 个周期、前面一大截是"资产还不存在"
的 0 值点。现在有一个 `trimBeforeFirstSnapshot` 开关（默认关，不改变已有测试锁定的
结转语义），UI 侧打开后只保留第一条快照之后的取样点。判据是**取样点结束时刻是否早于
最早快照时刻**，不是"净值是不是 0"——账户清零之后的真实 0（比如全部资产归档）
不该被这条规则当成"没数据"抹掉。

同时用 Vico 的 `HorizontalAxis.ItemPlacer.aligned(spacing, offset)` 让 x 轴标签
按点数动态稀疏，不再是"每个点都放一个标签"挤到重叠截断（实测过"10月/11月/12月"
被截断成"10…/11…/12…"）；`offset` 特意算成"让最后一个下标对齐"，保证最新的点
永远在最右边有标签，不会因为点数不是 spacing 的整数倍而漏标。

配置页加了环形占比图（Vico 的 `PieChart`／`PieChartHost`，同一个包早就在用于折线图），
和 [ClassRow](shared/src/commonMain/kotlin/com/boomsset/ui/allocation/AllocationScreen.kt)
的进度条共用 [chartColors](shared/src/commonMain/kotlin/com/boomsset/ui/theme/ChartColors.kt)
那五个固定顺序的大类色 —— 圆环没有另配一份图例文字，名称和百分比下面的卡片已经写了，
再写一遍是重复信息（配置页第一版就因为重复"对比目标"被反馈过，教训直接搬过来）。

⚠️ **查 Vico API 一定要对着 pinned 的 tag 查，不能信 GitHub 默认分支。**
第一次查 `HorizontalAxis.ItemPlacer.aligned()` 的参数时用 WebFetch 抓的是
`master` 分支，得到一个"新版本才有的参数"（`shiftExtremeLabels`），编译报
"找不到参数"。用 `gh api repos/.../git/refs/tags` 按项目锁定的版本号找到对应
commit sha，再用 `gh api repos/.../contents/<path>?ref=<sha>` 查源码，才是这个
项目实际链接的那个 API。Vico 的 pie chart 相关文件路径也是先用
`gh api search/code` 搜出来的真实路径，没有凭经验猜（猜的话大概率猜错，
这个库的目录结构比包名深好几层）。

**品牌色再调亮（两端验证）：** `#8A5A18` → `#BD4D03`，反馈是原色不够"积极向上"。
直接沿旧色相拉高亮度彩度不可行——候选在 sRGB 里会被裁剪，裁剪本身会偷偷改变色相角，
最后撞上图表「权益类」橙 `#E58A26`（配置页 FAB 和权益类那根条会挨在一起）。
改成往红偏约 25° 色相角，用 dataviz 验证器确认分离度 ΔE 16.2（门槛 15）。
细节和求解过程见 [Theme.kt](shared/src/commonMain/kotlin/com/boomsset/ui/theme/Theme.kt) 的注释。
图标同步换成新色，环的四阶台阶按「相对 BRAND 的偏移量」重新算，不是手调。

**表面是中性白灰，不是暖米色（实机验证）：** 「不够高级」的主因不在色相、在底色 ——
暖米色底本身就读作「米黄/复古」，还让所有强调色的对比度变差。
现在表面和文字梯度取自**有知有行**的 design token，品牌琥珀棕是**唯一**的暖色强调。
图标保持暖色不变（图标和 app 表面无关，不用重做）。

**图表配色成体系（两端实机验证，浅深两色）：** 五大类各有颜色，**顺序固定不可重排** ——
顺序本身是色盲安全机制。偏离度用分歧色：超配红、低配蓝、达标中性灰。
三个页面（净值/配置/资产）共用同一套大类色，「蓝色=流动资金」在哪一页都成立。
⚠️ **色值不是手挑的**：色相取自有知有行，但**必须经过 snap-to-passing**（色相角不动、
挪亮度和彩度到合规）—— 他们的原值是给小面积强调用的，当五路分类填色时 gold/pink 亮度超界、
cyan/purple 彩度不足。在 2520 种组合里搜出 588 组通过，取离原色最近的（总偏离仅 ΔE 5.8）。
改色值必须重跑验证器，
方法和命令写在 [ChartColorsTest](shared/src/commonTest/kotlin/com/boomsset/ui/theme/ChartColorsTest.kt) 的注释里。
浅色模式下有几个大类色低于 3:1 的色块对比度，**必须靠"色块旁边永远有名字 + 百分比"补偿** ——
改版式时别把那些标签去掉。

**添加资产是独立页面（两端实机验证）：** 不是对话框 —— 对话框放不下这个表单，
键盘一弹只剩两三行。**第一步选品种、不选大类**：用户不知道支付宝算哪一类，
所以反过来做 —— 选「支付宝」，大类和默认估值方式从内置品种表带出来，
大类只作为结果显示。负债品种（房贷/车贷/信用卡/消费贷）单独成组，选了自动打开负债开关。
币种是**下拉**而不是一排 chip（它几乎从不改，不该占表单最显眼的位置）。

**iOS 交互流程已验证（XCUITest，10 个测试全绿）：** 空状态、tab 切换、添加资产后
净值和盈亏正确、更新弹窗的预填能被解析、配置比例、应用锁能力提示，
以及**零资产时目标配置入口可达**、空状态指引、添加流程按品种选、负债品种预设负债标记。
跑法：`cd iosApp && xcodebuild test -scheme iosApp -destination "id=<UDID>"`。

**根据真机使用反馈做的第二轮 UI 打磨（两端实机/模拟器验证）：**
- 净值曲线改成**柱状图+折线图**组合，不再是纯折线 —— 见下方教训 10；
  只有 1 个点时那根柱子原本会撑满整条 x 轴，用幽灵系列窄化成正常宽度 —— 见教训 13
- **加号（添加资产）从净值页挪到资产页**：净值页是只读的趋势概览，添加资产是资产页在做的事，
  放错页面会让用户在错的地方找入口。空状态提示文案跟着一起改了（不再说"去净值页点加号"）
- 底部导航选中态**文字也要变成品牌色**，不能只靠一个浅灰指示条 —— 那条太不明显，看不出选中了哪个
- 净值页加了一点暖色：概览卡片换成 `primaryContainer` 浅色底，涨跌数字上了色
  （见 [GainLossColors.kt](shared/src/commonMain/kotlin/com/boomsset/ui/GainLossColors.kt)，
  中国股市语境**红涨绿跌**）。这组色是**复用已验证的 M3 角色色而不是新起一组 hex**——
  `docs/domain.md` 里早就预告"给盈亏上色要另开一组常量"，但那需要重跑 ChartColorsTest 注释里
  那套 OKLab 验证器，而那个脚本本身没有提交到仓库、这轮时间也不允许重建，所以选了更保守的路：
  复用已经过审的 primary/error 语义色，而不是引入未经色盲安全验证的新色值
- 配置页：净敞口的金额加了显式 **+/−** 符号（之前只有负数才看得出符号）；
  "对比哪套目标"和内置预设的长段说明文字收进了点开才看的 **(i) 图标 tooltip**；
  "编辑比例/恢复默认/删除"从常驻按钮改成**长按当前目标**才展开
  （[AllocationScreen.kt](shared/src/commonMain/kotlin/com/boomsset/ui/allocation/AllocationScreen.kt)
  的 `InfoTooltip`/`AllocationPicker`）
- 配置页的环形图**扇区可点**，点开显示那一类的名称和金额，再点一次收起 ——
  Vico 的 `PieChart` 没有点击回调，命中检测是手写的角度/半径计算，
  见 [AllocationDonut.kt](shared/src/commonMain/kotlin/com/boomsset/ui/allocation/AllocationDonut.kt)
- 资产页删掉了顶部常驻的"点更新估值记快照"提示；每行原来常驻的"更新估值/改名称分类/归档"
  三个按钮改成**左滑**才露出来，用 material3 自带的 `SwipeToDismissBox`（不是真的 dismiss，
  划开不移除数据，只是露出背后的按钮）。**卡片本身仍然整张可点直接打开更新对话框** ——
  这是刻意保留的，见下方教训 11
- 添加资产第二步表单（按份额取行情时的「持有份额」「总投入成本」）键盘弹出会挡住字段，
  补了 `Modifier.imePadding()` —— 见下方教训 12

**净值页的「查看币种」改成下拉（Android 模拟器 API 34 验证）：** 原来是 9 个 `FilterChip`
排开，加上标签和一行说明，在手机上占三四行（反馈："位置占比太大"）。现在收成一行：
标签 + `OutlinedButton`("CNY ▾") + `DropdownMenu`，说明收进 (i) tooltip。默认仍是 CNY
（`DEFAULT_BASE_CURRENCY`，本来就是）。
**没用 `ExposedDropdownMenuBox`** —— 那套是给文本输入框用的，会带进一个 56dp 高的
`OutlinedTextField`，正好和"省空间"相反；添加资产页的 `CurrencyDropdown` 用它是对的
（那里本来就是表单）。菜单展开会**盖住按钮自己**，所以当前币种在菜单里用 `trailingIcon` 打勾
标出来（不是只换颜色 —— 颜色单独承载状态对色弱用户不成立）。
配置页的 `InfoTooltip` 提到了 [InfoTooltip.kt](shared/src/commonMain/kotlin/com/boomsset/ui/InfoTooltip.kt)
两页共用，同时改成 `isPersistent = true` —— 见下方教训 15。
实测切 USD 折算正确（¥100,000 → $14,883，Frankfurter 实时汇率）、切回 CNY 原值不变。

**还没做的：** 应用锁在 iOS 上的真实认证（模拟器没录入生物识别，只验到了能力提示）；
iOS 18+ 的深色/着色图标变体（现在只提供浅色一张，系统会自动派生）。

**教训（十五次都是实跑才发现、编译和单测全绿）：**
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

6. `AddAssetDialog` 的内容没加 `verticalScroll` → iOS 上键盘一弹，**市值和成本字段
   被裁掉、用户够不到**。Android 模拟器屏幕高，刚好放得下所以一直没暴露。
   **对话框内容默认就该可滚动** —— 键盘会吃掉一半屏幕。

7. 图标的自适应前景按规范缩进了 66/108 安全区，本地按 72dp 视口合成看几乎贴边，
   **但实机上环明显更小** —— Pixel Launcher 对自适应图标还会**再缩一次**
   （Launcher3 的图标归一化，不在 `AdaptiveIconDrawable` 规范里）。
   顶着安全区放大会在别的 OEM 遮罩下被削，所以改为**加粗笔画**来补视觉重量。
   **图标必须装到设备上看，本地合成证明不了它在启动器里的样子。**

8. 配置页零资产时走 `state.isEmpty` 分支只渲染一行「还没有资产，先去净值页添加」，
   而 `AllocationPicker`（切换/编辑/新建目标配置的**唯一**入口）在 `else` 分支里
   → **新用户根本设不了目标配置**。而那恰恰是录第一笔资产*之前*就想做的事。
   **这条通则当时已经写在本文件里了，还是又犯了一次** —— 因为规则的字面只提了
   提前 `return`，这次用的是 `when` 的分支，形式不同、后果一样。

9. 浅色主题下**状态栏是白色图标压在奶白底上**，时间和信号几乎看不见
   （实测 WCAG 对比度 **1.36:1**，修完 10.20:1）。原因：**Android 15（SDK 35）起
   targetSdk ≥ 35 被强制 edge-to-edge**，内容画到状态栏下面，而系统不知道你的背景是浅是深，
   默认给白色图标。修法是 `isAppearanceLightStatusBars = !darkTheme`
   （名字容易读反：Light 指**背景**浅，所以图标画成深色），见
   [SystemBars.android.kt](shared/src/androidMain/kotlin/com/boomsset/ui/theme/SystemBars.android.kt)。

   **手上的 API 34 模拟器抓不到这个** —— 强制 edge-to-edge 之前，系统会画一条不透明状态栏、
   颜色自己就是对的。API 36 那台模拟器本该抓到，但它 `screencap` 返回全黑，
   于是我换到 API 34 去截图 —— **恰好换掉了会暴露这个 bug 的 API 等级**。
   教训：**为了绕开工具问题换设备时，要先确认新设备没有绕掉被测的那个条件。**
   验 edge-to-edge / 系统栏相关的问题必须用 **SDK ≥ 35** 的设备。

10. 净值图表加了 `trimBeforeFirstSnapshot` 之后，第一次记完快照的用户只有一个取样点，
    Vico 的 `LineCartesianLayer` 画不出线段（折线需要 2 个以上的点）——**图表区域里
    只有坐标轴，没有任何可见图形**（实机反馈原话："只有一条虚线"，其实是连虚线都没有，
    看到的是空的网格线）。单测测的是 `NetWorthSeries` 的数据，从没断言过"这个点数下
    Vico 到底画不画得出东西"，所以编译和单测全绿也发现不了。改成柱状图+折线图组合
    （`ColumnCartesianLayer` + `LineCartesianLayer` 叠在同一个 `rememberCartesianChart` 里），
    一个点也能画出一根柱子。**这条通则可以再泛化一次：图表类组件的正确性不能只测数据层，
    "点数很少（1 个、0 个）时图形是否可见"要专门实机确认**，数据正确不等于画得出来。

11. **验证自定义手势（长按弹出、左滑显示操作）不能用测试框架"最方便"的那个 API —— 而且
    "换一个更像真实拖拽的 API"这条思路在 iOS 上最终也没能修好，这是留在这里的一个真实缺口。**
    Compose 的 `SwipeToDismissBox` 用 `anchoredDraggable` 识别拖拽，而 Android 的
    `adb shell input swipe`、iOS XCUITest 的 `XCUIElement.swipeLeft()` 都是"元素范围内、
    固定极短时长"的合成手势，生成的中间移动事件太少/太快，两边都识别不到——**实机上
    手指划一下明明好用，自动化验证却像是没反应**，很容易被误判成"这个功能没做对"。
    Android 换成 `adb shell input draganddrop`（更接近真实连续拖拽）之后**确认手势本身是好的**——
    左滑露出按钮、点「更新」能打开对话框，全程实测通过。
    iOS 这边依样画葫芦换成坐标级的 `XCUICoordinate.press(forDuration:thenDragTo:)`，
    第一次以为修好了（写进过这条教训），但重新完整跑一遍测试套件后发现**其实还是没触发**——
    之前"看起来通过"是没有重新跑验证就写下的结论，一个教训：**改完自动化断言必须真的重新跑一遍
    再记录结果，不能凭"应该好了"就下结论。** 后来又试了带显式速度的重载
    `press(forDuration:thenDragTo:withVelocity:thenHoldForDuration:)`（给一个远低于默认值的慢速度），
    依然没用。三种 XCUITest 手势 API 都没能让这台模拟器上的 `anchoredDraggable` 识别成一次拖拽。
    **结论是把这一小段自动化断言去掉**，改成让相关测试走"直接点卡片"这条已验证稳定的路径
    （见 `testUpdateValuePrefillIsParseable`/`testUpdatingValueIsAVisibleAction`），
    并在代码注释里写清楚"左滑这个具体交互没有自动化覆盖，改动这块要手动在真机/模拟器上划一下"——
    诚实地承认工具链的缺口，比硬凑一个看起来通过、其实没测到东西的断言更负责任。
    支撑"功能本身没问题"这个判断的是架构论证：`SwipeToDismissBox`/`anchoredDraggable`
    是纯共享 Kotlin 代码，iOS 和 Android 手势识别逻辑完全一致，唯一的平台差异只在触摸事件
    怎么送进来那一层——而这一层已经在 Android 上用接近真实连续触摸的方式验证过。
    另外，**`coordinate(withNormalizedOffset:)` 建在一个还没等到出现的元素上时，
    内部重试会挂到 XCTest 的默认超时**（实测卡了 600~950 秒才失败，而不是快速报错）——
    自定义手势的测试助手函数必须先 `waitForExistence` 再取坐标，否则一个"元素暂时不在"
    的小问题会被拖成看起来像"卡死"的大问题，调试成本差一个数量级。

12. **`verticalScroll` 不等于"键盘弹出时能滚到聚焦字段"。** `AssetDetailForm` 早就有
    `verticalScroll`（教训 6 修的是完全没有滚动），但按份额取行情时的「持有份额」
    「总投入成本」两个字段照样被键盘挡住（实机反馈）。原因是**`verticalScroll` 单独
    存在时不知道键盘占了多少高度**——它仍然按"整个屏幕都看得见"来计算可滚动范围，
    聚焦字段的"滚入可视区"逻辑因此判断"已经在可视区内"而不多滚。补 `Modifier.imePadding()`
    让内容区域随键盘高度收缩，滚动容器的可视高度才是真的，才会正确多滚出被键盘吃掉的那截。
    **两个是不同的坑：`verticalScroll` 解决"内容装不下"，`imePadding` 解决"知道键盘多高"，
    键盘相关的表单两个都要有，只查有没有 `verticalScroll` 不够。**

13. **柱状图只有 1 个点时会撑满整条 x 轴，`LineComponent` 的 `thickness` 完全不管用。**
    教训 10 把净值图改成柱状图+折线组合，解决了"1 个点画不出折线"；但只有 1 个点时，
    这根柱子会撑成一整块实心矩形（实机反馈："宽度太宽"）。**先做了个错误验证**：
    以为 `thickness` 控制柱子宽度，改小到 1dp 结果肉眼看不出任何变化——这说明 Vico
    是按"这个 x 位置分到多少可用宽度"来画柱子，只有 1 个 x 位置时可用宽度就是整个绘图区，
    `thickness` 根本不参与这个计算。**验证方法**：把柱子颜色换成一个和折线区域填充明显不同的
    纯色（不透明蓝色），一眼就能确认那块"太宽的实心矩形"确实是柱状图层画的，不是折线的
    区域填充或别的什么东西——排查视觉问题时，用一个夸张到不会混淆的颜色隔离图层，
    比反复读源码猜哪个图层更快。

    第一次尝试用 `CartesianLayerRangeProvider.fixed()` 把 x 轴范围**下限**人为往左扩宽
    （比如只有 1 个点时假装有 6 个位置），让"每个位置的可用宽度"跟点数多时一致。
    这个思路方向没错，但**直接导致崩溃**：`HorizontalAxis` 在测量坐标轴标签宽度时会对
    扩出来的、没有对应真实日期的"虚拟"x 位置也调一次 `valueFormatter`，而这些位置的
    formatter 只能返回空字符串——Vico 不允许这样（`IllegalStateException`，异常信息明确说
    "改用 ItemPlacer，别用空字符串"）。改空字符串为占位文本能避开崩溃，但 `ItemPlacer`
    的 spacing/offset 算法是按"真实点数"设计的，不知道该跳过哪些是扩出来的虚拟位置，
    结果虚拟位置也会被真的选中显示，变成图表左边多出几个指向不存在日期的假标签。
    往回改这条路的成本比预期高很多，及时放弃、换路径。

    真正管用的办法完全不碰 x 轴范围：用 `ColumnCartesianLayer.MergeMode.Grouped` 加几个
    **全 0 值的"幽灵系列"**，让同一个 x 位置的可用宽度被分成好几份——真实数据只占其中一份，
    其余几份值是 0、画出来高度是 0、看不见。这个办法只影响"一个 x 位置内部怎么分宽度"，
    完全不涉及 x 轴范围和坐标轴标签，不会重蹈上面那次崩溃。**只在恰好 1 个点时才加幽灵系列**——
    2 个点以上时，多个真实点自然会分布在整个宽度上，不会出现"一整块"这种一眼看去像
    渲染错误的效果，没必要处理。这条本可以更早想到：解决"1 个 x 位置内部的宽度分配"问题，
    该用"1 个位置内部怎么分"这一层的机制（多系列分组），而不是先跳到"x 轴范围"这个更外层
    的机制——**越靠近问题实际发生的那一层修，副作用越小**。

    这个修法**第一版还有一个后续 bug**，也是装到真机上才看出来：把幽灵系列全部
    追加在真实系列**后面**（`series(values)` 在前，5 个 `series(listOf(0.0))` 在后），
    `Grouped` 是按 `series()` 的调用顺序把子柱从左到右排的，于是真实那根柱子被排到了
    这个 x 位置的**最左边**；但坐标轴标签（"8月"）是按**整个位置的中心**画的——
    结果柱子和它自己的月份标签左右错开，一眼看去像"柱子对错了日期"（实机反馈）。
    修法是把 5 个幽灵系列拆成两组，2 个放真实系列前面、2 个放后面，真实系列夹在
    正中间（5 个系列取中间下标 2），柱子的水平中心才会跟标签的水平中心对齐。
    **教训：`MergeMode.Grouped` 子柱的排列顺序完全由 `series()` 的调用顺序决定，
    "占位系列放哪"不是无所谓的细节，会直接决定真实柱子在这个位置里偏左还是居中。**

14. **净值页从「按月」切到「按季/按年」直接闪退**（实机反馈，模拟器上已复现原始崩溃：
    `IllegalStateException: CartesianValueFormatter.format returned a blank string`，
    栈顶是 `HorizontalAxis.getMaxLabelWidth`）。原因是**图表模型和 UI 状态之间必然差一帧**：
    `CartesianChartModelProducer` 是 `remember {}` 出来的、跨 period 切换一直活着，
    而模型更新是 `LaunchedEffect` 里的 **suspend transaction**（还带过渡动画）。
    切换的那一帧，composition 已经拿到新的 `series`（按季 3 个点），Vico 手里还是旧模型
    （按月 7 个点）—— 原来的 `valueFormatter` 直接闭包捕获 `series.dates`，被问到 x=3..6 时
    `getOrNull` 返回 null、formatter 返回 `""`，而 **Vico 对每个轴标签都 `check(isNotBlank())`**。
    反过来（按季切按月）点数变多、取不到 null，所以**只有切到粗粒度才崩** —— 正好是反馈的现象，
    这个方向性本身就是定位线索。
    `ItemPlacer` 的 spacing/offset 是同一个坑的另一半：`getFirstLabelValue()` 用
    `minX + offset * xStep` 去问 formatter，**这个 x 不做范围裁剪**，旧模型点少、
    新算出的 offset 偏大时一样会问到越界的 x。
    修法是把标签表放进 `ExtraStore`、和数据点在**同一个 transaction** 里落地，
    formatter 从 `context.model.extraStore` 读，spacing/offset 也从 Vico 传进来的
    `model.extraStore` 算（那两个 lambda 的参数就是它）。
    **通则：凡是 formatter / ItemPlacer 需要的东西都必须跟着 model 走，不能从 composition 捕获** ——
    「UI 状态」和「图表模型」是两个独立的时间线，任何跨越它们的隐式依赖都会在切换的那一帧炸。
    单测测不到 Vico 画什么，但能锁住"喂进去的值永远合法"：见 `NetWorthChartAxisTest`
    （标签永不为空白串、spacing>0、offset>=0、最后一个点一定被标到）。

15. **M3 的 tooltip 气泡默认 1.5 秒就自己消失 —— 把说明文字收进 (i) 之前得先知道这件事。**
    净值页的币种说明收进 `InfoTooltip` 后，装到模拟器上**连拍才发现**气泡只活了一瞬：
    `rememberTooltipState()` 默认 `isPersistent = false`，到点自动收（`TooltipDuration` 1500ms）。
    装的是三四行中文，读完要好几秒 —— 等于把文字藏进了一个来不及看的地方。
    改成 `rememberTooltipState(isPersistent = true)`（点别处才收）。
    配置页那几个 tooltip 一直有同样的问题，共用组件之后一起修了。
    ⚠️ **验证方法本身也是个坑**：`adb shell input tap` 之后回主机 `sleep` 再 `exec-out screencap`，
    一次往返就够 1.5 秒了，抓到的永远是气泡消失后的画面 —— 我因此**先误判成"tooltip 根本不显示"**。
    正确做法是把点击和连拍放进**同一条设备端命令**里：
    `adb shell 'input tap X Y; for i in 1 2 3 4; do screencap -p /sdcard/tt_$i.png; done'`，
    第一张（约 0.3s）就抓到了。**通则：验证「短暂出现」的 UI 不能用主机端 tap→sleep→screencap，
    往返延迟比被测现象还长；要么在设备端连拍，要么先让它别自动消失。**
    另外这次踩到一个环境问题：API 35 的 `google_apis_playstore` 模拟器上 `install` 报 Success、
    `dumpsys package` 里 resolver table 明明有 MainActivity，但 `am start` 一直报
    `Activity class does not exist`（重装、重启模拟器都没用）；换 `google_apis`（无 Play 商店）
    的 AVD 就正常。按教训 9 那条：**换设备绕工具问题之前先确认新设备没绕掉被测条件** ——
    这次测的是布局，和 SDK 等级无关，所以换到 API 34 可以；但如果测的是系统栏/edge-to-edge，
    就必须留在 SDK ≥ 35。

**另一条通则（第 1、5、8 条都是它）：空状态不能走一条不包含入口的渲染分支。**
**不要只盯着提前 `return`** —— `when`/`if` 分支、早退的 `LazyColumn` item，任何
「空态和有数据走不同路径」的写法都会犯。可操作的检查：**把这一页所有入口列出来
（已归档、设置、帮助、目标配置、应用锁…），逐个确认空态下它还在。**
入口最好干脆放在分支之外，只让「主体内容」分支化。

而且**这类 bug 只有 UI 测试能抓到**：数据层一直是对的（预设来自
`observeAllocations()`，和持仓无关），state 层测试只能证明数据在。
所以每修一次都要配一条 XCUITest，并且**先撤掉修复确认它真的会失败**。

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
| 配色 | **中性表面**（`#FFFFFF`/`#FAFAFA`/`#F5F5F5`，文字纯灰）+ 品牌琥珀棕 `#8A5A18` 作唯一暖色强调。表面梯度取自有知有行 |
| 图表配色 | 大类=分类色（固定顺序）、偏离度=分歧色，见 [ChartColors.kt](shared/src/commonMain/kotlin/com/boomsset/ui/theme/ChartColors.kt)。**色值是验证过的，改了要重跑验证器** |

## 项目结构

```
shared/          KMP library，绝大部分代码在这
  src/commonMain/   领域模型、数据层、ViewModel、Compose UI —— 默认都写这里
  src/androidMain/  仅 Android 平台实现（SQLDelight driver、Keystore…）
  src/iosMain/      仅 iOS 平台实现（native driver、Keychain…）
  src/commonTest/   共享测试
androidApp/      Android 应用入口（com.android.application）
iosApp/          Xcode 工程
tools/appicon/   app icon 生成器（PNG 都是产物，改设计改这里）
docs/            详细文档，按需查阅
```

**为什么 `androidApp` 是独立模块：** AGP 9 不再允许在 KMP 模块里应用 application 插件。
`shared` 用的是 `com.android.kotlin.multiplatform.library`，**不是** `com.android.library`。
这不是风格选择，是硬性要求。细节见 docs/stack.md。

**默认写 commonMain。** 只有真正调用平台 API 时才落到 androidMain/iosMain，通过 `expect/actual` 暴露。

## 构建与验证

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64   # iOS 编译，改完共享代码先跑这个（不需要 Xcode）
./gradlew :shared:testAndroidHostTest              # 共享代码的单元测试（跑在 JVM 上，178 个）
./gradlew :shared:iosSimulatorArm64Test            # iOS 模拟器测试（158 个，需要 Xcode）
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

**JVM 和 iOS 的测试数不一样（178 vs 158），这是对的**：
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
