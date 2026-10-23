import Foundation

struct StoredResponse: Codable, Sendable {
    let url: String
    let fetched: Date
    let sha256: String
    let data: Data
    var allowedIDs: [String]? = nil
    var fallbacks: [StoredResponse]? = nil
    func body() throws -> [String: Any] {
        guard data.count <= 16 * 1_024 * 1_024, ArchiveFiles.hash(data) == sha256,
              let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw ArchiveError.invalid(url) }
        guard (fallbacks?.count ?? 0) <= 3, (fallbacks ?? []).allSatisfy({ $0.fallbacks == nil && $0.url == url }) else { throw ArchiveError.invalid(url) }
        var merged = value
        for key in ["rosters", "boxscore", "keyEvents"] where !Self.complete(merged, section: key) {
            for source in fallbacks ?? [] {
                guard let older = try? source.body(), older.object("header").text("id") == value.object("header").text("id"), Self.complete(older, section: key) else { continue }
                merged[key] = older[key]; break
            }
        }
        return merged
    }
    static func complete(_ body: [String: Any], section: String) -> Bool {
        if section == "keyEvents" { return body[section] is [[String: Any]] }
        let ids = Set(body.object("header").objects("competitions").first?.objects("competitors").map { $0.text("id") } ?? [])
        let rows: [[String: Any]]
        let child: String
        if section == "rosters" { guard let value = body[section] as? [[String: Any]] else { return false }; rows = value; child = "roster" }
        else { guard let value = body.object("boxscore")["teams"] as? [[String: Any]] else { return false }; rows = value; child = "statistics" }
        return rows.isEmpty || (!ids.isEmpty && Set(rows.map { $0.object("team").text("id") }) == ids && rows.allSatisfy { $0[child] is [[String: Any]] })
    }
    mutating func preserveMissingSections(from previous: StoredResponse) throws {
        let latest = try body()
        var original = previous; original.fallbacks = nil
        let candidates = [original] + (previous.fallbacks ?? [])
        var retained: [StoredResponse] = []
        for key in ["rosters", "boxscore", "keyEvents"] where !Self.complete(latest, section: key) {
            if let source = candidates.first(where: { (try? Self.complete($0.body(), section: key)) == true }), !retained.contains(where: { $0.sha256 == source.sha256 }) { retained.append(source) }
        }
        fallbacks = retained.isEmpty ? nil : retained
    }
}
/// Atomic envelopes preserve original bytes, source URL and acquisition time together.
/// This reconstructable cache is excluded from iCloud/device backup.
struct ResponseStore: Sendable {
    let root: URL
    init(root: URL? = nil) {
        self.root = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Responses", isDirectory: true)
    }
    private func boundedData(_ url: URL) throws -> Data {
        let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
        var result = Data()
        while let part = try handle.read(upToCount: 65_536), !part.isEmpty {
            guard result.count + part.count <= 96 * 1_024 * 1_024 else { throw ArchiveError.invalid(url.path) }; result.append(part)
        }
        return result
    }
    func path(_ url: String) -> URL { root.appendingPathComponent(url.contains("/scoreboard?") ? "Scoreboards" : "Documents", isDirectory: true).appendingPathComponent(ArchiveFiles.hash(Data(url.utf8)) + ".json") }
    func read(_ url: String) throws -> StoredResponse? {
        let current = path(url)
        let legacy = root.appendingPathComponent(ArchiveFiles.hash(Data(url.utf8)) + ".json")
        var newest: StoredResponse?, failure: Error?
        for file in [current, legacy] where FileManager.default.fileExists(atPath: file.path) {
            do {
                let envelope = try JSONDecoder().decode(StoredResponse.self, from: boundedData(file))
                guard envelope.url == url else { throw ArchiveError.invalid(url) }
                _ = try envelope.body()
                if newest == nil || envelope.fetched > newest!.fetched { newest = envelope }
            } catch { failure = error }
        }
        if let newest { return newest }
        if let failure { throw failure }
        return nil
    }

    func organizeLegacy() {
        let legacy = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        for file in legacy where file.pathExtension == "json" {
            guard let data = try? boundedData(file), let value = try? JSONDecoder().decode(StoredResponse.self, from: data), (try? value.body()) != nil else { continue }
            let destination = path(value.url)
            guard !FileManager.default.fileExists(atPath: destination.path) else { continue }
            do { try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true); try FileManager.default.moveItem(at: file, to: destination) } catch { }
        }
    }
    func all(_ prefix: String) -> [StoredResponse] {
        let legacy = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        let indexed = ["Scoreboards", "Documents"].flatMap { directory in
            (try? FileManager.default.contentsOfDirectory(at: root.appendingPathComponent(directory), includingPropertiesForKeys: nil)) ?? []
        }
        return (indexed + legacy.filter { $0.pathExtension == "json" }).compactMap { file in
            guard let data = try? boundedData(file), let value = try? JSONDecoder().decode(StoredResponse.self, from: data), value.url.hasPrefix(prefix), (try? value.body()) != nil else { return nil }; return value
        }
    }
    func save(_ response: StoredResponse) throws {
        _ = try response.body()
        if let previous = try? read(response.url), previous.fetched >= response.fetched { return }
        try FileManager.default.createDirectory(at: path(response.url).deletingLastPathComponent(), withIntermediateDirectories: true)
        var directory = root
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        try Task.checkCancellation()
        try JSONEncoder().encode(response).write(to: path(response.url), options: .atomic)
    }
}

/// One shared connection pool; bounded response delivery cancels the underlying task.
final class SportsHTTP: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    static let shared = SportsHTTP()
    private struct Pending {
        var data = Data()
        let continuation: CheckedContinuation<StoredResponse, Error>
        let url: String
    }
    private let lock = NSLock()
    private var pending: [Int: Pending] = [:]
    private var session: URLSession!
    override init() {
        super.init()
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 12
        config.timeoutIntervalForResource = 20
        config.httpMaximumConnectionsPerHost = 4
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }
    func fetch(_ url: String) async throws -> StoredResponse {
        #if DEBUG
        if ProcessInfo.processInfo.environment["PITPITCH_TEST_NETWORK_FAILURE"] == "1" { throw URLError(.notConnectedToInternet) }
        #endif
        guard let address = URL(string: url), address.scheme == "https",
              (["site.api.espn.com", "site.web.api.espn.com", "api.jolpi.ca"].contains(address.host ?? "") || address.host?.hasSuffix(".espncdn.com") == true) else { throw ArchiveError.invalid(url) }
        let task = session.dataTask(with: address)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                lock.lock(); pending[task.taskIdentifier] = Pending(continuation: continuation, url: url); lock.unlock()
                if Task.isCancelled { task.cancel() }
                task.resume()
            }
        } onCancel: { task.cancel() }
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode), response.expectedContentLength <= 16 * 1_024 * 1_024 else {
            completionHandler(.cancel); return
        }
        completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        let exceeds = (pending[dataTask.taskIdentifier]?.data.count ?? 0) + data.count > 16 * 1_024 * 1_024
        if !exceeds { pending[dataTask.taskIdentifier]?.data.append(data) }
        lock.unlock()
        if exceeds { dataTask.cancel() }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock(); let value = pending.removeValue(forKey: task.taskIdentifier); lock.unlock()
        guard let value else { return }
        if let error { value.continuation.resume(throwing: error) }
        else { value.continuation.resume(returning: StoredResponse(url: value.url, fetched: .now, sha256: ArchiveFiles.hash(value.data), data: value.data)) }
    }
}
