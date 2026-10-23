import Foundation
import CryptoKit

enum ArchiveError: LocalizedError {
    case missing(String), invalid(String)
    var errorDescription: String? {
        switch self {
        case .missing: "本地资料暂未收录。"
        case .invalid: "本地资料校验失败。"
        }
    }
}

/// Only actor-owned callers read/parse JSON. Provider bodies and original clocks are immutable.
struct ArchiveFiles: Sendable {
    let root: URL
    func data(_ path: String) throws -> Data {
        guard !path.hasPrefix("/"), !path.split(separator: "/").contains("..") else { throw ArchiveError.invalid(path) }
        let file = try FileHandle(forReadingFrom: root.appendingPathComponent(path))
        defer { try? file.close() }
        var data = Data()
        while let part = try file.read(upToCount: 65_536), !part.isEmpty {
            guard data.count + part.count <= 16 * 1_024 * 1_024 else { throw ArchiveError.invalid(path) }
            data.append(part)
        }
        return data
    }
    func json(_ path: String) throws -> [String: Any] {
        guard let value = try JSONSerialization.jsonObject(with: data(path)) as? [String: Any] else { throw ArchiveError.invalid(path) }
        return value
    }
    private func packagedEnvelope(_ url: String, catalog: [String: Any]) throws -> (response: StoredResponse, fetched: String) {
        guard let record = catalog[url] as? [String: Any], let filename = record["file"] as? String,
              !filename.contains("/"), let hash = record["sha256"] as? String,
              let fetched = record["fetchedUtc"] as? String, let fetchedDate = SportsDate.parse(fetched) else { throw ArchiveError.missing(url) }
        let data = try data("sports/\(filename)")
        guard Self.hash(data) == hash else { throw ArchiveError.invalid(url) }
        let response = StoredResponse(url: url, fetched: fetchedDate, sha256: hash, data: data)
        _ = try response.body()
        return (response, fetched)
    }
    func storedResponse(_ url: String, catalog: [String: Any]) throws -> StoredResponse {
        try packagedEnvelope(url, catalog: catalog).response
    }
    func response(_ url: String, catalog: [String: Any]) throws -> (body: [String: Any], fetched: String) {
        let envelope = try packagedEnvelope(url, catalog: catalog)
        return (try envelope.response.body(), envelope.fetched)
    }
    static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
}

extension Dictionary where Key == String, Value == Any {
    func object(_ key: String) -> [String: Any] { self[key] as? [String: Any] ?? [:] }
    func objects(_ key: String) -> [[String: Any]] { self[key] as? [[String: Any]] ?? [] }
    func text(_ key: String) -> String { (self[key] as? String) ?? (self[key] as? NSNumber)?.stringValue ?? "" }
    func integer(_ key: String) -> Int? { Int(text(key)) }
}
