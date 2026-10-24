import XCTest
@testable import PitPitch

final class PortParityTests: XCTestCase {
    private var root: URL { Bundle.main.resourceURL!.appendingPathComponent("assets") }
    @MainActor
    func testHomePresentationUpdatesForSelectionsAndTimeWithoutChangingFacts() async throws {
        let repository = ArchiveRepository(root: root, responseRoot: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        let store = HomeStore()
        let time = try XCTUnwrap(SportsDate.parse("2026-10-23T00:00:00Z"))
        await store.present(selected: "eng.1", now: time)
        XCTAssertFalse(store.ready)
        await store.load(repository: repository)
        XCTAssertTrue(store.ready)
        let raw = store.fixtures
        func cards() -> [HomeCard] { store.entries.compactMap { if case .football(let card) = $0 { return card }; return nil } }
        let expected = HomeRules.cards(raw.filter { $0.competition == "eng.1" }, now: time)
        XCTAssertEqual(cards().flatMap(\.fixtures), expected.flatMap(\.fixtures))
        XCTAssertFalse(cards().isEmpty)
        await store.present(selected: "f1", now: time)
        XCTAssertTrue(cards().isEmpty)
        XCTAssertEqual(store.entries.map(\.id), store.races.first { ($0.date ?? .distantPast) >= time }.map { [$0.id] } ?? [])
        let later = time.addingTimeInterval(30 * 86_400)
        await store.present(selected: "eng.1", now: later)
        XCTAssertEqual(cards().flatMap(\.fixtures), HomeRules.cards(raw.filter { $0.competition == "eng.1" }, now: later).flatMap(\.fixtures))
        XCTAssertEqual(store.fixtures, raw)
    }
    func testSavedTeamIdentityDoesNotRequireLoadingWholeSeasons() async throws {
        let repository = ArchiveRepository(root: root, responseRoot: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        let resolved = try await repository.team(id: "360")
        let team = try XCTUnwrap(resolved)
        XCTAssertEqual(team.originalName, "Manchester United")
        XCTAssertEqual(team.name, "曼联")
        XCTAssertNotNil(team.badgePath)
        let seasons = await repository.footballCache.count
        XCTAssertEqual(seasons, 0)
        let unknown = try await repository.team(id: "unverified")
        XCTAssertNil(unknown)
    }
    func testStatisticsUseIndependentHalfWidthLimitsAndRejectUnknownValues() throws {
        let shots = try XCTUnwrap(MatchPresentation.fractions(home: "17", away: "5"))
        XCTAssertEqual(shots.home, 1)
        XCTAssertEqual(shots.away, 5.0 / 17, accuracy: 0.0001)
        let cards = try XCTUnwrap(MatchPresentation.fractions(home: "1", away: "3"))
        XCTAssertEqual(cards.home, 1.0 / 3, accuracy: 0.0001)
        XCTAssertEqual(cards.away, 1)
        let possession = try XCTUnwrap(MatchPresentation.fractions(home: "65.4%", away: "34.6%"))
        XCTAssertEqual(possession.away, 34.6 / 65.4, accuracy: 0.0001)
        XCTAssertEqual(MatchPresentation.fractions(home: "0", away: "0")?.home, 0)
        XCTAssertEqual(MatchPresentation.fractions(home: "0", away: "0")?.away, 0)
        for unknown in ["—", "", "nan", "inf", "-1"] {
            XCTAssertNil(MatchPresentation.fractions(home: unknown, away: "1"))
        }
    }
    func testTimelineRecognizesProviderStageNodesIndependentlyOfTeamIdentity() {
        for type in MatchPresentation.nodes {
            let event = MatchEvent(id: type, type: type, clock: "", text: "source", player: "", teamID: "home", period: 1)
            XCTAssertTrue(MatchPresentation.visible(event))
        }
        for type in ["goal", "own-goal", "penalty---scored", "red-card", "second-yellow-card"] {
            XCTAssertTrue(MatchPresentation.visible(MatchEvent(id: type, type: type, clock: "14'", text: "", player: "Player", teamID: "home", period: 1)))
        }
        for type in ["yellow-card", "substitution"] {
            XCTAssertFalse(MatchPresentation.visible(MatchEvent(id: type, type: type, clock: "14'", text: "", player: "Player", teamID: "home", period: 1)))
        }
    }
    func testFootballRankingKeepsUnknownStatisticsAndDeduplicatesPlayers() {
        let team = FootballTeam(id: "team", originalName: "Team", name: "Team", badgePath: nil)
        func player(_ id: String, _ name: String, goals: Double?, assists: Double?) -> FootballPlayer {
            FootballPlayer(
                id: id,
                name: name,
                number: "",
                position: "Forward",
                team: team,
                goals: goals,
                assists: assists,
                starter: nil
            )
        }
        let players = [
            player("unknown", "Alpha Unknown", goals: nil, assists: nil),
            player("known", "Beta Known", goals: 4, assists: 8),
            player("known", "Beta Known", goals: 7, assists: 2),
            player("tie", "Alpha Tie", goals: 4, assists: 1)
        ]

        let goals = FootballRanking.ranked(players, mode: 0)
        XCTAssertEqual(goals.map(\.id), ["known", "tie", "unknown"])
        XCTAssertEqual(goals.first?.goals, 7)
        XCTAssertNil(goals.last?.goals)

        let assists = FootballRanking.ranked(players, mode: 1)
        XCTAssertEqual(assists.map(\.id), ["known", "tie", "unknown"])
        XCTAssertEqual(assists.first?.assists, 8)
        XCTAssertNil(assists.last?.assists)
    }
    func testEveryArchivedTableRosterAndRaceResultParsesWithinItsIdentity() async throws {
        let files = ArchiveFiles(root: root), catalog = try files.json("sports/catalog.json")
        let repository = ArchiveRepository(root: root, responseRoot: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        var tableCount = 0, rosterCount = 0, resultCount = 0
        for season in 2020...2026 {
            for competition in Competition.all {
                let tables = try await repository.tables(competition: competition.id, season: season)
                XCTAssertFalse(tables.isEmpty)
                tableCount += 1
                for table in tables {
                    XCTAssertEqual(Set(table.rows.map(\.id)).count, table.rows.count)
                    for row in table.rows {
                        let url = ArchiveRepository.site + "\(competition.id)/teams/\(row.id)/roster?season=\(season)"
                        guard catalog[url] != nil else { continue }
                        let players = try await repository.roster(team: row.team, competition: competition.id, season: season)
                        XCTAssertTrue(players.allSatisfy { $0.team.id == row.id && !$0.name.isEmpty && $0.starter == nil })
                        rosterCount += 1
                    }
                }
            }
            for constructors in [false, true] {
                let table = try await repository.racingTable(season: season, constructors: constructors)
                XCTAssertFalse(table.rows.isEmpty)
                XCTAssertNotNil(table.round)
            }
            for race in try await repository.races(season: season) {
                for qualifying in [false, true] {
                    let url = ArchiveRepository.f1 + "\(season)/\(race.round)/\(qualifying ? "qualifying" : "results").json?limit=100"
                    guard catalog[url] != nil else { continue }
                    let rows = try await repository.results(race: race, qualifying: qualifying)
                    XCTAssertFalse(rows.isEmpty)
                    XCTAssertEqual(Set(rows.map(\.id)).count, rows.count)
                    resultCount += 1
                }
            }
        }
        XCTAssertEqual(tableCount, 49)
        XCTAssertGreaterThan(rosterCount, 1000)
        XCTAssertGreaterThan(resultCount, 200)
    }
    func testStoredResponsesPreserveNewerTimestampAndRejectCorruption() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = ResponseStore(root: directory), url = "https://example.com/raw"
        let data = Data("{\"score\":2}".utf8)
        let recent = StoredResponse(url: url, fetched: Date(timeIntervalSince1970: 200), sha256: ArchiveFiles.hash(data), data: data)
        try store.save(recent)
        try store.save(StoredResponse(url: url, fetched: Date(timeIntervalSince1970: 100), sha256: ArchiveFiles.hash(data), data: data))
        XCTAssertEqual(try store.read(url)?.fetched, recent.fetched)
        XCTAssertEqual(try store.read(url)?.data, data)
        XCTAssertEqual(try directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        try Data("broken".utf8).write(to: store.path(url))
        XCTAssertThrowsError(try store.read(url))
    }
    func testResponseIndexIncludesScoreboardsAndDocuments() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = ResponseStore(root: directory)
        let scoreboard = "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard?dates=2026"
        let summary = "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/summary?event=1"
        for url in [scoreboard, summary] {
            let data = Data("{\"score\":2}".utf8)
            try store.save(StoredResponse(url: url, fetched: .now, sha256: ArchiveFiles.hash(data), data: data))
        }
        XCTAssertEqual(Set(store.all("").map(\.url)), Set([scoreboard, summary]))
    }
    func testHomeRefreshUsesOnlySelectedCompetitions() {
        XCTAssertEqual(RefreshCoordinator.homeSelections(nil), ["eng.1", "uefa.champions", "f1"])
        XCTAssertEqual(RefreshCoordinator.homeSelections("eng.1,uefa.europa,f1"), ["eng.1", "uefa.europa", "f1"])
        XCTAssertEqual(RefreshCoordinator.homeSelections("not-a-league"), [])
        XCTAssertEqual(RefreshCoordinator.homeSelections(""), [])
    }
    func testMatchIdentityAndPublishedStartersAreRequired() async throws {
        let fixture = try await ArchiveRepository(root: root, responseRoot: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)).football(competition: "eng.1", season: 2020).items[0]
        let body: [String: Any] = ["header": ["id": fixture.providerID, "competitions": [["competitors": [["id": fixture.home.id], ["id": fixture.away.id]]]]], "rosters": [["team": ["id": fixture.home.id], "roster": [["athlete": ["id": "1", "displayName": "Original Full Name"]]]]]]
        let detail = try DetailParser.match(body, fixture: fixture)
        XCTAssertNil(detail.players[0].starter)
        XCTAssertTrue(detail.statistics.isEmpty)
        XCTAssertTrue(detail.events.isEmpty)
        var invalid = body; invalid["header"] = ["id": "different"]
        XCTAssertThrowsError(try DetailParser.match(invalid, fixture: fixture))
    }
    func testHistoricalResultStatusDoesNotInferRetirementFromZeroPoints() async throws {
        let race = try await ArchiveRepository(root: root, responseRoot: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)).races(season: 2020)[0]
        let drivers = [("one", "Lapped"), ("two", "Did not start"), ("three", "Withdrew"), ("four", "Did not qualify")]
        let entries: [[String: Any]] = drivers.enumerated().map { i, value in ["position": i + 1, "points": "0", "Driver": ["driverId": value.0, "givenName": value.0, "familyName": "Driver"], "status": value.1] }
        let body: [String: Any] = ["MRData": ["total": "4", "RaceTable": ["season": "2020", "round": "1", "Races": [["season": "2020", "round": "1", "Circuit": ["circuitId": race.circuitID], "Results": entries]]]]]
        let rows = try DetailParser.results(body, race: race, qualifying: false)
        XCTAssertEqual(rows.map(\.result), ["被套圈", "未发车", "退出参赛", "未获资格"])
    }
}

