import SwiftUI
import SharedKit

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct BoomssetApp: App {
    init() {
        // UI 测试专用：每个测试从干净数据库开始，否则上一个测试建的资产会影响下一个。
        //
        // 刻意放在 Swift 这一侧而不是共享层 —— 这是测试的关切，不该出现在生产领域代码里。
        // 只有显式传 -uitest-reset 才生效，正常启动不受影响。
        if ProcessInfo.processInfo.arguments.contains("-uitest-reset") {
            Self.wipeDatabase()
        }

        // 必须在任何 Compose 界面创建之前启动 Koin ——
        // koinViewModel() 在没有 Koin application 时会抛异常。
        IosModuleKt.doInitKoinIos()
    }

    /// 删掉数据库文件。**连 -wal 和 -shm 一起删** —— SQLite 在 WAL 模式下
    /// 数据还在 -wal 里没 checkpoint，只删主文件会留下上一次的数据。
    private static func wipeDatabase() {
        let fm = FileManager.default
        guard let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        else { return }
        let dir = support.appendingPathComponent("databases")
        for suffix in ["", "-wal", "-shm"] {
            let url = dir.appendingPathComponent("boomsset.db\(suffix)")
            try? fm.removeItem(at: url)
        }
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
        }
    }
}
