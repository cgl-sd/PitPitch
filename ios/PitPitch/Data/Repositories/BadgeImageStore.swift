import Foundation
import ImageIO

actor BadgeImageStore {
    static let shared = BadgeImageStore()
    private var images: [String: Data] = [:]
    private var order: [String] = []
    private let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("BadgeImages", isDirectory: true)
    func image(_ url: String) async -> Data? {
        guard URL(string: url)?.scheme == "https" else { return nil }
        if let cached = images[url] { return cached }
        let path = root.appendingPathComponent(ArchiveFiles.hash(Data(url.utf8)) + ".json")
        if let handle = try? FileHandle(forReadingFrom: path) {
            defer { try? handle.close() }
            if let data = try? handle.read(upToCount: 6 * 1_024 * 1_024),
               let saved = try? JSONDecoder().decode(StoredResponse.self, from: data), saved.url == url,
               saved.data.count <= 4 * 1_024 * 1_024, saved.sha256 == ArchiveFiles.hash(saved.data) { retain(saved.data, for: url); return saved.data }
        }
        do {
            let response = try await NetworkLimiter.shared.fetch(url)
            try Task.checkCancellation()
            guard response.data.count <= 4 * 1_024 * 1_024, let source = CGImageSourceCreateWithData(response.data as CFData, nil), CGImageSourceGetCount(source) > 0 else { return nil }
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            var excluded = root, flags = URLResourceValues(); flags.isExcludedFromBackup = true
            try excluded.setResourceValues(flags)
            try JSONEncoder().encode(response).write(to: path, options: .atomic)
            retain(response.data, for: url); return response.data
        } catch { return nil }
    }
    private func retain(_ data: Data, for url: String) {
        images[url] = data; order.removeAll { $0 == url }; order.append(url)
        while order.count > 128 { images[order.removeFirst()] = nil }
    }
}
