import Foundation

actor ArchiveRepository {
    let files: ArchiveFiles
    let responses: ResponseStore
    var catalog: [String: Any] = [:]
    var names: [String: Any] = [:]
    var badges: [String: String] = [:]
    var footballCache: [String: ArchivedSeason<FootballFixture>] = [:]
    var racingCache: [Int: [RacingRound]] = [:]
    var roundCache: [Int: [String: [String: Any]]] = [:]
    var media: [String: Any] = [:]
    var prepared = false
    var latestAcquisition: Date?
    var parsedDocuments: [String: (hash: String, value: any Sendable)] = [:]
    var parsedOrder: [String] = []
    var rosterBaselines: [String: (statistics: Bool, players: Bool)] = [:]
    var tableSizes: [String: Int] = [:]
    init(root: URL, responseRoot: URL? = nil) { files = ArchiveFiles(root: root); responses = ResponseStore(root: responseRoot) }

    func prepare() throws {
        if prepared { return }
        responses.organizeLegacy()
        catalog = try files.json("sports/catalog.json")
        media = try files.json("f1-media/catalog.json")
        names = try files.json("football-chinese-names.json")
        let badgeIndex = try files.json("team-badges/catalog.json")
        var paths: [String: Set<String>] = [:]
        for value in badgeIndex.values {
            guard let row = value as? [String: Any], !row.text("file").contains("/") else { continue }
            for id in row.object("teams").keys { paths[id, default: []].insert("team-badges/" + row.text("file")) }
        }
        badges = paths.compactMapValues { $0.count == 1 ? $0.first : nil }
        prepared = true
    }
    func rounds(_ season: Int) throws -> [String: [String: Any]] {
        if let cached = roundCache[season] { return cached }
        let data = try files.data("rounds/\(season).json")
        let rows = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] ?? []
        var result: [String: [String: Any]] = [:]
        for row in rows where !row.text("id").isEmpty {
            guard result[row.text("id")] == nil else { throw ArchiveError.invalid("Duplicate round") }
            result[row.text("id")] = row
        }
        roundCache[season] = result
        return result
    }
    func football(competition: String, season: Int) throws -> ArchivedSeason<FootballFixture> {
        try Task.checkCancellation()
        try prepare()
        let key = "\(competition)/\(season)"
        if let cached = footballCache[key] { return cached }
        guard season >= 2020, Competition.all.contains(where: { $0.id == competition }) else { throw ArchiveError.invalid(key) }
        let supplements = try rounds(season)
        var fixtures: [String: FootballFixture] = [:]
        var errors: [String] = []
        for year in [season, season + 1] {
            let url = "https://site.api.espn.com/apis/site/v2/sports/soccer/\(competition)/scoreboard?dates=\(year)&limit=1000"
            do {
                let response = try bestResponse(url)
                for match in try FootballParser.parse(response.body, competition: competition, season: season,
                                                      fetched: response.fetched, names: names, badges: badges, rounds: supplements) {
                    if let previous = fixtures[match.id],
                       (SportsDate.parse(previous.fetchedUTC) ?? .distantPast) > (SportsDate.parse(match.fetchedUTC) ?? .distantPast) { continue }
                    fixtures[match.id] = match
                }
            } catch { errors.append(error.localizedDescription) }
            try Task.checkCancellation()
        }
        // Replay only the originally permitted match identities; an upgrade cannot expand
        // an older refresh into unrelated historical fixtures.
        for saved in responses.all(Self.site + competition + "/scoreboard?dates=").sorted(by: { $0.fetched < $1.fetched }) {
            guard let allowed = saved.allowedIDs,
                  let parsed = try? FootballParser.parse(saved.body(), competition: competition, season: season, fetched: saved.fetched.ISO8601Format(), names: names, badges: badges, rounds: supplements) else { continue }
            for match in parsed where allowed.contains(match.id) {
                guard let old = fixtures[match.id], old.status != "完场", (SportsDate.parse(old.fetchedUTC) ?? .distantPast) <= saved.fetched else { continue }
                fixtures[match.id] = match
            }
        }
        if fixtures.isEmpty && !errors.isEmpty { throw ArchiveError.missing(key) }
        let result = ArchivedSeason(items: fixtures.values.sorted {
            if $0.sourceDate != $1.sourceDate { return $0.sourceDate < $1.sourceDate }
            if $0.kickoff != $1.kickoff { return ($0.kickoff ?? .distantFuture) < ($1.kickoff ?? .distantFuture) }
            return $0.id < $1.id
        }, errors: errors)
        footballCache[key] = result
        return result
    }
    func races(season: Int) throws -> [RacingRound] {
        try Task.checkCancellation()
        try prepare()
        if let cached = racingCache[season] { return cached }
        guard season >= 2020 else { throw ArchiveError.invalid("F1 season") }
        let response = try bestResponse("https://api.jolpi.ca/ergast/f1/\(season).json?limit=100")
        let races = try F1Parser.parse(response.body, season: season, fetched: response.fetched).map { source in
            var race = source
            let resultURL = Self.f1 + "\(season)/\(race.round)/results.json?limit=100"
            if let result = try? bestResponse(resultURL), let rows = try? DetailParser.results(result.body, race: race, qualifying: false) { race.hasResults = !rows.isEmpty }
            let photo = media.object("circuit/\(race.circuitID)").text("file")
            race.photoPath = photo.isEmpty ? nil : "f1-media/" + photo
            let layout = media.object("layout/\(season)/\(race.id)").text("file")
            race.layoutFile = layout.isEmpty ? nil : layout.replacingOccurrences(of: ".svg", with: ".png")
            return race
        }
        try Task.checkCancellation()
        racingCache[season] = races
        return races
    }
}

extension ArchiveRepository {
    /// Resolve the saved team without parsing and sorting every competition's full season.
    func team(id: String) throws -> FootballTeam? {
        try prepare()
        for season in footballCache.values {
            for fixture in season.items {
                if fixture.home.id == id { return fixture.home }
                if fixture.away.id == id { return fixture.away }
            }
        }
        guard let number = Int(id) else { return nil }
        let entry = names.object("teams").object(String(number + 1_000_000))
        guard let original = (entry["originalNames"] as? [String])?.first, !original.isEmpty else { return nil }
        let name = entry.text("name")
        return FootballTeam(id: id, originalName: original, name: name.isEmpty ? original : name, badgePath: badges[id])
    }

    func teams(season: Int) throws -> [FootballTeam] {
        var teams: [String: FootballTeam] = [:]
        for competition in Competition.all {
            guard let archived = try? football(competition: competition.id, season: season) else { continue }
            for fixture in archived.items {
                for team in [fixture.home, fixture.away] where Int(team.id) != nil { teams[team.id] = team }
            }
        }
        return teams.values.sorted {
            func key(_ value: String) -> String { (value.applyingTransform(.toLatin, reverse: false) ?? value).folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX")) }
            return key($0.name) < key($1.name)
        }
    }
    func teamFixtures(id: String, season: Int) throws -> [FootballFixture] {
        var result: [FootballFixture] = []
        for competition in Competition.all {
            guard let archived = try? football(competition: competition.id, season: season) else { continue }
            result += archived.items.filter { $0.home.id == id || $0.away.id == id }
        }
        return result.sorted { ($0.kickoff ?? .distantFuture) < ($1.kickoff ?? .distantFuture) }
    }
}
