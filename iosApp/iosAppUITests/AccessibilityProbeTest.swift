import XCTest

/// 探测性测试：**先确认 XCUITest 到底能不能看到 Compose Multiplatform 的元素。**
///
/// CMP 在 iOS 上把整个界面画在一个 Skia canvas 上，XCUITest 靠无障碍元素定位控件。
/// 如果 Compose 的 semantics 没有映射到 UIAccessibility，XCUITest 就什么都找不到，
/// 那么写一堆交互测试是白费功夫 —— 所以先只验这一件事。
final class AccessibilityProbeTest: XCTestCase {

    func testDumpAccessibilityTree() throws {
        let app = XCUIApplication()
        app.launch()

        // 给 Compose 首帧留时间
        _ = app.wait(for: .runningForeground, timeout: 10)
        Thread.sleep(forTimeInterval: 3)

        // 把整棵树打出来 —— 这就是这次探测的产出
        print("=== ACCESSIBILITY TREE START ===")
        print(app.debugDescription)
        print("=== ACCESSIBILITY TREE END ===")

        print("=== COUNTS ===")
        print("staticTexts: \(app.staticTexts.count)")
        print("buttons: \(app.buttons.count)")
        print("otherElements: \(app.otherElements.count)")

        // 找我们自己的文案
        let title = app.staticTexts["旺资"]
        let emptyHint = app.staticTexts["还没有资产"]
        print("找到「旺资」: \(title.exists)")
        print("找到「还没有资产」: \(emptyHint.exists)")
    }
}
