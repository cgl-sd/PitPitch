import Foundation
import Observation

@MainActor @Observable
final class AppUpdateChecker {
    var checking = false
    var result: String?
    var storeURL: URL?
    func check() async {
        guard !checking else { return }; checking = true; defer { checking = false }
        do {
            guard let bundle = Bundle.main.bundleIdentifier,
                  let url = URL(string: "https://itunes.apple.com/lookup?bundleId=\(bundle)&country=cn") else { return }
            var request = URLRequest(url: url); request.timeoutInterval = 20
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200, data.count < 1_048_576,
                  let body = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw URLError(.badServerResponse) }
            guard let app = body.objects("results").first(where: { $0.text("bundleId") == bundle }) else { result = "此 iOS 版本尚未在 App Store 发布"; return }
            let installed = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
            if app.text("version").compare(installed, options: .numeric) == .orderedDescending,
               let url = URL(string: app.text("trackViewUrl")), url.host == "apps.apple.com", url.scheme == "https" {
                storeURL = url; result = "发现新版本 \(app.text("version"))"
            } else { result = "已是最新版本" }
        } catch { if !Task.isCancelled { result = "检查失败：\(error.localizedDescription)" } }
    }
}
