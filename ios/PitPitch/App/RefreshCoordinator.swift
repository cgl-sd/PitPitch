import Foundation
import Observation

@MainActor @Observable
final class RefreshCoordinator {
    let repository: ArchiveRepository
    var revision = 0
    var refreshing = false
    var lastSuccess: Date?
    var error: String?
    private var running: Task<Void, Never>?
    private var runID: UUID?
    private let defaults = UserDefaults.standard
    nonisolated static func homeSelections(_ raw: String?) -> Set<String> {
        let available = Set(Competition.all.map(\.id)).union(["f1"])
        let values = raw?.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
            ?? ["eng.1", "uefa.champions", "f1"]
        return Set(values).intersection(available)
    }
    init(repository: ArchiveRepository) {
        self.repository = repository
        let timestamp = defaults.double(forKey: "homeLastSuccessfulFetch")
        lastSuccess = timestamp > 0 ? Date(timeIntervalSince1970: timestamp) : nil
    }
    func cancel() { running?.cancel() }
    func refresh() async {
        if let running {
            let cancelled = running.isCancelled, previous = runID
            await running.value
            guard cancelled else { return }
            if runID == previous { self.running = nil; runID = nil }
            await refresh(); return
        }
        let identity = UUID()
        refreshing = true
        let operation = Task(priority: .utility) {
            let year = SportsDate.currentFootballSeason(), calendarYear = Calendar.current.component(.year, from: Date.now)
            let selected = Self.homeSelections(defaults.string(forKey: "homeCompetitions"))
            let successes = await withTaskGroup(of: Bool.self) { group in
                for competition in Competition.all where selected.contains(competition.id) {
                    group.addTask {
                        do {
                            try await self.repository.refreshFootball(competition: competition.id, season: year)
                            _ = try await self.repository.tables(competition: competition.id, season: year, refresh: true)
                            _ = try? await self.repository.leaders(competition: competition.id, season: year, refresh: true)
                            return true
                        } catch { return false }
                    }
                }
                if selected.contains("f1") {
                    group.addTask { (try? await self.repository.refreshHomeRace(season: calendarYear)) != nil }
                    group.addTask { (try? await self.repository.racingTable(season: calendarYear, constructors: false, refresh: true)) != nil }
                    group.addTask { (try? await self.repository.racingTable(season: calendarYear, constructors: true, refresh: true)) != nil }
                }
                var success = false
                for await value in group { success = value || success; self.revision += 1 }
                return success
            }
            if selected.isEmpty {
                error = nil
            } else if successes, let acquired = await repository.latestAcquisition {
                // Only a successful live acquisition advances this value.
                lastSuccess = acquired
                defaults.set(lastSuccess!.timeIntervalSince1970, forKey: "homeLastSuccessfulFetch")
                error = nil
            } else if !Task.isCancelled { error = "联网更新失败，已保留本地资料" }
        }
        running = operation; runID = identity
        await operation.value
        guard runID == identity else { return }
        running = nil; runID = nil; refreshing = false; revision += 1
    }
}