extension PortParityTests {
    func testPartialSummaryPreservesVerifiedSectionsAndOriginalBytes() throws {
        let header: [String: Any] = ["id": "one", "competitions": [["competitors": [["id": "home"], ["id": "away"]]]]]
        let full: [String: Any] = ["header": header, "keyEvents": [["id": "goal", "type": ["type": "goal"]]], "rosters": [], "boxscore": ["teams": []]]
        func response(_ object: [String: Any], time: Double) throws -> StoredResponse {
            let data = try JSONSerialization.data(withJSONObject: object)
            return StoredResponse(url: "https://example.com/summary?event=one", fetched: Date(timeIntervalSince1970: time), sha256: ArchiveFiles.hash(data), data: data)
        }
        let original = try response(full, time: 100)
        var previous = original
        for time in 101...110 {
            var partial = try response(["header": header], time: Double(time))
            try partial.preserveMissingSections(from: previous)
            XCTAssertEqual(try partial.body().objects("keyEvents").first?.text("id"), "goal")
            XCTAssertEqual(partial.fallbacks?.first?.data, original.data)
            XCTAssertEqual(partial.fallbacks?.first?.fetched, original.fetched)
            previous = partial
        }
        var cleared = try response(["header": header, "keyEvents": []], time: 111)
        try cleared.preserveMissingSections(from: previous)
        XCTAssertTrue(try cleared.body().objects("keyEvents").isEmpty)
    }
    func testPublishedLineupRequiresUniquePositionAssignment() {
        let team = FootballTeam(id: "team", originalName: "Team", name: "Team", badgePath: nil)
        func player(_ i: Int, _ position: String) -> FootballPlayer { FootballPlayer(id: String(i), name: "Player \(i)", number: String(i), position: position, team: team, goals: nil, assists: nil, starter: true) }
        let players = [player(1, "Goalkeeper")] + (2...5).map { player($0, "Defender") } + (6...8).map { player($0, "Midfielder") } + (9...11).map { player($0, "Forward") }
        XCTAssertEqual(LineupLayout.published(players, formation: "4-3-3")?.map(\.count), [1, 4, 3, 3])
        XCTAssertNil(LineupLayout.published(players, formation: "4-4-2"))
        XCTAssertEqual(LineupLayout.schematic(players, formation: "4-4-2").map(\.count), [1, 4, 4, 2])
        XCTAssertEqual(Set(LineupLayout.schematic(players, formation: "").flatMap { $0 }.map(\.id)).count, 11)
    }
    func testLiveSummaryValidatesRealStatisticsEventsAndPublishedLineups() async throws {
        let repository = ArchiveRepository(root: root, responseRoot: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        let fixtures = try await repository.football(competition: "eng.1", season: 2025)
        let fixture = try XCTUnwrap(fixtures.items.first { $0.providerID == "740596" })
        let detail = try await repository.matchDetail(fixture, refresh: true)
        XCTAssertEqual(detail.statistics.count, 8)
        XCTAssertEqual(detail.players.filter { $0.starter == true }.count, 22)
        XCTAssertFalse(detail.events.isEmpty)
        XCTAssertTrue(detail.events.contains { $0.sourceScore != nil })
        let cached = try await repository.matchDetail(fixture)
        XCTAssertEqual(cached.events.map(\.id), detail.events.map(\.id))
    }
    func testCancelledHTTPDoesNotLeaveAQueuedRequest() async {
        let request = Task { try await NetworkLimiter.shared.fetch("https://api.jolpi.ca/ergast/f1/2020.json?limit=100") }
        request.cancel()
        do { _ = try await request.value; XCTFail("Cancelled request succeeded") } catch { }
    }
}

extension PortParityTests {
    func testCacheMigrationSelectsNewestValidEnvelopeAndKeepsLegacyFallback() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = ResponseStore(root: directory), url = "https://example.com/standings"
        let data = Data("{\"points\":42}".utf8)
        try store.save(StoredResponse(url: url, fetched: Date(timeIntervalSince1970: 100), sha256: ArchiveFiles.hash(data), data: data))
        let legacy = directory.appendingPathComponent(ArchiveFiles.hash(Data(url.utf8)) + ".json")
        let newer = StoredResponse(url: url, fetched: Date(timeIntervalSince1970: 200), sha256: ArchiveFiles.hash(data), data: data)
        try JSONEncoder().encode(newer).write(to: legacy)
        XCTAssertEqual(try store.read(url)?.fetched, newer.fetched)
        try Data("corrupt".utf8).write(to: store.path(url))
        XCTAssertEqual(try store.read(url)?.fetched, newer.fetched)
    }
}

