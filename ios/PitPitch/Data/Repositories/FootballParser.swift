import Foundation

enum FootballParser {
    static func parse(_ body: [String: Any], competition: String, season: Int, fetched: String,
                      names: [String: Any], badges: [String: String], rounds: [String: [String: Any]]) throws -> [FootballFixture] {
        guard body["events"] is [[String: Any]] else { throw ArchiveError.invalid(competition) }
        let suppliedLeagues = body.objects("leagues").map { $0.text("slug") }.filter { !$0.isEmpty }
        guard suppliedLeagues.isEmpty || suppliedLeagues.contains(competition) else { throw ArchiveError.invalid("scoreboard league") }
        let targetEvents = body.objects("events").filter { $0.object("season").integer("year") == season }
        guard targetEvents.allSatisfy({ !$0.text("id").isEmpty }), Set(targetEvents.map { $0.text("id") }).count == targetEvents.count else { throw ArchiveError.invalid("scoreboard identifiers") }
        var seen = Set<String>()
        return body.objects("events").compactMap { event in
            guard event.object("season").integer("year") == season,
                  !event.text("id").isEmpty, seen.insert(event.text("id")).inserted,
                  let game = event.objects("competitions").first else { return nil }
            let eventID = event.text("id")
            let id = "fb-espn-\(competition)-\(eventID)"
            let home = team(game.objects("competitors").first { $0.text("homeAway") == "home" }?.object("team") ?? [:],
                            fallback: "\(id)-home", names: names, badges: badges)
            let away = team(game.objects("competitors").first { $0.text("homeAway") == "away" }?.object("team") ?? [:],
                            fallback: "\(id)-away", names: names, badges: badges)
            let status = game.object("status").isEmpty ? event.object("status") : game.object("status")
            let type = status.object("type")
            let stopped = ["STATUS_SUSPENDED", "STATUS_ABANDONED", "STATUS_CANCELED", "STATUS_POSTPONED"].contains(type.text("name"))
            let completed = type["completed"] as? Bool == true
            let active = type.text("state") == "in"
            let competitors = game.objects("competitors")
            let hs = competitors.first { $0.text("homeAway") == "home" }?.text("score") ?? ""
            let ascore = competitors.first { $0.text("homeAway") == "away" }?.text("score") ?? ""
            let score = !stopped && (completed || active) && !hs.isEmpty && !ascore.isEmpty ? "\(hs) : \(ascore)" : nil
            let rawDate = game.text("date").isEmpty ? event.text("date") : game.text("date")
            let timeValid = game["timeValid"] as? Bool != false
            let supplemental = rounds[id]
            let round: Int? = supplemental?.text("competitionId") == competition && supplemental?.integer("season") == season &&
                supplemental?.integer("homeTeamId") == Int(home.id).map({ $0 + 1_000_000 }) &&
                supplemental?.integer("awayTeamId") == Int(away.id).map({ $0 + 1_000_000 }) ? supplemental?.integer("round") : nil
            let rawVenue = game.object("venue").text("fullName")
            let venueName = names.object("venues").object(rawVenue).text("name")
            return FootballFixture(id: id, providerID: eventID, competition: competition, season: season,
                                   home: home, away: away, kickoff: timeValid ? SportsDate.parse(rawDate) : nil,
                                   sourceDate: String(rawDate.prefix(10)), score: score,
                                   status: statusLabel(type, clock: status.text("displayClock")), round: round,
                                   venue: venueName.isEmpty ? (rawVenue.isEmpty ? "场地待定" : rawVenue) : venueName, fetchedUTC: fetched, stage: game.text("altGameNote").isEmpty ? event.object("season").text("slug") : game.text("altGameNote"))
        }
    }
    static func team(_ object: [String: Any], fallback: String, names: [String: Any], badges: [String: String]) -> FootballTeam {
        let id = object.text("id")
        let raw = object.text("displayName")
        let chinese = Int(id).map { names.object("teams").object(String($0 + 1_000_000)).text("name") } ?? ""
        let pending = raw.isEmpty || ["TBD", "TBC", "Winner", "Loser"].contains(where: { raw.hasPrefix($0) })
        let originalLogo = object.text("logo").isEmpty ? object.objects("logos").first?.text("href") : object.text("logo")
        let logo = originalLogo.flatMap { URL(string: $0) }.flatMap { $0.scheme == "https" ? $0.absoluteString : nil }
        return FootballTeam(id: id.isEmpty ? fallback : id, originalName: raw,
                            name: pending ? "待定" : (chinese.isEmpty ? raw : chinese), badgePath: badges[id], badgeURL: logo)
    }
    private static func statusLabel(_ type: [String: Any], clock: String) -> String {
        switch type.text("name") {
        case "STATUS_SUSPENDED": "中止"
        case "STATUS_ABANDONED": "终止"
        case "STATUS_CANCELED": "取消"
        case "STATUS_POSTPONED": "延期"
        case "STATUS_HALFTIME": "中场"
        default:
            if type["completed"] as? Bool == true { "完场" }
            else if type.text("state") == "in" { clock.isEmpty ? "进行中" : clock }
            else { "待赛" }
        }
    }
}
