import Foundation
import Observation

/// Scoped presentation snapshots allow previously visited columns to render in the same frame.
@MainActor @Observable
final class ViewSnapshots {
    static let shared = ViewSnapshots()
    var footballTables: [String: [FootballTable]] = [:]
    var footballPlayers: [String: [FootballPlayer]] = [:]
    var racingTables: [String: RacingTable] = [:]
    private var details: [String: MatchDetail] = [:]
    private var detailOrder: [String] = []
    func detail(_ id: String) -> MatchDetail? { details[id] }
    func retain(_ value: MatchDetail, id: String) {
        details[id] = value; detailOrder.removeAll { $0 == id }; detailOrder.append(id)
        while detailOrder.count > 128 { details[detailOrder.removeFirst()] = nil }
    }
}
