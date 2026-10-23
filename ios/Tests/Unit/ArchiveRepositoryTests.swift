import XCTest
@testable import PitPitch

final class ArchiveRepositoryTests: XCTestCase {
    private var root: URL { Bundle.main.resourceURL!.appendingPathComponent("assets") }

    func testAllFootballSeasonsRetain2020AndExactSeasonIdentity() async throws {
        let repository = ArchiveRepository(root: root, responseRoot: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        for year in 2020...2026 {
            for competition in Competition.all {
                let season = try await repository.football(competition: competition.id, season: year)
                XCTAssertFalse(season.items.isEmpty, "\(year)/\(competition.id)")
                XCTAssertTrue(season.items.allSatisfy { $0.season == year && $0.competition == competition.id })
                XCTAssertEqual(Set(season.items.map(\.id)).count, season.items.count)
                XCTAssertTrue(season.items.allSatisfy { !$0.home.name.isEmpty && !$0.away.name.isEmpty })
            }
        }
    }
    func testF1HistoryKeepsOnePracticeAndSourceRaceIdentity() async throws {
        let repository = ArchiveRepository(root: root, responseRoot: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        for year in 2020...2026 {
            let races = try await repository.races(season: year)
            XCTAssertFalse(races.isEmpty)
            XCTAssertEqual(Set(races.map(\.round)).count, races.count)
            for race in races {
                XCTAssertEqual(race.season, year)
                XCTAssertEqual(race.id, "f1-\(year)-\(race.round)")
                XCTAssertEqual(race.sessions.filter { $0.title == "正赛" }.map(\.id), [race.id])
                XCTAssertLessThanOrEqual(race.sessions.filter { $0.title == "练习" }.count, 1)
                XCTAssertNotEqual(race.name, race.originalName)
                XCTAssertNotNil(SportsDate.parse(race.fetchedUTC))
            }
        }
    }
    func testCompletedAndPendingScoresUseProviderState() throws {
        let raw = try fixture(status: "STATUS_SCHEDULED", state: "pre", completed: false)
        XCTAssertNil(raw.score)
        XCTAssertEqual(raw.status, "待赛")
        XCTAssertEqual(try fixture(status: "STATUS_SUSPENDED", state: "in", completed: false).status, "中止")
        XCTAssertNil(try fixture(status: "STATUS_SUSPENDED", state: "in", completed: false).score)
        XCTAssertEqual(try fixture(status: "STATUS_FULL_TIME", state: "post", completed: true).score, "2 : 1")
        XCTAssertNil(try fixture(status: "STATUS_SCHEDULED", state: "pre", completed: false, timeValid: false).kickoff)
    }
    func testUnknownParticipantsAndVenueHaveNeutralFallbacks() throws {
        let fixture = try fixture(status: "STATUS_SCHEDULED", state: "pre", completed: false)
        XCTAssertEqual(fixture.away.name, "待定")
        XCTAssertEqual(fixture.venue, "场地待定")
        XCTAssertNil(fixture.round)
    }
    func testResourceTraversalAndCorruptResponseAreRejected() throws {
        let files = ArchiveFiles(root: root)
        XCTAssertThrowsError(try files.data("../sports/catalog.json"))
        let catalog: [String: Any] = ["test": ["file": "catalog.json", "sha256": "incorrect", "fetchedUtc": "2026-10-10T00:00:00Z"]]
        XCTAssertThrowsError(try files.response("test", catalog: catalog))
    }
    func testPackagedEnvelopeRetainsOriginalBytesAndProviderTime() throws {
        let files = ArchiveFiles(root: root), catalog = try files.json("sports/catalog.json")
        let url = ArchiveRepository.web + "eng.1/standings?season=2020"
        let source = try XCTUnwrap(catalog[url] as? [String: Any])
        let response = try files.storedResponse(url, catalog: catalog)
        let fetchedText = try XCTUnwrap(source["fetchedUtc"] as? String)
        let sourceTime = try XCTUnwrap(SportsDate.parse(fetchedText))
        XCTAssertEqual(response.url, url)
        XCTAssertEqual(response.sha256, source["sha256"] as? String)
        XCTAssertEqual(response.fetched, sourceTime)
        XCTAssertEqual(ArchiveFiles.hash(response.data), response.sha256)
        XCTAssertEqual(try files.response(url, catalog: catalog).fetched, fetchedText)
    }
    func testHistoricalSelectionDoesNotReplaceCurrentCachedSeason() async throws {
        let repository = ArchiveRepository(root: root, responseRoot: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        let current = try await repository.football(competition: "eng.1", season: 2026)
        _ = try await repository.football(competition: "eng.1", season: 2020)
        let restored = try await repository.football(competition: "eng.1", season: 2026)
        XCTAssertEqual(current.items, restored.items)
    }
    private func fixture(status: String, state: String, completed: Bool, timeValid: Bool = true) throws -> FootballFixture {
        let game: [String: Any] = ["date": "2026-01-01T12:00Z", "timeValid": timeValid,
                                  "status": ["type": ["name": status, "state": state, "completed": completed]],
                                  "competitors": [["homeAway": "home", "score": "2", "team": ["id": "360", "displayName": "Manchester United"]],
                                                  ["homeAway": "away", "score": "1", "team": ["id": "", "displayName": ""]]]]
        let body: [String: Any] = ["events": [["id": "123", "season": ["year": 2026], "competitions": [game]]]]
        return try XCTUnwrap(FootballParser.parse(body, competition: "eng.1", season: 2026, fetched: "2026-01-01T00:00:00Z", names: [:], badges: [:], rounds: [:]).first)
    }
}
