import XCTest

/// iOS 上的交互流程验证。
///
/// 为什么需要这个：项目里五个「实跑才发现」的 bug 全是在 Android 上点出来的。
/// 大部分在共享层，所以 iOS 也一并修好了 —— 但 iOS 特有的行为
/// （Compose 在 Skia canvas 上的输入、键盘、无障碍映射）从来没有人验过。
///
/// 前提已探明：CMP 的 semantics 会映射到 UIAccessibility，所以 XCUITest 能定位到
/// Compose 画出来的控件（见 AccessibilityProbeTest 的输出）。
final class AssetFlowUITest: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        // 每个测试从干净状态开始 —— 上一个测试建的资产不能影响下一个
        app.launchArguments = ["-uitest-reset"]
        app.launch()
        _ = app.wait(for: .runningForeground, timeout: 15)
    }

    /// 等元素出现，失败时把当前树打出来 —— 否则「找不到元素」这条报错毫无线索
    private func waitFor(
        _ element: XCUIElement,
        _ label: String,
        timeout: TimeInterval = 10
    ) {
        if !element.waitForExistence(timeout: timeout) {
            print("=== 找不到「\(label)」，当前无障碍树 ===")
            print(app.debugDescription)
            XCTFail("找不到「\(label)」")
        }
    }

    func testEmptyStateShowsOnboarding() throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")
        // 三个 tab 都在
        XCTAssertTrue(app.buttons["净值"].exists)
        XCTAssertTrue(app.buttons["配置"].exists)
        XCTAssertTrue(app.buttons["资产"].exists)

        // 加号在"资产"页，不在"净值"页 —— 添加资产是资产页在做的事，
        // 放在净值页（一个只读的趋势概览）会让用户在错的地方找入口（实机反馈）。
        app.buttons["资产"].tap()
        XCTAssertTrue(app.buttons["＋"].exists, "资产页空状态下加号必须可见")
    }

    func testTabsSwitch() throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")

        app.buttons["配置"].tap()
        // 配置页在零资产时也显示标题和目标比例，不再是一行"去添加资产"
        waitFor(app.staticTexts["资产配置"], "配置页")

        app.buttons["资产"].tap()
        // 加号已经就在这一页了，不用再指去"净值"页
        waitFor(app.staticTexts["没有在持资产。点右下角加号添加。"], "资产页空态")

        app.buttons["净值"].tap()
        waitFor(app.staticTexts["还没有资产"], "回到净值页")
    }

    /// 完整的添加资产流程 —— 对应 Android 上验过的那条
    func testAddManualAssetComputesNetWorthAndPnL() throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")
        app.buttons["资产"].tap()
        app.buttons["＋"].tap()

        waitFor(app.staticTexts["添加资产"], "添加资产页")
        pickSubtype("现金")

        // 名称已由品种预填（"现金"），不用手输 —— 这正是新流程要省掉的步骤
        type("field-asset-amount", "100000")
        type("field-asset-cost", "95000")

        app.buttons["添加"].tap()

        // 添加完从"资产"页弹回来（加号现在就在这一页），净值和盈亏要去"净值"页看
        app.buttons["净值"].tap()

        // 净值和盈亏都要对：100000 - 95000 = 5000，5000/95000 = 5.26%
        waitFor(app.staticTexts["¥100,000.00"], "净值 ¥100,000.00")
        XCTAssertTrue(
            app.staticTexts["浮动盈亏 ¥5,000.00（+5.26%）"].exists,
            "盈亏应为 ¥5,000.00（+5.26%）"
        )
    }

    /// 更新估值 —— 重点是预填格式能被自己的解析器读回（Android 上这里坏过）
    func testUpdateValuePrefillIsParseable() throws {
        try addCashAsset(value: "100000", cost: "95000")

        app.buttons["资产"].tap()
        waitFor(app.staticTexts["现金"], "资产列表里的现金")
        // 更新估值现在有两条路：左滑露出「更新」按钮，或者直接点整行卡片。
        // 这个测试关心的是预填格式，不是"怎么进入对话框"，所以走**最稳的那条路**——
        // 直接点卡片。左滑手势本身的验证见 testUpdatingValueIsAVisibleAction 的注释：
        // XCUITest 在这台模拟器上验不出 `SwipeToDismissBox` 的拖拽手势，不代表功能没做对。
        app.staticTexts["现金"].tap()

        waitFor(app.staticTexts["更新「现金」"], "更新对话框")

        // 预填必须是 100000.00（不带千分位）—— 带逗号的话保存会永久禁用
        // TextView 的 value 就是当前文本内容
        let allText = app.debugDescription
        XCTAssertTrue(
            allText.contains("100000.00"),
            "市值预填应为不带千分位的 100000.00，实际树：\n\(allText)"
        )
        XCTAssertTrue(
            allText.contains("95000.00"),
            "成本应从上一条结转，预填 95000.00"
        )
        XCTAssertFalse(
            allText.contains("100,000.00"),
            "预填绝不能带千分位 —— 解析器拒绝逗号，保存会永久禁用"
        )

        // 保存按钮必须是可用的 —— 这正是 Android 上坏掉的地方
        let save = app.buttons["保存"]
        waitFor(save, "保存按钮")
        XCTAssertTrue(save.isEnabled, "预填值必须能被解析，否则保存永久禁用")
    }

    func testAllocationSharesCloseAt100Percent() throws {
        try addCashAsset(value: "100000", cost: nil)

        app.buttons["配置"].tap()
        waitFor(app.staticTexts["资产配置"], "配置页")

        // 只有一项流动资金 → 该类 100%
        XCTAssertTrue(app.staticTexts["100.00%"].exists, "流动资金应占 100%")
        XCTAssertTrue(app.staticTexts["净资产 ¥100,000.00"].exists)
        // 内置预设可切换
        XCTAssertTrue(app.staticTexts["平衡"].exists || app.buttons["平衡"].exists)
    }

    /// 回归测试：**零资产时目标配置的入口必须够得到。**
    ///
    /// 曾经够不到 —— `AllocationScreen` 在 `state.isEmpty` 分支只渲染一行
    /// "还没有资产，先去「净值」页添加"，而切换/编辑/新建目标配置的唯一入口
    /// `AllocationPicker` 写在 `else` 分支里，整块被跳过。新用户于是根本设不了目标配置，
    /// 而那恰恰是录第一笔资产**之前**就想做的事。
    ///
    /// 和「全部归档后取消不了归档」是同一类 bug（空状态走了一条不含入口的分支），
    /// 第三次。数据层一直是对的，所以**只有 UI 测试能抓到它** ——
    /// `AllocationUiStateTest` 只能证明数据在。
    func testAllocationTargetsReachableWithNoAssets() throws {
        waitFor(app.staticTexts["还没有资产"], "净值页空状态")

        app.buttons["配置"].tap()
        waitFor(app.staticTexts["资产配置"], "配置页标题")

        // 三套内置预设都能切
        for preset in ["稳健", "平衡", "激进"] {
            XCTAssertTrue(
                app.staticTexts[preset].exists || app.buttons[preset].exists,
                "零资产时预设「\(preset)」必须可选，实际树：\n\(app.debugDescription)"
            )
        }
        XCTAssertTrue(
            app.staticTexts["＋ 新建"].exists || app.buttons["＋ 新建"].exists,
            "零资产时必须能新建配置"
        )

        // 目标比例本身要显示出来 —— 这一页在没有数据时也该是有用的。
        // 「平衡」的权益类目标是 40%
        XCTAssertTrue(
            app.staticTexts["目标 40%"].exists,
            "零资产时应显示目标比例，实际树：\n\(app.debugDescription)"
        )

        // 编辑比例/恢复默认不再常驻，长按当前目标（"平衡"）才展开 —— 常驻按钮占地方，
        // 实机反馈要求收起来。零资产时也要能长按到，因为设目标比例恰恰是加第一笔
        // 资产之前就想做的事。
        let activeTarget = app.buttons["平衡"].exists ? app.buttons["平衡"] : app.staticTexts["平衡"]
        XCTAssertTrue(activeTarget.exists, "当前目标「平衡」应可见，实际树：\n\(app.debugDescription)")
        activeTarget.press(forDuration: 1.0)
        let editButton = app.buttons["编辑比例"]
        XCTAssertTrue(
            editButton.waitForExistence(timeout: 5),
            "长按当前目标后应展开「编辑比例」，实际树：\n\(app.debugDescription)"
        )

        // 编辑对话框真的打得开，不只是按钮存在
        editButton.tap()
        XCTAssertTrue(
            app.staticTexts["编辑「平衡」"].waitForExistence(timeout: 5),
            "点「编辑比例」应打开编辑对话框，实际树：\n\(app.debugDescription)"
        )
    }

    /// 添加资产是**独立页面**，第一步按品种选、大类自动带出，币种是下拉。
    ///
    /// 三条都是实际使用后的反馈：对话框太窄放不下这个表单；币种用一排 chip
    /// 占掉表单最显眼的一块而它几乎从不改；以及**用户不知道自己要加的东西属于哪个大类**，
    /// 所以不该让他先选大类。
    func testAddAssetIsAFullPagePickingBySubtype() throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")
        app.buttons["资产"].tap()
        app.buttons["＋"].tap()
        waitFor(app.staticTexts["添加资产"], "添加资产页")

        // 独立页面：底部 tab 栏在录入期间必须收起，误点会丢掉已填内容
        XCTAssertFalse(app.buttons["配置"].exists, "添加页不该还显示底部 tab")
        XCTAssertTrue(app.buttons["取消"].exists, "独立页面必须有退路")

        // 第一步是品种，不是大类。用户认得的名字要直接可选。
        // 支付宝和微信钱包在第一屏（流动资金排在最前，因为最常记）
        for subtype in ["支付宝", "微信钱包"] {
            XCTAssertTrue(
                app.buttons[subtype].exists || app.staticTexts[subtype].exists,
                "品种「\(subtype)」应在第一屏可选，实际树：\n\(app.debugDescription)"
            )
        }
        pickSubtype("支付宝")

        // 大类是**结果**而不是提问 —— 选完直接告诉用户它归到哪
        XCTAssertTrue(
            app.staticTexts["归入流动资金"].exists,
            "应显示品种带出来的大类，实际树：\n\(app.debugDescription)"
        )
        // 名称已预填品种名
        XCTAssertTrue(
            app.debugDescription.contains("支付宝"),
            "名称应预填品种名"
        )
        // 币种是下拉，不是一排 chip
        XCTAssertTrue(
            textView("field-asset-currency").exists,
            "币种应为下拉框，实际树：\n\(app.debugDescription)"
        )
    }

    /// 选了负债类品种，负债开关应当自动打开 —— 不用用户再想一遍"房贷是负债"
    func testLiabilitySubtypePresetsTheLiabilityFlag() throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")
        app.buttons["资产"].tap()
        app.buttons["＋"].tap()
        waitFor(app.staticTexts["添加资产"], "添加资产页")

        // 负债自成一组、排在列表末尾 —— 滚下去应当找到
        XCTAssertNotNil(scrollUntilVisible("负债"), "负债应当单独成组")
        pickSubtype("房贷")

        XCTAssertTrue(
            app.staticTexts["负债 · 从另类实物抵扣"].exists,
            "房贷应预设为负债并说明抵扣哪一类，实际树：\n\(app.debugDescription)"
        )
        // 负债没有"成本"和"估值方式"的概念，这两块要收起来
        XCTAssertFalse(app.staticTexts["怎么估值"].exists, "负债不该显示估值方式")
    }

    /// 回归测试：**更新估值不能只有隐形入口。**
    ///
    /// 最早它真的只有隐形入口（整张卡片可点，没有任何按钮）。用户想把支付宝从
    /// 10 万改成 12 万，看到的唯一两个可点的东西是「编辑信息」和「归档」，
    /// 自然点前者 —— 但那个对话框**根本没有金额字段**，于是合理地得出
    /// "改不了资产"的结论。这是实际使用反馈出来的。
    ///
    /// 后来加了常驻的「更新估值」按钮修好了这个问题；这一轮反馈又要求把它收进
    /// 左滑手势（常驻按钮占用了快一半的卡片高度）。**这条教训不能因此被绕开**：
    /// 卡片本身仍然整张可点、直接打开更新对话框，"更新"是**不需要发现手势**
    /// 就能触达的核心动作；左滑露出的按钮是给知道手势的用户的快捷方式，不是唯一入口。
    ///
    /// **左滑本身没有自动化断言 —— 不是没做，是这台工具链验不出来。**
    /// 依次试过 `swipeLeft()`、坐标级 `press(forDuration:thenDragTo:)`、
    /// 带显式速度的重载，三种手势在这台模拟器上都无法让 `SwipeToDismissBox` 的
    /// `anchoredDraggable` 识别成一次拖拽（每次之后的无障碍树里背后的「更新/编辑/归档」
    /// 仍是未展开状态）。但这个手势本身的识别逻辑是**纯共享 Kotlin 代码**，iOS 和 Android
    /// 走的是同一份 `anchoredDraggable`，唯一的平台差异只在"触摸事件怎么送进来"这一层——
    /// 在 Android 上已经用更接近真实连续触摸的 `adb shell input draganddrop`（而不是
    /// 更粗糙的 `input swipe`）手动验证过整条链路：左滑露出按钮、点「更新」能打开
    /// 对话框。这里的结论是"XCUITest 合成手势的力度在这台模拟器上不够"，不是
    /// "这个功能在 iOS 上没做对"——但**这确实是自动化覆盖的一个缺口**，改这块代码时
    /// 除了跑这条测试，还应该在真机或模拟器上手动划一下确认。
    func testUpdatingValueIsAVisibleAction() throws {
        try addCashAsset(value: "100000", cost: nil)
        app.buttons["资产"].tap()
        waitFor(app.staticTexts["现金"], "资产列表里的现金")

        // 不需要先发现左滑手势 —— 直接点整行就能打开更新对话框
        app.staticTexts["现金"].tap()
        waitFor(app.staticTexts["更新「现金」"], "点整行应直接打开更新对话框")
        let field = textView("field-update-amount")
        waitFor(field, "市值输入框")
        XCTAssertTrue(field.isEnabled, "更新对话框里的市值必须可改")
    }

    /// 净值页的空状态要给出上手指引，而不只是"点加号"
    func testEmptyStateExplainsHowTheAppWorks() throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")

        // 关键概念：记快照不记流水。不说清楚，用户会按记账 App 的预期去用
        XCTAssertTrue(
            app.staticTexts.containing(
                NSPredicate(format: "label CONTAINS %@", "不记流水")
            ).firstMatch.exists,
            "空状态必须说明这个 App 记快照而不是记流水"
        )
        // 三步指引都在
        for step in ["1", "2", "3"] {
            XCTAssertTrue(app.staticTexts[step].exists, "缺第 \(step) 步指引")
        }
    }

    // MARK: - 净值页图表的三个控件

    /// 周期是**下拉**，不是一排常驻 chip；三个都能选，当前项在菜单里有勾。
    ///
    /// 顺带守住 AGENTS.md 教训 14 那次真实闪退：**从按月切到按季/按年会崩**。
    /// 原因是图表模型和 UI 状态必然差一帧 —— 切换的那一帧 composition 已经拿到点数更少的
    /// 新序列，Vico 手里还是旧模型，formatter 被问到越界的 x 就返回空串，
    /// 而 Vico 对每个轴标签都 `check(isNotBlank())`。**只有切到更粗的粒度才会崩**
    /// （点数变少才取得到 null），所以这里必须按「月 → 季 → 年」这个方向走一遍。
    func testChartPeriodIsADropdown() throws {
        try addCashAsset(value: "100000", cost: nil)
        app.buttons["净值"].tap()

        // 常驻的三个 chip 应该已经不在了 —— 收进下拉正是为了把那一行还给图表
        waitFor(periodButton("按月"), "周期下拉按钮")
        XCTAssertFalse(
            app.buttons["按季"].exists,
            "周期应收进下拉，不该有常驻的「按季」chip，实际树：\n\(app.debugDescription)"
        )

        // 按月 → 按季 → 按年，逐级变粗，每一步都不能崩
        for next in ["按季", "按年"] {
            let previous = currentPeriodLabel()
            let current = periodButton(previous)
            waitFor(current, "周期下拉按钮")
            current.tap()

            // 菜单会盖住按钮自己，所以当前项必须在菜单里另有标记（打勾）——
            // 只靠"按钮上写着按月"是不够的，那块正被菜单盖着
            XCTAssertTrue(
                app.staticTexts["✓"].waitForExistence(timeout: 5),
                "菜单里应给当前的「\(previous)」打勾，实际树：\n\(app.debugDescription)"
            )

            guard let item = menuItem(next) else {
                XCTFail("下拉里找不到「\(next)」，实际树：\n\(app.debugDescription)")
                return
            }
            item.tap()

            XCTAssertTrue(
                periodButton(next).waitForExistence(timeout: 5),
                "选完「\(next)」按钮文字应跟着变，实际树：\n\(app.debugDescription)"
            )
            // 切粗粒度是那次闪退的方向，确认 App 还活着
            XCTAssertEqual(app.state, .runningForeground, "切到「\(next)」后 App 不该退出")
        }
    }

    /// 两个开关（按大类 / 趋势图）四种组合都切得动，且每种都给出**可见的**内容。
    ///
    /// "可见的内容"是这条测试的重点，不是"没崩"。AGENTS.md 教训 10 就是这么来的：
    /// 只有一个取样点时折线画不出线段，图表区域里只剩坐标轴 —— 数据全对、单测全绿、
    /// 用户看到一片空白。这里刚加完一笔资产**正好只有一个点**，所以趋势图必须
    /// 显式说明"点不够"，而不是给一张空图。
    func testChartModeAndStyleSwitchesStayUsable() throws {
        try addCashAsset(value: "100000", cost: nil)
        app.buttons["净值"].tap()
        waitFor(periodButton("按月"), "周期下拉按钮")

        // ① 总资产 + 柱状图（默认）：一个点也要画得出柱子（幽灵系列，教训 13）。
        //    图表是 Skia 画的、进不了无障碍树，所以这里只能反过来断言
        //    "没有走到那两条说明分支"，也就是图确实画了。
        XCTAssertFalse(trendTooShortNote.exists, "默认是柱状图，不该提示点数不够")

        // ② 按大类 + 柱状图：图例出现，五个大类都有名字
        //    （浅色模式下几类颜色低于 3:1，名字是补偿手段，不能只有色块）
        toggle("按大类")
        for name in ["流动资金", "固定收益", "权益类", "另类实物", "保障类"] {
            XCTAssertTrue(
                app.staticTexts[name].waitForExistence(timeout: 5),
                "按大类时图例应列出「\(name)」，实际树：\n\(app.debugDescription)"
            )
        }
        XCTAssertTrue(
            app.staticTexts.containing(
                NSPredicate(format: "label CONTAINS %@", "净敞口")
            ).firstMatch.exists,
            "按大类时必须说明这张图的口径是净敞口，否则合计和上面的净值对不上会被当成算错"
        )

        // ③ 按大类 + 趋势图：只有一个点，必须**明说**画不了，而不是给一张空图
        toggle("趋势图")
        XCTAssertTrue(
            trendTooShortNote.waitForExistence(timeout: 5),
            "只有一个取样点时趋势图应说明原因，实际树：\n\(app.debugDescription)"
        )

        // ④ 总资产 + 趋势图：同样只有一个点，说明照旧
        toggle("按大类")
        XCTAssertTrue(
            trendTooShortNote.waitForExistence(timeout: 5),
            "总资产趋势图在一个点时也该说明原因，实际树：\n\(app.debugDescription)"
        )

        // 切回柱状图，说明收起、图重新画出来
        toggle("趋势图")
        XCTAssertFalse(
            trendTooShortNote.waitForExistence(timeout: 2),
            "切回柱状图后不该还留着「点数不够」的说明"
        )
        XCTAssertEqual(app.state, .runningForeground, "四种组合切完 App 不该退出")
    }

    /// 应用锁在 iOS 上的能力判断 —— 模拟器默认没录入生物识别
    func testAppLockReportsCapabilityHonestly() throws {
        waitFor(app.staticTexts["应用锁"], "应用锁开关")

        // 模拟器上没录 Face ID 也没设密码 → 应当告诉用户去系统设置加，
        // 而不是含糊地说"不可用"
        let notEnrolled = app.staticTexts["这台设备还没设锁屏密码或生物识别 —— 去系统设置里加上就能用了。"]
        let available = app.staticTexts["开启后每次打开旺资都需要验证身份。开启时会先验一次。"]
        XCTAssertTrue(
            notEnrolled.exists || available.exists,
            "应用锁必须给出明确的状态说明，而不是空白"
        )
    }

    // MARK: - 辅助

    /// 两件探测出来的事实：
    ///
    /// 1. **Compose 的 OutlinedTextField 在 iOS 无障碍树里是 `TextView`，不是 `TextField`。**
    ///    `app.textFields` 一个都找不到。
    /// 2. **不能靠 OutlinedTextField 的 `label` 定位** —— 它只在部分状态下映射成无障碍
    ///    label，聚焦后就消失了（连读屏用户都会听到空白）。所以共享层给这些输入框
    ///    加了显式的 `contentDescription`。
    /// 3. **Compose 把 contentDescription 和可见 label 拼接**成一个无障碍 label，
    ///    所以要前缀匹配，不能精确匹配。见 [textView]。
    /// 输入并**收起键盘**。
    ///
    /// 不收键盘的话，下一个字段可能落在键盘下面，`tap()` 打到的是键盘 ——
    /// 报错是 "Neither element nor any descendant has keyboard focus"，
    /// 看起来像找不到元素，其实是点错了地方。
    /// 换行会触发 singleLine 字段的 ImeAction.Done，从而清掉焦点。
    private func type(_ fieldId: String, _ text: String) {
        let field = textView(fieldId)
        waitFor(field, "输入框「\(fieldId)」")
        // 元素可能在键盘下面，先滚进可见区域
        if !field.isHittable {
            app.swipeUp()
        }
        field.tap()
        field.typeText(text + "\n")
    }

    /// 按 contentDescription 前缀匹配。
    ///
    /// **必须用前缀而不是精确匹配** —— Compose 把 `contentDescription` 和输入框的
    /// 可见 label **拼接**成一个无障碍 label：`'field-asset-name, 名称，如「招行活期」'`。
    /// 用 `app.textViews["field-asset-name"]` 一个都匹配不到。
    private func textView(_ idPrefix: String) -> XCUIElement {
        app.textViews
            .matching(NSPredicate(format: "label BEGINSWITH %@", idPrefix))
            .firstMatch
    }

    /// 往下滚直到某个标签可见可点。
    ///
    /// **品种列表比一屏长，所以必须滚。** 无障碍树只报**可见区域内**的节点 ——
    /// 屏幕外的 chip 用 `exists` 判断就是 false，报错看着像"元素不存在"，
    /// 其实只是还没滚到。Android 的 uiautomator 同理（实测：负债那一组要滚一屏才出现）。
    private func scrollUntilVisible(_ label: String, maxSwipes: Int = 8) -> XCUIElement? {
        let window = app.windows.firstMatch
        for _ in 0...maxSwipes {
            for candidate in [app.buttons[label], app.staticTexts[label]] {
                guard candidate.exists && candidate.isHittable else { continue }
                // **只判 isHittable 不够。** 卡在屏幕边缘的元素 isHittable 仍是 true，
                // 但 tap 打的是它的中心点，而那个点在可视区之外 —— 于是"点了没反应"，
                // 比"找不到元素"难查得多（实测：滚到负债组后点房贷一直不进详情页）。
                // 要求元素**整个**落在窗口内，上边再留出 TopAppBar 的高度。
                let f = candidate.frame
                if f.minY > window.frame.minY + 96 && f.maxY < window.frame.maxY - 24 {
                    return candidate
                }
            }
            app.swipeUp()
            // 惯性滚动没停时元素还在移动，立刻 tap 也会打偏。等它停下来。
            Thread.sleep(forTimeInterval: 0.5)
        }
        return nil
    }


    /// 选品种。**新流程的第一步** —— 大类由品种带出，用户不用判断"现金算哪一类"。
    private func pickSubtype(_ name: String) {
        guard let chip = scrollUntilVisible(name) else {
            print(app.debugDescription)
            XCTFail("滚遍整页也找不到品种「\(name)」")
            return
        }
        chip.tap()
        // 选完进入详情表单，标志是那张"已选品种"卡片上的「换一个」
        waitFor(app.buttons["换一个"], "详情表单")
    }

    // MARK: - 图表控件的辅助

    /// 只有一个取样点时趋势图给出的说明。见 [testChartModeAndStyleSwitchesStayUsable]。
    private var trendTooShortNote: XCUIElement {
        app.staticTexts.containing(
            NSPredicate(format: "label CONTAINS %@", "两个以上的取样点")
        ).firstMatch
    }

    /// 周期下拉的按钮。
    ///
    /// **必须前缀匹配**：按钮上写的是「按月 ▾」（带那个下拉三角），
    /// `app.buttons["按月"]` 精确匹配一个都找不到。而下拉**菜单项**没有三角，
    /// 所以精确匹配那几个名字命中的一定是菜单项、不会误伤按钮 ——
    /// `testChartPeriodIsADropdown` 里"不该有常驻 chip"那条断言正是靠这个区分。
    private func periodButton(_ label: String) -> XCUIElement {
        app.buttons
            .matching(NSPredicate(format: "label BEGINSWITH %@", label))
            .firstMatch
    }

    /// 等下拉菜单里的某一项出现。
    ///
    /// **菜单项的元素类型探不准**（`DropdownMenuItem` 里就是一个 `Text`，可能落成
    /// button 也可能落成 staticText），所以两种都等一等再判断 —— 不能像
    /// `app.buttons[x].exists ? ... : ...` 那样立刻取值：菜单是弹出来的，
    /// 判断的那一刻它可能还没画上去，于是必然落到另一个分支上去空等。
    private func menuItem(_ name: String, timeout: TimeInterval = 5) -> XCUIElement? {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            for candidate in [app.buttons[name], app.staticTexts[name]] where candidate.exists {
                return candidate
            }
            Thread.sleep(forTimeInterval: 0.2)
        } while Date() < deadline
        return nil
    }

    private func currentPeriodLabel() -> String {
        for label in ["按月", "按季", "按年"] where periodButton(label).exists {
            return label
        }
        return "按月"
    }

    /// 点一个带文字标签的开关（「按大类」/「趋势图」）。
    ///
    /// 共享层给这两个 `Switch` 加了显式 `contentDescription`（标签是相邻的兄弟节点，
    /// 不会并进开关自己的无障碍节点）。**元素类型是探不准的** —— Compose 的开关在
    /// iOS 无障碍树里可能落成 switch / button / other，取决于 toggleable 语义怎么映射，
    /// 所以这里逐个类型试，和 [scrollUntilVisible] 同一个思路。
    private func toggle(_ name: String) {
        let predicate = NSPredicate(format: "label BEGINSWITH %@", name)
        for query in [app.switches, app.buttons, app.otherElements] {
            let element = query.matching(predicate).firstMatch
            guard element.exists else { continue }
            element.tap()
            return
        }
        print("=== 找不到开关「\(name)」，当前无障碍树 ===")
        print(app.debugDescription)
        XCTFail("找不到开关「\(name)」")
    }

    private func addCashAsset(value: String, cost: String?) throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")
        app.buttons["资产"].tap()
        app.buttons["＋"].tap()
        waitFor(app.staticTexts["添加资产"], "添加资产页")
        pickSubtype("现金")

        type("field-asset-amount", value)
        if let cost { type("field-asset-cost", cost) }

        app.buttons["添加"].tap()
    }
}
