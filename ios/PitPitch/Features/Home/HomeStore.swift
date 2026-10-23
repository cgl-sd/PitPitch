import Foundation
import Observation

struct HomeCard: Identifiable {
    let competition: String
    let round: Int?
    let fixtures: [FootballFixture]
    var id: String { competition }
    var date: Date { fixtures.first?.kickoff ?? .distantFuture }
}

enum HomeRules {
    static func cards(_ fixtures: [FootballFixture], now: Date = .now) -> [HomeCard] {
        Dictionary(grouping: fixtures, by: \.competition).compactMap { competition, fixtures in
            let groups = Dictionary(grouping: fixtures) { $0.round.map(String.init) ?? $0.id }.values
                .map { $0.filter { !["取消", "延期"].contains($0.status) } }.filter { !$0.isEmpty }
            func end(_ values: [FootballFixture]) -> Date? {
                guard values.allSatisfy({ $0.kickoff != nil }) else { return nil }
                return values.compactMap(\.kickoff).max()
            }
            let closedThrough = groups.filter { end($0).map { $0 < now } == true }.compactMap { $0.first?.round }.max()
            let candidates = groups.compactMap { values -> HomeCard? in
                if let round = values.first?.round, let closedThrough, round <= closedThrough { return nil }
                if let last = end(values), last < now { return nil }
                let upcoming = values.filter { fixture in
                    if ["完场", "终止", "中止"].contains(fixture.status) { return false }
                    if let date = fixture.kickoff {
                        return date >= now || (fixture.status != "待赛" && Calendar.current.isDate(date, inSameDayAs: now))
                    }
                    let format = DateFormatter(); format.dateFormat = "yyyy-MM-dd"; format.locale = Locale(identifier: "en_US_POSIX")
                    return fixture.sourceDate >= format.string(from: now)
                }.sorted { ($0.kickoff ?? .distantFuture) < ($1.kickoff ?? .distantFuture) }
                return upcoming.isEmpty ? nil : HomeCard(competition: competition, round: values.first?.round, fixtures: Array(upcoming.prefix(3)))
            }
            return candidates.sorted {
                if $0.round != $1.round { return ($0.round ?? .max) < ($1.round ?? .max) }
                return $0.date < $1.date
            }.first
        }.sorted { $0.date < $1.date }
    }
}

@MainActor @Observable
final class HomeStore {
    var fixtures: [FootballFixture] = []
    var races: [RacingRound] = []
    var errors: [String] = []
    var ready = false
    func load(repository: ArchiveRepository) async {
        let slices = await withTaskGroup(of: HomeLocalSlice.self) { group in
            for competition in Competition.all {
                group.addTask {
                    do { return .football(try await repository.football(competition: competition.id, season: SportsDate.currentFootballSeason()).items) }
                    catch { return .failure("\(competition.name)：\(error.localizedDescription)") }
                }
            }
            group.addTask {
                do { return .racing(try await repository.races(season: Calendar.current.component(.year, from: .now))) }
                catch { return .failure("F1：\(error.localizedDescription)") }
            }
            var values: [HomeLocalSlice] = []
            for await value in group { values.append(value) }; return values
        }
        var football: [FootballFixture] = [], races: [RacingRound] = [], failures: [String] = []
        for slice in slices {
            switch slice { case .football(let values): football += values; case .racing(let values): races = values; case .failure(let message): failures.append(message) }
        }
        guard !Task.isCancelled else { return }
        self.fixtures = football; self.races = races; self.errors = failures; ready = true
    }
}

private enum HomeLocalSlice: Sendable { case football([FootballFixture]), racing([RacingRound]), failure(String) }
