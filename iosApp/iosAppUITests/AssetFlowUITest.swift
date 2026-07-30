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
        waitFor(app.staticTexts["还没有资产，先去「净值」页添加。"], "配置页空态")

        app.buttons["资产"].tap()
        waitFor(app.staticTexts["没有在持资产。去「净值」页点加号添加。"], "资产页空态")

        app.buttons["净值"].tap()
        waitFor(app.staticTexts["还没有资产"], "回到净值页")
    }

    /// 完整的添加资产流程 —— 对应 Android 上验过的那条
    func testAddManualAssetComputesNetWorthAndPnL() throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")
        app.buttons["＋"].tap()

        waitFor(app.staticTexts["添加资产"], "添加对话框")

        type("field-asset-name", "Cash")
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
        waitFor(app.staticTexts["Cash"], "资产列表里的 Cash")
        app.staticTexts["Cash"].tap()

        waitFor(app.staticTexts["更新「Cash」"], "更新对话框")

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

    private func addCashAsset(value: String, cost: String?) throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")
        app.buttons["＋"].tap()
        waitFor(app.staticTexts["添加资产"], "添加对话框")

        type("field-asset-name", "Cash")
        type("field-asset-amount", value)
        if let cost { type("field-asset-cost", cost) }

        app.buttons["添加"].tap()
    }
}
