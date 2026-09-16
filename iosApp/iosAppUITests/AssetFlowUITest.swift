import XCTest

/// Interaction flow verification on iOS.
///
/// Why this is needed: all five "only found by actually running it" bugs in this
/// project were caught on Android. Most were in the shared layer, so they got fixed
/// for iOS too —— but iOS-specific behavior (Compose input on a Skia canvas,
/// keyboard, accessibility mapping) had never been verified by anyone.
///
/// Precondition already established: CMP's semantics do map to UIAccessibility, so
/// XCUITest can locate the controls Compose renders (see AccessibilityProbeTest's output).
final class AssetFlowUITest: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        // Each test starts from a clean state —— assets created by the previous test must not affect the next
        app.launchArguments = ["-uitest-reset"]
        app.launch()
        _ = app.wait(for: .runningForeground, timeout: 15)
    }

    /// Waits for an element to appear; dumps the current tree on failure —— otherwise an
    /// "element not found" failure gives no clue at all
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
        // All three tabs are present
        XCTAssertTrue(app.buttons["净值"].exists)
        XCTAssertTrue(app.buttons["配置"].exists)
        XCTAssertTrue(app.buttons["资产"].exists)

        // The add button lives on the "Assets" tab, not "Net Worth" —— adding an asset
        // is something the Assets tab does; putting it on the Net Worth tab (a read-only
        // trend overview) would make users look for the entry point in the wrong place
        // (real-device feedback).
        app.buttons["资产"].tap()
        XCTAssertTrue(app.buttons["＋"].exists, "资产页空状态下加号必须可见")
    }

    func testTabsSwitch() throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")

        app.buttons["配置"].tap()
        // The Allocation tab shows its title and target percentages even with zero assets —— no longer just a single line saying "go add an asset"
        waitFor(app.staticTexts["资产配置"], "配置页")

        app.buttons["资产"].tap()
        // The add button is already on this tab, no need to point users to "Net Worth" anymore
        waitFor(app.staticTexts["没有在持资产。点右下角加号添加。"], "资产页空态")

        app.buttons["净值"].tap()
        waitFor(app.staticTexts["还没有资产"], "回到净值页")
    }

    /// The complete add-asset flow —— mirrors the one verified on Android
    func testAddManualAssetComputesNetWorthAndPnL() throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")
        app.buttons["资产"].tap()
        app.buttons["＋"].tap()

        waitFor(app.staticTexts["添加资产"], "添加资产页")
        pickSubtype("现金")

        // The name is already prefilled by the subtype ("现金"/Cash) —— no manual typing needed, which is exactly what the new flow set out to eliminate
        type("field-asset-amount", "100000")
        type("field-asset-cost", "95000")

        app.buttons["添加"].tap()

        // After adding, it pops back to the "Assets" tab (the add button now lives there) —— net worth and P&L are checked on the "Net Worth" tab
        app.buttons["净值"].tap()

        // Both net worth and P&L must be correct: 100000 - 95000 = 5000, 5000/95000 = 5.26%
        waitFor(app.staticTexts["¥100,000.00"], "净值 ¥100,000.00")
        XCTAssertTrue(
            app.staticTexts["浮动盈亏 ¥5,000.00（+5.26%）"].exists,
            "盈亏应为 ¥5,000.00（+5.26%）"
        )
    }

    /// Updating a valuation —— the focus here is that the prefilled value can be read back by its own parser (this used to be broken on Android)
    func testUpdateValuePrefillIsParseable() throws {
        try addCashAsset(value: "100000", cost: "95000")

        app.buttons["资产"].tap()
        waitFor(app.staticTexts["现金"], "资产列表里的现金")
        // There are now two ways to update a valuation: swipe left to reveal the
        // "Update" button, or tap the whole row card directly. This test cares about
        // the prefill format, not "how to get into the dialog", so it takes **the most
        // reliable path** —— tapping the card directly. Verification of the swipe
        // gesture itself is in the comment on testUpdatingValueIsAVisibleAction:
        // XCUITest can't get `SwipeToDismissBox`'s drag gesture to register on this
        // simulator, which doesn't mean the feature itself is broken.
        app.staticTexts["现金"].tap()

        waitFor(app.staticTexts["更新「现金」"], "更新对话框")

        // The prefill must be 100000.00 (no thousands separator) —— with a comma, saving would be permanently disabled
        // A TextView's value is just its current text content
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

        // The save button must be enabled —— this is exactly what was broken on Android
        let save = app.buttons["保存"]
        waitFor(save, "保存按钮")
        XCTAssertTrue(save.isEnabled, "预填值必须能被解析，否则保存永久禁用")
    }

    func testAllocationSharesCloseAt100Percent() throws {
        try addCashAsset(value: "100000", cost: nil)

        app.buttons["配置"].tap()
        waitFor(app.staticTexts["资产配置"], "配置页")

        // Only one liquid-assets holding → that class is 100%
        XCTAssertTrue(app.staticTexts["100.00%"].exists, "流动资金应占 100%")
        XCTAssertTrue(app.staticTexts["净资产 ¥100,000.00"].exists)
        // Built-in presets can be switched
        XCTAssertTrue(app.staticTexts["平衡"].exists || app.buttons["平衡"].exists)
    }

    /// **Deviation percentages must be converted into money.**
    ///
    /// "90% overallocated" doesn't tell you how much to move —— the user would have
    /// to multiply by net worth themselves (real-device feedback).
    ///
    /// The scenario is deliberately clean: only ¥100,000 in cash (subtype "现金" →
    /// liquid assets), compared against the built-in "平衡"/Balanced preset (10%
    /// liquid / 35% fixed income). Liquid assets at 100% is 90% overallocated → needs
    /// ¥90,000 reduced; fixed income at 0% is 35% underallocated → needs ¥35,000 added.
    /// The convention is that **total net worth stays constant**, so the five classes'
    /// adjustment amounts must sum to exactly 0 —— that invariant is locked down by
    /// `AllocationRebalanceTest`; this test only verifies it's actually shown on screen
    /// and that the numbers weren't mangled by the formatting layer.
    ///
    /// ⚠️ **The vertical line marking the target position on the bar can't be verified
    /// here.** It's a shape drawn by `AllocationBar` using Canvas, so it produces no
    /// accessibility element, and the project has no Compose UI tests
    /// (`compose-ui-test` is declared in libs.versions.toml but never actually pulled
    /// in). Changes to `AllocationBar` require manually eyeballing the line's position
    /// on a real device/simulator —— this is the same category of gap as the swipe
    /// gesture: being honest about what the toolchain can't verify is more useful than
    /// forcing an assertion that doesn't actually test anything.
    func testAllocationShowsMoneyNeededToReachTarget() throws {
        try addCashAsset(value: "100000", cost: nil)

        app.buttons["配置"].tap()
        waitFor(app.staticTexts["资产配置"], "配置页")

        assertAllocationRow(
            "目标 10%，超配 90.00% · 距目标 -¥90,000",
            "超配的类要给出「需减少多少钱」"
        )
        assertAllocationRow(
            "目标 35%，低配 35.00% · 距目标 +¥35,000",
            "低配的类要给出「需增加多少钱」"
        )
        // The explicit plus sign on net exposure must be shown too —— it used to only carry a sign when negative, giving no sense of direction
        assertAllocationRow("净敞口 +¥100,000.00", "净敞口要带显式正号")
    }

    /// Asserts that a given line of text exists on the Allocation tab, **scrolling first if needed**.
    ///
    /// Can't just use `exists`: the accessibility tree only reports nodes within the
    /// visible area, and the third/fourth asset-class cards fall below the fold on
    /// small-screen devices, so `exists` would be false and the failure would look
    /// like "wrong copy" when it's really just "hasn't scrolled there yet" (this
    /// exact trap is noted in the comment on [scrollUntilVisible]).
    private func assertAllocationRow(_ text: String, _ why: String) {
        if app.staticTexts[text].exists { return }
        if scrollUntilVisible(text) != nil { return }
        XCTFail("\(why)：找不到「\(text)」，实际树：\n\(app.debugDescription)")
    }

    /// Regression test: **the target-allocation entry point must be reachable even with zero assets.**
    ///
    /// It once wasn't —— `AllocationScreen`'s `state.isEmpty` branch rendered only a
    /// single line, "还没有资产，先去「净值」页添加" ("no assets yet, go add one on
    /// the Net Worth tab"), while `AllocationPicker`, the sole entry point for
    /// switching/editing/creating target allocations, lived in the `else` branch and
    /// was skipped entirely. New users therefore had no way to set a target
    /// allocation, which is exactly what someone wants to do **before** recording
    /// their first asset.
    ///
    /// Same category of bug as "can't un-archive after archiving everything" (the
    /// empty state takes a branch with no entry point), the third instance of it. The
    /// data layer was always correct, so **only a UI test can catch this** ——
    /// `AllocationUiStateTest` can only prove the data exists.
    func testAllocationTargetsReachableWithNoAssets() throws {
        waitFor(app.staticTexts["还没有资产"], "净值页空状态")

        app.buttons["配置"].tap()
        waitFor(app.staticTexts["资产配置"], "配置页标题")

        // All three built-in presets must be selectable
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

        // The target percentages themselves must be shown —— this tab should be
        // useful even with no data.
        // The "平衡"/Balanced preset's equities target is 40%
        XCTAssertTrue(
            app.staticTexts["目标 40%"].exists,
            "零资产时应显示目标比例，实际树：\n\(app.debugDescription)"
        )

        // Edit-ratios/restore-defaults are no longer always-visible buttons; they only
        // expand on a long press of the current target ("平衡") —— the always-visible
        // buttons took up space, and real-device feedback asked for them to be
        // collapsed. Long-pressing must still work with zero assets, since setting a
        // target allocation is exactly what someone wants to do before adding their
        // first asset.
        let activeTarget = app.buttons["平衡"].exists ? app.buttons["平衡"] : app.staticTexts["平衡"]
        XCTAssertTrue(activeTarget.exists, "当前目标「平衡」应可见，实际树：\n\(app.debugDescription)")
        activeTarget.press(forDuration: 1.0)
        let editButton = app.buttons["编辑比例"]
        XCTAssertTrue(
            editButton.waitForExistence(timeout: 5),
            "长按当前目标后应展开「编辑比例」，实际树：\n\(app.debugDescription)"
        )

        // The edit dialog must actually open, not just have the button exist
        editButton.tap()
        XCTAssertTrue(
            app.staticTexts["编辑「平衡」"].waitForExistence(timeout: 5),
            "点「编辑比例」应打开编辑对话框，实际树：\n\(app.debugDescription)"
        )
    }

    /// Adding an asset is a **standalone page**; the first step is picking the
    /// subtype, with the asset class derived automatically, and currency is a dropdown.
    ///
    /// All three points are feedback from actual use: a dialog is too narrow to fit
    /// this form; a row of currency chips hogs the most prominent spot on the form
    /// even though currency almost never changes; and **the user doesn't know which
    /// asset class the thing they're adding belongs to**, so they shouldn't be made
    /// to pick the class first.
    func testAddAssetIsAFullPagePickingBySubtype() throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")
        app.buttons["资产"].tap()
        app.buttons["＋"].tap()
        waitFor(app.staticTexts["添加资产"], "添加资产页")

        // Standalone page: the bottom tab bar must be hidden while entering data, since a stray tap would discard what's been filled in
        XCTAssertFalse(app.buttons["配置"].exists, "添加页不该还显示底部 tab")
        XCTAssertTrue(app.buttons["取消"].exists, "独立页面必须有退路")

        // Step one is the subtype, not the class. Names the user recognizes must be directly selectable.
        // Alipay and WeChat Wallet appear on the first screen (liquid assets come first since they're recorded most often)
        for subtype in ["支付宝", "微信钱包"] {
            XCTAssertTrue(
                app.buttons[subtype].exists || app.staticTexts[subtype].exists,
                "品种「\(subtype)」应在第一屏可选，实际树：\n\(app.debugDescription)"
            )
        }
        pickSubtype("支付宝")

        // The asset class is a **result**, not a question —— once the subtype is picked, immediately tell the user which class it falls into
        XCTAssertTrue(
            app.staticTexts["归入流动资金"].exists,
            "应显示品种带出来的大类，实际树：\n\(app.debugDescription)"
        )
        // Name is already prefilled with the subtype name
        XCTAssertTrue(
            app.debugDescription.contains("支付宝"),
            "名称应预填品种名"
        )
        // Currency is a dropdown, not a row of chips
        XCTAssertTrue(
            textView("field-asset-currency").exists,
            "币种应为下拉框，实际树：\n\(app.debugDescription)"
        )
    }

    /// Picking a liability subtype should auto-enable the liability toggle —— the user shouldn't have to think through "a mortgage is a liability" again
    func testLiabilitySubtypePresetsTheLiabilityFlag() throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")
        app.buttons["资产"].tap()
        app.buttons["＋"].tap()
        waitFor(app.staticTexts["添加资产"], "添加资产页")

        // Liabilities form their own group at the end of the list —— scrolling down should find it
        XCTAssertNotNil(scrollUntilVisible("负债"), "负债应当单独成组")
        pickSubtype("房贷")

        XCTAssertTrue(
            app.staticTexts["负债 · 从另类实物抵扣"].exists,
            "房贷应预设为负债并说明抵扣哪一类，实际树：\n\(app.debugDescription)"
        )
        // Liabilities have no concept of "cost" or "valuation mode" —— these sections should be collapsed
        XCTAssertFalse(app.staticTexts["怎么估值"].exists, "负债不该显示估值方式")
    }

    /// Regression test: **updating a valuation must not have only a hidden entry point.**
    ///
    /// It once really did have only a hidden entry point (the whole card was
    /// tappable, with no buttons at all). A user wanting to change an Alipay balance
    /// from 100k to 120k would see only two tappable things, "Edit info" and
    /// "Archive", and naturally tap the former —— but that dialog **has no amount
    /// field at all**, leading them to reasonably conclude "you can't change the
    /// asset." This came from actual usage feedback.
    ///
    /// An always-visible "Update valuation" button was later added, fixing the
    /// problem; this round of feedback then asked for it to be tucked into a swipe
    /// gesture instead (the always-visible button ate up nearly half the card's
    /// height). **This shouldn't let that lesson be sidestepped**: the card itself
    /// is still tappable as a whole and opens the update dialog directly —— "Update"
    /// is a core action reachable **without needing to discover a gesture**; the
    /// button revealed by swiping left is a shortcut for users who already know the
    /// gesture, not the only way in.
    ///
    /// **The swipe-left gesture itself has no automated assertion —— not because it
    /// wasn't attempted, but because this toolchain can't verify it.**
    /// Tried, in order: `swipeLeft()`, coordinate-level `press(forDuration:thenDragTo:)`,
    /// and the overload with explicit velocity — none of the three gestures could get
    /// `SwipeToDismissBox`'s `anchoredDraggable` to register as a drag on this
    /// simulator (the "Update/Edit/Archive" buttons behind it remained collapsed in
    /// the accessibility tree every time). But the recognition logic for this gesture
    /// is **pure shared Kotlin code** —— iOS and Android run through the exact same
    /// `anchoredDraggable`, and the only platform difference is in how touch events
    /// get delivered. On Android, the whole chain has already been manually verified
    /// using `adb shell input draganddrop` (which is closer to real continuous touch
    /// than the coarser `input swipe`): swiping left reveals the buttons, and tapping
    /// "Update" opens the dialog. The conclusion here is "XCUITest's synthetic
    /// gestures aren't forceful enough on this simulator," not "this feature is
    /// broken on iOS" —— but **this is a genuine gap in automated coverage**, and
    /// changing this code should involve manually swiping on a real device or
    /// simulator in addition to running this test.
    func testUpdatingValueIsAVisibleAction() throws {
        try addCashAsset(value: "100000", cost: nil)
        app.buttons["资产"].tap()
        waitFor(app.staticTexts["现金"], "资产列表里的现金")

        // No need to discover the swipe-left gesture first —— tapping the whole row directly opens the update dialog
        app.staticTexts["现金"].tap()
        waitFor(app.staticTexts["更新「现金」"], "点整行应直接打开更新对话框")
        let field = textView("field-update-amount")
        waitFor(field, "市值输入框")
        XCTAssertTrue(field.isEnabled, "更新对话框里的市值必须可改")
    }

    /// The Net Worth tab's empty state must give onboarding guidance, not just "tap the plus button"
    func testEmptyStateExplainsHowTheAppWorks() throws {
        waitFor(app.staticTexts["还没有资产"], "空状态")

        // Key concept: it records snapshots, not transactions. If this isn't made clear, users will treat it like a bookkeeping app
        XCTAssertTrue(
            app.staticTexts.containing(
                NSPredicate(format: "label CONTAINS %@", "不记流水")
            ).firstMatch.exists,
            "空状态必须说明这个 App 记快照而不是记流水"
        )
        // All three onboarding steps are present
        for step in ["1", "2", "3"] {
            XCTAssertTrue(app.staticTexts[step].exists, "缺第 \(step) 步指引")
        }
    }

    // MARK: - The Net Worth chart's three controls

    /// The period is a **dropdown**, not a row of always-visible chips; all three are selectable, and the current one has a checkmark in the menu.
    ///
    /// Incidentally guards against the real crash from AGENTS.md lesson 14: **switching from monthly to quarterly/yearly used to crash**.
    /// The cause was that the chart model and UI state are necessarily one frame out
    /// of sync —— on the frame of the switch, composition already has the new series
    /// (with fewer points), but Vico still holds the old model; the formatter is
    /// asked for an out-of-range x and returns an empty string, and Vico calls
    /// `check(isNotBlank())` on every axis label. **It only crashes when switching to
    /// a coarser granularity** (only then does the point count shrink enough to hit
    /// null), so this test must walk through the "month → quarter → year" direction.
    func testChartPeriodIsADropdown() throws {
        try addCashAsset(value: "100000", cost: nil)
        app.buttons["净值"].tap()

        // The three always-visible chips should be gone now —— tucking them into a dropdown is precisely to give that row back to the chart
        waitFor(periodButton("按月"), "周期下拉按钮")
        XCTAssertFalse(
            app.buttons["按季"].exists,
            "周期应收进下拉，不该有常驻的「按季」chip，实际树：\n\(app.debugDescription)"
        )

        // Month → quarter → year, progressively coarser, must not crash at any step
        for next in ["按季", "按年"] {
            let previous = currentPeriodLabel()
            let current = periodButton(previous)
            waitFor(current, "周期下拉按钮")
            current.tap()

            // The menu covers the button itself, so the current item must be marked
            // some other way in the menu (a checkmark) —— relying on "the button says
            // 月/month" isn't enough since that area is covered by the menu
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
            // Switching to a coarser granularity is the direction that used to crash — confirm the app is still alive
            XCTAssertEqual(app.state, .runningForeground, "切到「\(next)」后 App 不该退出")
        }
    }

    /// All four combinations of the two switches (by-class / trend-line) must remain operable, and each must show **visible** content.
    ///
    /// "Visible content" is the focus of this test, not just "doesn't crash".
    /// AGENTS.md lesson 10 came from exactly this: with only one sample point, a line
    /// chart can't draw a segment, so the chart area shows only axes —— data all
    /// correct, unit tests all green, and the user sees a blank space. Right after
    /// adding one asset here there is **exactly one point**, so the trend chart must
    /// explicitly say "not enough points" rather than render an empty chart.
    func testChartModeAndStyleSwitchesStayUsable() throws {
        try addCashAsset(value: "100000", cost: nil)
        app.buttons["净值"].tap()
        waitFor(periodButton("按月"), "周期下拉按钮")

        // ① Total assets + bar chart (default): a bar must be drawable even with one
        //    point (ghost series, lesson 13). The chart is drawn by Skia and doesn't
        //    enter the accessibility tree, so the only way to assert here is the
        //    reverse: "it did NOT fall into either of the explanatory branches",
        //    i.e. the chart was actually drawn.
        XCTAssertFalse(trendTooShortNote.exists, "默认是柱状图，不该提示点数不够")

        // ② By-class + bar chart: the legend appears, all five classes have names
        //    (a few class colors fall below 3:1 in light mode, so the name is the
        //    compensating mechanism — can't rely on the color swatch alone)
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

        // ③ By-class + trend line: only one point, must **explicitly say** it can't be drawn rather than show a blank chart
        toggle("趋势图")
        XCTAssertTrue(
            trendTooShortNote.waitForExistence(timeout: 5),
            "只有一个取样点时趋势图应说明原因，实际树：\n\(app.debugDescription)"
        )

        // ④ Total assets + trend line: still only one point, same explanation applies
        toggle("按大类")
        XCTAssertTrue(
            trendTooShortNote.waitForExistence(timeout: 5),
            "总资产趋势图在一个点时也该说明原因，实际树：\n\(app.debugDescription)"
        )

        // Switching back to bar chart, the explanation collapses and the chart redraws
        toggle("趋势图")
        XCTAssertFalse(
            trendTooShortNote.waitForExistence(timeout: 2),
            "切回柱状图后不该还留着「点数不够」的说明"
        )
        XCTAssertEqual(app.state, .runningForeground, "四种组合切完 App 不该退出")
    }

    /// App-lock capability detection on iOS —— the simulator has no biometrics enrolled by default
    func testAppLockReportsCapabilityHonestly() throws {
        waitFor(app.staticTexts["应用锁"], "应用锁开关")

        // The simulator has neither Face ID enrolled nor a passcode set → it should
        // tell the user to go set one up in system settings, rather than vaguely
        // saying "unavailable"
        let notEnrolled = app.staticTexts["这台设备还没设锁屏密码或生物识别 —— 去系统设置里加上就能用了。"]
        let available = app.staticTexts["开启后每次打开猪满仓都需要验证身份。开启时会先验一次。"]
        XCTAssertTrue(
            notEnrolled.exists || available.exists,
            "应用锁必须给出明确的状态说明，而不是空白"
        )
    }

    // MARK: - Helpers

    /// Two facts established through probing:
    ///
    /// 1. **Compose's OutlinedTextField shows up as `TextView` in the iOS
    ///    accessibility tree, not `TextField`.** `app.textFields` finds none at all.
    /// 2. **Can't rely on OutlinedTextField's `label` for lookup** —— it only maps
    ///    to an accessibility label in some states, and disappears once focused
    ///    (even screen-reader users would hear nothing). So the shared layer adds an
    ///    explicit `contentDescription` to these input fields.
    /// 3. **Compose concatenates the contentDescription with the visible label**
    ///    into one accessibility label, so lookups must be prefix matches, not exact
    ///    matches. See [textView].
    /// Types text and **dismisses the keyboard**.
    ///
    /// Without dismissing the keyboard, the next field might end up under the
    /// keyboard, and `tap()` would hit the keyboard instead —— the failure is
    /// "Neither element nor any descendant has keyboard focus", which looks like a
    /// missing element but is really just tapping the wrong spot.
    /// A newline triggers ImeAction.Done on single-line fields, clearing focus.
    private func type(_ fieldId: String, _ text: String) {
        let field = textView(fieldId)
        waitFor(field, "输入框「\(fieldId)」")
        // The element might be under the keyboard; scroll it into view first
        if !field.isHittable {
            app.swipeUp()
        }
        field.tap()
        field.typeText(text + "\n")
    }

    /// Matches by contentDescription prefix.
    ///
    /// **Must use a prefix match, not an exact match** —— Compose **concatenates**
    /// the `contentDescription` with the input field's visible label into one
    /// accessibility label: `'field-asset-name, 名称，如「招行活期」'`. Using
    /// `app.textViews["field-asset-name"]` matches nothing.
    private func textView(_ idPrefix: String) -> XCUIElement {
        app.textViews
            .matching(NSPredicate(format: "label BEGINSWITH %@", idPrefix))
            .firstMatch
    }

    /// Scrolls down until a given label is visible and tappable.
    ///
    /// **The subtype list is longer than one screen, so scrolling is required.** The
    /// accessibility tree only reports nodes **within the visible area** —— an
    /// off-screen chip's `exists` check will be false, and the failure looks like
    /// "element doesn't exist" when it's really just not scrolled there yet. Same
    /// applies to Android's uiautomator (verified in practice: the liabilities group
    /// needs a full screen of scrolling before it appears).
    private func scrollUntilVisible(_ label: String, maxSwipes: Int = 8) -> XCUIElement? {
        let window = app.windows.firstMatch
        for _ in 0...maxSwipes {
            for candidate in [app.buttons[label], app.staticTexts[label]] {
                guard candidate.exists && candidate.isHittable else { continue }
                // **`isHittable` alone isn't enough.** An element stuck at the edge of
                // the screen still reports `isHittable == true`, but a tap hits its
                // center point, which can be outside the visible area —— resulting in
                // "tapped, nothing happened", which is much harder to debug than
                // "element not found" (verified in practice: after scrolling to the
                // liabilities group, tapping "mortgage" never opened the detail page).
                // Require the element to fall **entirely** within the window, leaving
                // extra headroom for the TopAppBar height.
                let f = candidate.frame
                if f.minY > window.frame.minY + 96 && f.maxY < window.frame.maxY - 24 {
                    return candidate
                }
            }
            app.swipeUp()
            // While inertial scrolling hasn't settled, the element is still moving, and tapping immediately would miss. Wait for it to stop.
            Thread.sleep(forTimeInterval: 0.5)
        }
        return nil
    }


    /// Picks a subtype. **Step one of the new flow** —— the asset class is derived from the subtype, so the user never has to decide "which class does cash belong to".
    private func pickSubtype(_ name: String) {
        guard let chip = scrollUntilVisible(name) else {
            print(app.debugDescription)
            XCTFail("滚遍整页也找不到品种「\(name)」")
            return
        }
        chip.tap()
        // Once picked, it moves to the detail form; the marker for that is the "change" button on the "selected subtype" card
        waitFor(app.buttons["换一个"], "详情表单")
    }

    // MARK: - Chart control helpers

    /// The explanation the trend chart gives when there's only one sample point. See [testChartModeAndStyleSwitchesStayUsable].
    private var trendTooShortNote: XCUIElement {
        app.staticTexts.containing(
            NSPredicate(format: "label CONTAINS %@", "两个以上的取样点")
        ).firstMatch
    }

    /// The period dropdown button.
    ///
    /// **Must be a prefix match**: the button reads "按月 ▾" (with the dropdown
    /// triangle), so an exact match on `app.buttons["按月"]` finds nothing. The
    /// dropdown's **menu items**, on the other hand, have no triangle, so an exact
    /// match on those names is guaranteed to hit a menu item and never mistakenly
    /// hit the button —— that's exactly what the "no always-visible chip" assertion
    /// in `testChartPeriodIsADropdown` relies on to tell them apart.
    private func periodButton(_ label: String) -> XCUIElement {
        app.buttons
            .matching(NSPredicate(format: "label BEGINSWITH %@", label))
            .firstMatch
    }

    /// Waits for a given item to appear in the dropdown menu.
    ///
    /// **The element type of a menu item can't be predicted reliably** (a
    /// `DropdownMenuItem` is just a `Text` internally, which may land as a button or
    /// as a staticText), so both are waited on before deciding —— it can't be
    /// resolved immediately like `app.buttons[x].exists ? ... : ...`: the menu is a
    /// popup, and at the moment of checking it might not have been drawn yet,
    /// which would inevitably fall into the wrong branch and wait on nothing.
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

    /// Taps a switch with a text label ("按大类"/by-class or "趋势图"/trend line).
    ///
    /// The shared layer adds an explicit `contentDescription` to these two
    /// `Switch`es (the label is a sibling node and doesn't get merged into the
    /// switch's own accessibility node). **The element type can't be predicted
    /// reliably** —— a Compose switch may land as switch / button / other in the iOS
    /// accessibility tree depending on how the toggleable semantics get mapped, so
    /// each type is tried in turn here, same approach as [scrollUntilVisible].
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