extension PortParityTests {
    func testIncompleteCachedTableAndMissingRosterStatisticsCannotHideArchive() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = ResponseStore(root: directory), files = ArchiveFiles(root: root), catalog = try files.json("sports/catalog.json")
        let tableURL = ArchiveRepository.web + "eng.1/standings?season=2024"
        var table = try files.response(tableURL, catalog: catalog).body
        var children = table.objects("children"), standings = children[0].object("standings")
        standings["entries"] = Array(standings.objects("entries").prefix(19)); children[0]["standings"] = standings; table["children"] = children
        func envelope(_ url: String, _ body: [String: Any]) throws -> StoredResponse {
            let data = try JSONSerialization.data(withJSONObject: body)
            return StoredResponse(url: url, fetched: .now, sha256: ArchiveFiles.hash(data), data: data)
        }
        try store.save(envelope(tableURL, table))
        let rosterURL = ArchiveRepository.site + "eng.1/teams/364/roster?season=2024"
        var roster = try files.response(rosterURL, catalog: catalog).body
        roster["athletes"] = roster.objects("athletes").map { source in var value = source; value["statistics"] = nil; return value }
        try store.save(envelope(rosterURL, roster))
        let repository = ArchiveRepository(root: root, responseRoot: directory)
        let restored = try await repository.tables(competition: "eng.1", season: 2024)
        XCTAssertEqual(restored.flatMap(\.rows).count, 20)
        let liverpool = try XCTUnwrap(restored.flatMap(\.rows).first { $0.id == "364" }?.team)
        let squad = try await repository.roster(team: liverpool, competition: "eng.1", season: 2024)
        XCTAssertEqual(squad.first { $0.name == "Mohamed Salah" }?.goals, 29)
        XCTAssertEqual(squad.first { $0.name == "Mohamed Salah" }?.assists, 18)
    }
}

