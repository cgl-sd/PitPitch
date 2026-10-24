import Foundation

extension ArchiveRepository {
    static let site = "https://site.api.espn.com/apis/site/v2/sports/soccer/"
    static let web = "https://site.web.api.espn.com/apis/v2/sports/soccer/"
    static let f1 = "https://api.jolpi.ca/ergast/f1/"

    func document<T: Sendable>(_ url: String, refresh: Bool, parse: ([String: Any]) throws -> T) async throws -> T {
        try prepare()
        if refresh {
            var response = try await NetworkLimiter.shared.fetch(url)
            try Task.checkCancellation()
            if url.contains("/summary?"),
               let previous = (try? responses.read(url)) ?? (try? files.storedResponse(url, catalog: catalog)) {
                try response.preserveMissingSections(from: previous)
            }
            let value = try parsedDocument(url, response: response, parse: parse)
            // Validate before saving. Never replace a newer valid response with an older fetch.
            if let old = try? responses.read(url), old.fetched > response.fetched { return try parsedDocument(url, response: old, parse: parse) }
            try Task.checkCancellation()
            try responses.save(response)
            if !url.contains("/summary?") { latestAcquisition = max(latestAcquisition ?? .distantPast, response.fetched) }
            return value
        }
        let bundled = try? files.response(url, catalog: catalog)
        if let saved = try? responses.read(url),
           let value = try? parsedDocument(url, response: saved, parse: parse) { return value }
        if let bundled {
            let signature = catalog.object(url).text("sha256")
            if memoizable(url), let cached = parsedDocuments[url], cached.hash == signature, let value = cached.value as? T { return value }
            let value = try parse(bundled.body)
            retainParsed(value, url: url, hash: signature); return value
        }
        throw ArchiveError.missing(url)
    }
    func memoizable(_ url: String) -> Bool { url.contains("/roster?") || url.contains("standings") }
    func parsedDocument<T: Sendable>(_ url: String, response: StoredResponse, parse: ([String: Any]) throws -> T) throws -> T {
        let signature = response.sha256 + (response.fallbacks ?? []).map(\.sha256).joined()
        if memoizable(url), let cached = parsedDocuments[url], cached.hash == signature, let value = cached.value as? T { return value }
        let value = try parse(response.body())
        retainParsed(value, url: url, hash: signature); return value
    }
    func retainParsed<T: Sendable>(_ value: T, url: String, hash: String) {
        guard memoizable(url) else { return }
        parsedDocuments[url] = (hash, value); parsedOrder.removeAll { $0 == url }; parsedOrder.append(url)
        while parsedOrder.count > 256 { parsedDocuments[parsedOrder.removeFirst()] = nil }
    }
    func tables(competition: String, season: Int, refresh: Bool = false) async throws -> [FootballTable] {
        try prepare()
        return try await document(Self.web + "\(competition)/standings?season=\(season)", refresh: refresh) {
            try verifiedTables($0, competition: competition, season: season)
        }
    }
    func verifiedTables(_ body: [String: Any], competition: String, season: Int) throws -> [FootballTable] {
        let providerIDs = ["eng.1": "23", "esp.1": "15", "ita.1": "12", "ger.1": "10", "fra.1": "9", "uefa.champions": "2", "uefa.europa": "2310"]
        let historicEuropeanNames = ["uefa.champions": "UEFA Champions League", "uefa.europa": "UEFA Europa League"]
        let legacyGroups = season < 2024 && body.text("id") == "8675309" && historicEuropeanNames[competition] == body.text("name")
        guard body.text("id") == providerIDs[competition] || legacyGroups else { throw ArchiveError.invalid("standings league") }
        let tables = try DetailParser.tables(body, season: season, names: names, badges: badges)
        let url = Self.web + "\(competition)/standings?season=\(season)"
        if tableSizes[url] == nil, let original = try? files.response(url, catalog: catalog), let baseline = try? DetailParser.tables(original.body, season: season, names: names, badges: badges) { tableSizes[url] = baseline.flatMap(\.rows).count }
        if let count = tableSizes[url] { guard tables.flatMap(\.rows).count >= count else { throw ArchiveError.invalid("partial standings") } }
        guard let supplements = try? files.json("football-standing-notes.json").object(url),
              supplements.text("archiveSha256") == catalog.object(url).text("sha256"),
              let archived = try? files.response(url, catalog: catalog), NSDictionary(dictionary: archived.body).isEqual(to: body) else { return tables }
        return tables.map { table in
            FootballTable(id: table.id, name: table.name, rows: table.rows.map { source in
                var row = source
                let note = supplements.object("groups").object(table.id).text(row.id)
                if !note.isEmpty { row.note = note }; return row
            })
        }
    }
    func roster(team: FootballTeam, competition: String, season: Int, refresh: Bool = false) async throws -> [FootballPlayer] {
        try prepare()
        let url = Self.site + "\(competition)/teams/\(team.id)/roster?season=\(season)"
        if rosterBaselines[url] == nil {
            let bundled = (try? files.response(url, catalog: catalog)).flatMap { try? DetailParser.roster($0.body, team: team, season: season) }
            rosterBaselines[url] = ((bundled ?? []).contains { $0.goals != nil || $0.assists != nil }, bundled?.isEmpty == false)
        }
        let prior = refresh ? (try? await roster(team: team, competition: competition, season: season)) : nil
        let hadStatistics = rosterBaselines[url]?.statistics == true || prior?.contains { $0.goals != nil || $0.assists != nil } == true
        let hadPlayers = rosterBaselines[url]?.players == true || prior?.isEmpty == false
        return try await document(url, refresh: refresh) {
            let values = try DetailParser.roster($0, team: team, season: season)
            guard (!hadPlayers || !values.isEmpty), (!hadStatistics || values.contains { $0.goals != nil || $0.assists != nil }), (!refresh || !values.isEmpty) else { throw ArchiveError.invalid("partial roster") }
            return values
        }
    }

