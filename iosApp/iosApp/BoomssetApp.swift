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
        // UI-test only: each test starts from a clean database, otherwise assets
        // created by the previous test would bleed into the next one.
        //
        // Deliberately placed on the Swift side rather than in the shared layer ——
        // this is a testing concern and shouldn't leak into production domain code.
        // Only takes effect when -uitest-reset is passed explicitly; normal launches
        // are unaffected.
        if ProcessInfo.processInfo.arguments.contains("-uitest-reset") {
            Self.wipeDatabase()
        }

        // Must start Koin before any Compose UI is created ——
        // koinViewModel() throws if there's no Koin application yet.
        IosModuleKt.doInitKoinIos()
    }

    /// Deletes the database file. **Deletes the -wal and -shm files too** —— under
    /// SQLite's WAL mode, data can still be sitting in -wal without being checkpointed,
    /// so removing only the main file would leave stale data behind.
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
