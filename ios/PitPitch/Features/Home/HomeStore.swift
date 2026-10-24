import Foundation
import Observation

struct HomeCard: Identifiable, Sendable {
    let competition: String
    let round: Int?
    let fixtures: [FootballFixture]
    var id: String { competition }
    var date: Date { fixtures.first?.kickoff ?? .distantFuture }
}

enum HomeEntry: Identifiable, Sendable {
    case football(HomeCard)
    case race(RacingRound)
    var id: String { switch self { case .football(let card): card.id; case .race(let race): race.id } }
    func date(at now: Date) -> Date {
        switch self {
        case .football(let card): card.date
        case .race(let race): race.sessions.compactMap(\.date).filter { $0 > now }.min() ?? .distantFuture
        }
    }
}

private actor HomePresentationBuilder {
    static let shared = HomePresentationBuilder()
    func entries(fixtures: [FootballFixture], races: [RacingRound], selected: String, now: Date) -> [HomeEntry] {
        let enabled = Set(selected.split(separator: ",").map(String.init))
        var result = HomeRules.cards(fixtures.filter { enabled.contains($0.competition) }, now: now).map(HomeEntry.football)
        if enabled.contains("f1"), let race = races.first(where: { ($0.date ?? .distantPast) >= now }) { result.append(.race(race)) }
        return result.sorted { $0.date(at: now) < $1.date(at: now) }
    }
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
    private(set) var entries: [HomeEntry] = []
    var errors: [String] = []
    var ready = false
    @ObservationIgnored private var rawReady = false
    @ObservationIgnored private var revision = 0
    @ObservationIgnored private var selected = UserDefaults.standard.string(forKey: "homeCompetitions") ?? "eng.1,uefa.champions,f1"
    @ObservationIgnored private var now = Date.now
    @ObservationIgnored private var prepared: PresentationKey?
    @ObservationIgnored private var generation = 0
    private struct PresentationKey: Equatable { let revision: Int; let selected: String; let now: Date }

    func present(selected: String, now: Date) async {
        self.selected = selected
        self.now = now
        await prepareEntries()
    }

    private func prepareEntries() async {
        guard rawReady else { return }
        let key = PresentationKey(revision: revision, selected: selected, now: now)
        guard prepared != key else { return }
        generation += 1
        let identity = generation
        let result = await HomePresentationBuilder.shared.entries(fixtures: fixtures, races: races, selected: key.selected, now: key.now)
        guard !Task.isCancelled, identity == generation else { return }
        entries = result
        prepared = key
        ready = true
    }

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
        for slice in slices.sorted(by: { $0.key < $1.key }) {
            switch slice { case .football(let values): football += values; case .racing(let values): races = values; case .failure(let message): failures.append(message) }
        }
        guard !Task.isCancelled else { return }
        if self.fixtures != football || self.races != races {
            self.fixtures = football
            self.races = races
            revision += 1
        }
        if self.errors != failures { self.errors = failures }
        rawReady = true
        await prepareEntries()
    }
}

private enum HomeLocalSlice: Sendable {
    case football([FootballFixture]), racing([RacingRound]), failure(String)
    var key: String {
        switch self {
        case .football(let fixtures): fixtures.first?.competition ?? ""
        case .racing: "f1"
        case .failure(let message): message
        }
    }
}