extension PortParityTests {
    func testHistoricalSprintShootoutKeepsOriginalSessionIdentity() async throws {
        let repository = ArchiveRepository(root: root, responseRoot: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        let races = try await repository.races(season: 2023)
        let files = ArchiveFiles(root: root), catalog = try files.json("sports/catalog.json")
        let raw = try files.response(ArchiveRepository.f1 + "2023.json?limit=100", catalog: catalog).body.object("MRData").object("RaceTable").objects("Races")
        let published = raw.filter { !$0.object("SprintShootout").text("date").isEmpty }
        XCTAssertEqual(published.count, 6)
        for source in published {
            let race = try XCTUnwrap(races.first { $0.round == source.integer("round") })
            let shootout = try XCTUnwrap(race.sessions.first { $0.id == race.id + "-SprintShootout" })
            XCTAssertEqual(shootout.title, "冲刺排位")
            XCTAssertEqual(shootout.sourceDate, source.object("SprintShootout").text("date"))
        }
    }
}

extension PortParityTests {
    func testConcurrentHTTPResponsesKeepTheirOwnSeasonAndURL() async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            for year in 2020...2023 {
                group.addTask {
                    let url = ArchiveRepository.f1 + "\(year)/driverstandings.json?limit=100"
                    let response = try await NetworkLimiter.shared.fetch(url)
                    guard response.url == url, try response.body().object("MRData").object("StandingsTable").integer("season") == year else { throw ArchiveError.invalid("mixed HTTP response") }
                }
            }
            try await group.waitForAll()
        }
    }
}
