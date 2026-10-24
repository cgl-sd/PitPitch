import Foundation
import Observation

@MainActor
@Observable
final class NavigationPerformance {
    private var teamStartedAt: Date?
    private(set) var teamHeaderMilliseconds: Int?
    private(set) var teamContentMilliseconds: Int?

    func beginTeamNavigation() {
        teamStartedAt = .now
        teamHeaderMilliseconds = nil
        teamContentMilliseconds = nil
    }

    func teamHeaderRendered() {
        guard let start = teamStartedAt, teamHeaderMilliseconds == nil else { return }
        teamHeaderMilliseconds = max(0, Int(Date.now.timeIntervalSince(start) * 1_000))
    }

    func teamContentRendered() {
        guard let start = teamStartedAt, teamContentMilliseconds == nil else { return }
        teamContentMilliseconds = max(0, Int(Date.now.timeIntervalSince(start) * 1_000))
    }
}
