import Foundation

enum DetailParser {
    static func tables(_ body: [String: Any], season: Int, names: [String: Any], badges: [String: String]) throws -> [FootballTable] {
        guard body.object("season").integer("year") == season else { throw ArchiveError.invalid("standings season") }
        func walk(_ group: [String: Any]) throws -> [FootballTable] {
            let entries = group.object("standings").objects("entries")
            var result: [FootballTable] = []
            if !entries.isEmpty {
                var seen = Set<String>()
                let rows = try entries.map { entry -> FootballStanding in
                    let team = FootballParser.team(entry.object("team"), fallback: "", names: names, badges: badges)
                    let stats = Dictionary(entry.objects("stats").map { ($0.text("name"), $0.text("displayValue").isEmpty ? $0.text("value") : $0.text("displayValue")) }, uniquingKeysWith: { a, _ in a })
                    guard !team.id.isEmpty, seen.insert(team.id).inserted, let rank = Int(stats["rank"] ?? ""), rank > 0,
                          !(stats["points"] ?? "").isEmpty else { throw ArchiveError.invalid("standings entry") }
                    return FootballStanding(team: team, rank: rank, played: stats["gamesPlayed"] ?? "—", won: stats["wins"] ?? "—", drawn: stats["ties"] ?? "—", lost: stats["losses"] ?? "—", goalsFor: stats["pointsFor"] ?? "—", goalsAgainst: stats["pointsAgainst"] ?? "—", points: stats["points"] ?? "—", note: entry.object("note").text("description"))
                }.sorted { $0.rank < $1.rank }
                result.append(FootballTable(id: group.text("id"), name: group.text("name"), rows: rows))
            }
            for child in group.objects("children") { result += try walk(child) }
            return result
        }
        let tables = try walk(body)
        guard !tables.isEmpty else { throw ArchiveError.invalid("standings") }
        return tables
    }
    static func roster(_ body: [String: Any], team: FootballTeam, season: Int) throws -> [FootballPlayer] {
        guard body.object("season").integer("year") == season, body["athletes"] is [[String: Any]],
              body.object("team").text("id").isEmpty || body.object("team").text("id") == team.id else { throw ArchiveError.invalid("roster identity") }
        var seen = Set<String>()
        return try body.objects("athletes").map { athlete in
            let id = athlete.text("id"), name = athlete.text("displayName")
            guard !id.isEmpty, !name.isEmpty, seen.insert(id).inserted else { throw ArchiveError.invalid("athlete") }
            var stats: [String: Double] = [:]
            for category in athlete.object("statistics").object("splits").objects("categories") {
                for stat in category.objects("stats") { stats[stat.text("name")] = Double(stat.text("value")) }
            }
            return FootballPlayer(id: id, name: name, number: athlete.text("jersey"), position: athlete.object("position").text("name"), team: team, goals: stats["totalGoals"], assists: stats["goalAssists"], starter: nil)
        }
    }
    static func match(_ body: [String: Any], fixture: FootballFixture) throws -> MatchDetail {
        let header = body.object("header")
        guard header.text("id") == fixture.providerID,
              (header.object("season")["year"] == nil || header.object("season").integer("year") == fixture.season),
              (header.object("league").text("slug").isEmpty || header.object("league").text("slug") == fixture.competition),
              Set(header.objects("competitions").first?.objects("competitors").map { $0.text("id") } ?? []) == Set([fixture.home.id, fixture.away.id]) else { throw ArchiveError.invalid("match identity") }
        var stats: [String: [String: String]] = [:]
        for box in body.object("boxscore").objects("teams") {
            let id = box.object("team").text("id")
            guard [fixture.home.id, fixture.away.id].contains(id) else { throw ArchiveError.invalid("statistics team") }
            stats[id] = Dictionary(box.objects("statistics").map { ($0.text("name"), $0.text("displayValue")) }, uniquingKeysWith: { a, _ in a })
        }
        let keys = [("possessionPct", "控球率"), ("totalShots", "射门"), ("shotsOnTarget", "射正"), ("wonCorners", "角球"), ("foulsCommitted", "犯规"), ("yellowCards", "黄牌"), ("redCards", "红牌"), ("penaltyKickGoals", "点球进球")]
        let statistics = keys.compactMap { key, title -> MatchStatistic? in
            let home = stats[fixture.home.id]?[key], away = stats[fixture.away.id]?[key]
            guard home != nil || away != nil else { return nil }
            return MatchStatistic(key: key, title: title, home: home ?? "—", away: away ?? "—")
        }
        var formations: [String: String] = [:], players: [FootballPlayer] = []
        for roster in body.objects("rosters") {
            let id = roster.object("team").text("id")
            guard let team = [fixture.home, fixture.away].first(where: { $0.id == id }) else { throw ArchiveError.invalid("lineup team") }
            formations[id] = roster.text("formation")
            var seen = Set<String>()
            for entry in roster.objects("roster") {
                let athlete = entry.object("athlete"), playerID = athlete.text("id")
                guard !playerID.isEmpty, seen.insert(playerID).inserted, !athlete.text("displayName").isEmpty else { throw ArchiveError.invalid("lineup player") }
                players.append(FootballPlayer(id: playerID, name: athlete.text("displayName"), number: entry.text("jersey"), position: entry.object("position").text("displayName"), team: team, goals: nil, assists: nil, starter: entry["starter"] as? Bool))
            }
        }
        let competitors = header.objects("competitions").first?.objects("competitors") ?? []
        func aliases(_ id: String) -> [String] {
            let team = competitors.first { $0.text("id") == id }?.object("team") ?? [:]
            return ["displayName", "shortDisplayName", "name"].map { team.text($0) }.filter { !$0.isEmpty }
        }
        var seen = Set<String>()
        let events = try body.objects("keyEvents").map { entry -> MatchEvent in
            let id = entry.text("id"), teamID = entry.object("team").text("id")
            guard !id.isEmpty, seen.insert(id).inserted, teamID.isEmpty || [fixture.home.id, fixture.away.id].contains(teamID) else { throw ArchiveError.invalid("event identity") }
            let clock = entry.object("clock"), period = entry.object("period").integer("number")
            let seconds = Double(clock.text("value"))
            guard seconds.map({ $0.isFinite && $0 >= 0 }) ?? true, period.map({ $0 > 0 }) ?? true else { throw ArchiveError.invalid("event time") }
            let text = entry.text("text").isEmpty ? (entry.text("shortText").isEmpty ? entry.object("type").text("text") : entry.text("shortText")) : entry.text("text")
            var score: String?
            for home in aliases(fixture.home.id) {
                for away in aliases(fixture.away.id) {
                    let pattern = NSRegularExpression.escapedPattern(for: home) + #" (\d+), "# + NSRegularExpression.escapedPattern(for: away) + #" (\d+)(?:[.\s]|$)"#
                    if let regex = try? NSRegularExpression(pattern: pattern), let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)), let h = Range(match.range(at: 1), in: text), let a = Range(match.range(at: 2), in: text) { score = "\(text[h]) : \(text[a])" }
                }
            }
            return MatchEvent(id: id, type: entry.object("type").text("type"), clock: clock.text("displayValue"), text: text, player: entry.objects("participants").map { $0.object("athlete").text("displayName") }.filter { !$0.isEmpty }.joined(separator: " · "), teamID: teamID.isEmpty ? nil : teamID, period: period, sourceScore: score, seconds: seconds)
        }
        return MatchDetail(statistics: statistics, events: events.sorted { if $0.period != $1.period { return ($0.period ?? .max) < ($1.period ?? .max) }; return ($0.seconds ?? .infinity) < ($1.seconds ?? .infinity) }, players: players, formations: formations)
    }
    static func racingTable(_ body: [String: Any], season: Int, constructors: Bool) throws -> RacingTable {
        let data = body.object("MRData"), table = data.object("StandingsTable"), lists = table.objects("StandingsLists")
        guard table.integer("season") == season, lists.count <= 1 else { throw ArchiveError.invalid("F1 standings identity") }
        guard let list = lists.first else {
            guard data.integer("total") == 0 else { throw ArchiveError.invalid("F1 standings count") }
            return RacingTable(rows: [], round: nil)
        }
        guard list.integer("season") == season, let round = list.integer("round"), round > 0, (table["round"] == nil || table.integer("round") == round) else { throw ArchiveError.invalid("F1 standings season") }
        let entries = list.objects(constructors ? "ConstructorStandings" : "DriverStandings")
        guard data.integer("total") == entries.count else { throw ArchiveError.invalid("F1 standings completeness") }
        var seen = Set<String>(), positions = Set<Int>()
        let rows = try entries.map { entry -> RacingStanding in
            let person = entry.object("Driver"), team = constructors ? entry.object("Constructor") : (entry.objects("Constructors").count == 1 ? entry.objects("Constructors")[0] : [:])
            let id = constructors ? team.text("constructorId") : person.text("driverId")
            guard !id.isEmpty, seen.insert(id).inserted, let rank = entry.integer("position"), rank > 0, positions.insert(rank).inserted, Double(entry.text("points")) != nil else { throw ArchiveError.invalid("F1 standing") }
            return RacingStanding(id: id, name: constructors ? team.text("name").replacingOccurrences(of: " F1 Team", with: "") : person.text("givenName") + " " + person.text("familyName"), constructorID: team.text("constructorId"), constructor: constructors ? team.text("name") : entry.objects("Constructors").map { $0.text("name") }.filter { !$0.isEmpty }.joined(separator: " / "), points: entry.text("points"), rank: rank)
        }
        return RacingTable(rows: rows.sorted { $0.rank < $1.rank }, round: round)
    }
    static func results(_ body: [String: Any], race: RacingRound, qualifying: Bool) throws -> [RacingResult] {
        let data = body.object("MRData"), table = data.object("RaceTable"), races = table.objects("Races")
        guard table.integer("season") == race.season, (table["round"] == nil || table.integer("round") == race.round), races.count <= 1 else { throw ArchiveError.invalid("F1 result identity") }
        guard let result = races.first else {
            guard data.integer("total") == 0 else { throw ArchiveError.invalid("F1 results") }; return []
        }
        guard result.integer("season") == race.season, result.integer("round") == race.round, result.object("Circuit").text("circuitId") == race.circuitID else { throw ArchiveError.invalid("F1 circuit identity") }
        let entries = result.objects(qualifying ? "QualifyingResults" : "Results")
        guard data["total"] == nil || data.integer("total") == entries.count else { throw ArchiveError.invalid("F1 results completeness") }
        var seen = Set<String>()
        return try entries.map { entry in
            let driver = entry.object("Driver"), team = entry.object("Constructor"), id = driver.text("driverId")
            guard !id.isEmpty, seen.insert(id).inserted, let rank = entry.integer("position"), rank > 0 else { throw ArchiveError.invalid("F1 result") }
            let time = entry.object("Time").text("time"), status = entry.text("status")
            let label: String
            if qualifying { label = [entry.text("Q3"), entry.text("Q2"), entry.text("Q1")].first { !$0.isEmpty } ?? "—" }
            else if !time.isEmpty { label = time }
            else { label = ["Did not start": "未发车", "Withdrew": "退出参赛", "Did not qualify": "未获资格", "Finished": "完赛"][status] ?? (status.contains("Lap") || status == "Lapped" ? "被套圈" : status) }
            return RacingResult(id: id, name: driver.text("givenName") + " " + driver.text("familyName"), constructorID: team.text("constructorId"), constructor: team.text("name"), points: entry.text("points"), result: label.isEmpty ? "—" : label, q1: entry.text("Q1"), q2: entry.text("Q2"), q3: entry.text("Q3"), rank: rank)
        }
    }
}
