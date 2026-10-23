import Foundation

actor NetworkLimiter {
    static let shared = NetworkLimiter()
    private var active = 0
    private var waiting: [(UUID, TaskPriority, CheckedContinuation<Void, Error>)] = []
    func fetch(_ url: String) async throws -> StoredResponse {
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                if Task.isCancelled { continuation.resume(throwing: CancellationError()) }
                else if active < 4 { active += 1; continuation.resume() }
                else { waiting.append((id, Task.currentPriority, continuation)) }
            }
        } onCancel: { Task { await self.cancel(id) } }
        defer { release() }
        try Task.checkCancellation()
        return try await SportsHTTP.shared.fetch(url)
    }
    private func cancel(_ id: UUID) {
        if let index = waiting.firstIndex(where: { $0.0 == id }) { waiting.remove(at: index).2.resume(throwing: CancellationError()) }
    }
    private func release() {
        if waiting.isEmpty { active -= 1 }
        else {
            let priority = waiting.map { $0.1 }.max()!
            let index = waiting.firstIndex { $0.1 == priority }!
            waiting.remove(at: index).2.resume()
        }
    }
}
