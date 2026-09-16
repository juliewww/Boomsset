import XCTest

/// Probe test: **first confirm whether XCUITest can even see Compose Multiplatform elements.**
///
/// CMP on iOS renders the entire UI onto a single Skia canvas, and XCUITest locates
/// controls via accessibility elements. If Compose's semantics don't map to
/// UIAccessibility, XCUITest will find nothing at all, making a whole suite of
/// interaction tests pointless —— so verify this one thing first.
final class AccessibilityProbeTest: XCTestCase {

    func testDumpAccessibilityTree() throws {
        let app = XCUIApplication()
        app.launch()

        // Give Compose time to render its first frame
        _ = app.wait(for: .runningForeground, timeout: 10)
        Thread.sleep(forTimeInterval: 3)

        // Dump the whole tree —— this is the actual output of this probe
        print("=== ACCESSIBILITY TREE START ===")
        print(app.debugDescription)
        print("=== ACCESSIBILITY TREE END ===")

        print("=== COUNTS ===")
        print("staticTexts: \(app.staticTexts.count)")
        print("buttons: \(app.buttons.count)")
        print("otherElements: \(app.otherElements.count)")

        // Look for our own copy
        let title = app.staticTexts["猪满仓"]
        let emptyHint = app.staticTexts["还没有资产"]
        print("找到「猪满仓」: \(title.exists)")
        print("找到「还没有资产」: \(emptyHint.exists)")
    }
}