    func leaders(competition: String, season: Int, refresh: Bool = false) async throws -> [FootballPlayer] {
        let tables = try await tables(competition: competition, season: season)
        let teams = Dictionary(tables.flatMap(\.rows).map { ($0.team.id, $0.team) }, uniquingKeysWith: { a, _ in a }).values.sorted { $0.id < $1.id }
        var players: [FootballPlayer] = []
        // A shared four-request gate covers all leagues; one roster batch has a 20s deadline.
        players = await withTaskGroup(of: [FootballPlayer]?.self) { group in
            for team in teams {
                group.addTask {
                    if refresh, let fresh = try? await self.roster(team: team, competition: competition, season: season, refresh: true) { return fresh }
                    return (try? await self.roster(team: team, competition: competition, season: season)) ?? []
                }
            }
            group.addTask { try? await Task.sleep(for: .seconds(20)); return nil }
            var result: [FootballPlayer] = [], completed = 0
            for await rows in group {
                guard let rows else { group.cancelAll(); break }
                result += rows; completed += 1
                if completed == teams.count { group.cancelAll(); break }
            }
            return result
        }
        // Fill cancelled/unavailable downloads from verified local snapshots.
        let loadedTeams = Set(players.map { $0.team.id })
        for team in teams where !loadedTeams.contains(team.id) {
            players += (try? await roster(team: team, competition: competition, season: season)) ?? []
        }
        guard !players.isEmpty else { throw ArchiveError.missing("players") }
        return players
    }
    func matchDetail(_ fixture: FootballFixture, refresh: Bool = false) async throws -> MatchDetail {
        try await document(Self.site + "\(fixture.competition)/summary?event=\(fixture.providerID)", refresh: refresh) {
            try DetailParser.match($0, fixture: fixture)
        }
    }
    func racingTable(season: Int, constructors: Bool, refresh: Bool = false) async throws -> RacingTable {
        try await document(Self.f1 + "\(season)/\(constructors ? "constructorstandings" : "driverstandings").json?limit=100", refresh: refresh) {
            try DetailParser.racingTable($0, season: season, constructors: constructors)
        }
    }
    func results(race: RacingRound, qualifying: Bool, refresh: Bool = false) async throws -> [RacingResult] {
        try await document(Self.f1 + "\(race.season)/\(race.round)/\(qualifying ? "qualifying" : "results").json?limit=100", refresh: refresh) {
            try DetailParser.results($0, race: race, qualifying: qualifying)
        }
    }
    func mediaPath(season: Int, kind: String, id: String) throws -> String? {
        try prepare()
        let file = media.object("\(kind)/\(season)/\(id)").text("file")
        return file.isEmpty || file.contains("/") ? nil : "f1-media/" + file
    }
    func teamColor(_ id: String) -> String? {
        (try? files.json("football-team-colors.json"))?.object(String((Int(id) ?? -1) + 1_000_000)).text("color")
    }
    func refreshFootball(competition: String, season: Int) async throws {
        var archived = (try? football(competition: competition, season: season).items) ?? []
        let current = SportsDate.currentFootballSeason()
        guard season == current else { return }
        if archived.isEmpty {
            for year in [season, season + 1] {
                let _: [FootballFixture] = try await document(Self.site + "\(competition)/scoreboard?dates=\(year)&limit=1000", refresh: true) {
                    let rows = try FootballParser.parse($0, competition: competition, season: season, fetched: Date.now.ISO8601Format(), names: names, badges: badges, rounds: (try? rounds(season)) ?? [:])
                    guard !rows.isEmpty else { throw ArchiveError.missing("unpublished season") }; return rows
                }
                footballCache["\(competition)/\(season)"] = nil
                archived = (try? football(competition: competition, season: season).items) ?? archived
            }
        }
        var merged = Dictionary(archived.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        // Exact dates only: next archived round plus unresolved records after kickoff.
        let home = HomeRules.cards(archived).first?.fixtures ?? []
        let round = home.first?.round
        let selected = archived.filter {
            ($0.round == round && round != nil) ||
            ($0.kickoff.map { $0 < .now } == true && !["完场", "取消", "终止"].contains($0.status))
        }
        let formatter = DateFormatter(); formatter.dateFormat = "yyyyMMdd"; formatter.timeZone = TimeZone(secondsFromGMT: 0)
        let dates = Set(selected.compactMap(\.kickoff).map { formatter.string(from: $0) }).sorted()
        let allowed = Set(selected.map(\.id))
        for start in stride(from: 0, to: dates.count, by: 4) {
            let batch = Array(dates[start..<min(start + 4, dates.count)])
            await withTaskGroup(of: StoredResponse?.self) { group in
                for date in batch {
                    group.addTask { try? await NetworkLimiter.shared.fetch(Self.site + "\(competition)/scoreboard?dates=\(date)&limit=100") }
                }
                for await response in group {
                    guard let response, !Task.isCancelled,
                          let parsed = try? FootballParser.parse(response.body(), competition: competition, season: season, fetched: response.fetched.ISO8601Format(), names: names, badges: badges, rounds: rounds(season)) else { continue }
                    let updates = parsed.filter { match in
                        guard allowed.contains(match.id), let previous = merged[match.id], previous.status != "完场" else { return false }
                        return (previous.home.name == "待定" || previous.home.id == match.home.id) && (previous.away.name == "待定" || previous.away.id == match.away.id)
                    }
                    guard !updates.isEmpty else { continue }
                    do {
                        var scoped = response; scoped.allowedIDs = updates.map(\.id)
                        try responses.save(scoped)
                        latestAcquisition = max(latestAcquisition ?? .distantPast, response.fetched)
                        for value in updates where (SportsDate.parse(merged[value.id]?.fetchedUTC ?? "") ?? .distantPast) <= response.fetched { merged[value.id] = value }
                        footballCache["\(competition)/\(season)"] = ArchivedSeason(items: merged.values.sorted { ($0.kickoff ?? .distantFuture) < ($1.kickoff ?? .distantFuture) }, errors: [])
                    } catch { }
                }
            }
        }
        try Task.checkCancellation()
    }
}

extension ArchiveRepository {
    func refreshHomeRace(season: Int) async throws -> [RacingRound] {
        let now = Date.now
        let current = try races(season: season)
        guard let target = current
            .filter({ ($0.date ?? .distantPast) > now })
            .min(by: { ($0.date ?? .distantFuture) < ($1.date ?? .distantFuture) }) else { return current }
        let updated: [RacingRound] = try await document(
            Self.f1 + "\(season)/\(target.round).json?limit=100",
            refresh: true
        ) {
            let races = try F1Parser.parse($0, season: season, fetched: Date.now.ISO8601Format())
            guard races.count == 1,
                  let race = races.first,
                  race.round == target.round,
                  race.circuitID == target.circuitID else { throw ArchiveError.invalid("home race") }
            return [race]
        }
        var merged = updated[0]
        merged.photoPath = target.photoPath
        merged.layoutFile = target.layoutFile
        let refreshed = current.map { $0.round == target.round ? merged : $0 }.sorted { $0.round < $1.round }
        racingCache[season] = refreshed
        return refreshed
    }
    func refreshRaces(season: Int) async throws {
        let _: [RacingRound] = try await document(Self.f1 + "\(season).json?limit=100", refresh: true) {
            try F1Parser.parse($0, season: season, fetched: Date.now.ISO8601Format())
        }
        racingCache[season] = nil
        if let weekend = try races(season: season).first(where: { !$0.hasResults && ($0.date ?? .distantPast) >= Calendar.current.startOfDay(for: .now) }) {
            async let raceResults = results(race: weekend, qualifying: false, refresh: true)
            async let qualifying = results(race: weekend, qualifying: true, refresh: true)
            _ = try? await raceResults; _ = try? await qualifying
            racingCache[season] = nil
        }
    }
    func bestResponse(_ url: String) throws -> (body: [String: Any], fetched: String) {
        let bundled = try? files.response(url, catalog: catalog)
        if let cached = try? responses.read(url) {
            return (try cached.body(), cached.fetched.ISO8601Format())
        }
        guard let bundled else { throw ArchiveError.missing(url) }; return bundled
    }
}
