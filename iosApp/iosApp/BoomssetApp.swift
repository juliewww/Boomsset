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
        // 必须在任何 Compose 界面创建之前启动 Koin ——
        // koinViewModel() 在没有 Koin application 时会抛异常。
        IosModuleKt.doInitKoinIos()
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
        }
    }
}
