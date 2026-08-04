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
        XCTAssertTrue(app.buttons["＋"].exists, "空状态下加号必须可见")
        // 三个 tab 都在
        XCTAssertTrue(app.buttons["净值"].exists)
        XCTAssertTrue(app.buttons["配置"].exists)
        XCTAssertTrue(app.buttons["资产"].exists)
    }

    func testTabsSwitch() throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")

        app.buttons["配置"].tap()
        // 配置页在零资产时也显示标题和目标比例，不再是一行"去添加资产"
        waitFor(app.staticTexts["资产配置"], "配置页")

        app.buttons["资产"].tap()
        waitFor(app.staticTexts["没有在持资产。去「净值」页点加号添加。"], "资产页空态")

        app.buttons["净值"].tap()
        waitFor(app.staticTexts["还没有资产"], "回到净值页")
    }

    /// 完整的添加资产流程 —— 对应 Android 上验过的那条
    func testAddManualAssetComputesNetWorthAndPnL() throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")
        app.buttons["＋"].tap()

        waitFor(app.staticTexts["添加资产"], "添加资产页")
        pickSubtype("现金")

        // 名称已由品种预填（"现金"），不用手输 —— 这正是新流程要省掉的步骤
        type("field-asset-amount", "100000")
        type("field-asset-cost", "95000")

        app.buttons["添加"].tap()

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
        // 走**可见按钮**这条路，不是点整张卡片 —— 见 testUpdatingValueIsAVisibleAction
        app.buttons["更新估值"].firstMatch.tap()

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
        // 编辑比例和新建都要在
        XCTAssertTrue(
            app.buttons["编辑比例"].exists || app.staticTexts["编辑比例"].exists,
            "零资产时必须能编辑比例"
        )
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

        // 编辑对话框真的打得开，不只是按钮存在
        app.buttons["编辑比例"].tap()
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

    /// 回归测试：**更新估值必须有看得见的按钮。**
    ///
    /// 之前它只有隐形入口（整张卡片可点）。用户想把支付宝从 10 万改成 12 万，
    /// 看到的唯一两个可点的东西是「编辑信息」和「归档」，自然点前者 ——
    /// 但那个对话框**根本没有金额字段**，于是合理地得出"改不了资产"的结论。
    /// 这是实际使用反馈出来的。
    ///
    /// App 最核心的动作不能只有隐形入口。
    func testUpdatingValueIsAVisibleAction() throws {
        try addCashAsset(value: "100000", cost: nil)
        app.buttons["资产"].tap()
        waitFor(app.staticTexts["现金"], "资产列表里的现金")

        // 按钮必须存在且写明它是干什么的
        let update = app.buttons["更新估值"].firstMatch
        XCTAssertTrue(
            update.exists,
            "每一行都要有看得见的「更新估值」按钮，实际树：\n\(app.debugDescription)"
        )
        // 而且点了真的能改金额
        update.tap()
        waitFor(app.staticTexts["更新「现金」"], "更新对话框")
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

    private func addCashAsset(value: String, cost: String?) throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")
        app.buttons["＋"].tap()
        waitFor(app.staticTexts["添加资产"], "添加资产页")
        pickSubtype("现金")

        type("field-asset-amount", value)
        if let cost { type("field-asset-cost", cost) }

        app.buttons["添加"].tap()
    }
}
