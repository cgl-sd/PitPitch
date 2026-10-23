import Foundation

enum F1Parser {
    static func parse(_ body: [String: Any], season: Int, fetched: String) throws -> [RacingRound] {
        let data = body.object("MRData")
        let table = data.object("RaceTable")
        guard table.integer("season") == season, table["Races"] is [[String: Any]], (data["total"] == nil || data.integer("total") == table.objects("Races").count) else { throw ArchiveError.invalid("F1 \(season)") }
        var seen = Set<Int>()
        return try table.objects("Races").map { race in
            guard race.integer("season") == season, let round = race.integer("round"), round > 0,
                  seen.insert(round).inserted else { throw ArchiveError.invalid("F1 identity") }
            let circuit = race.object("Circuit")
            let name = race.text("raceName")
            let id = "f1-\(season)-\(round)"
            var sessions = [RaceSession]()
            let practiceKey = ["FirstPractice", "SecondPractice", "ThirdPractice"].first { !race.object($0).text("date").isEmpty }
            let sprintQualifyingKey = ["SprintQualifying", "SprintShootout"].first { !race.object($0).text("date").isEmpty }
            for (key, title) in [(practiceKey ?? "FirstPractice", "练习"), (sprintQualifyingKey ?? "SprintQualifying", "冲刺排位"),
                                 ("Sprint", "冲刺赛"), ("Qualifying", "排位赛")] {
                let session = race.object(key)
                if !session.text("date").isEmpty {
                    sessions.append(RaceSession(id: "\(id)-\(key)", title: title,
                                                date: instant(session), sourceDate: session.text("date")))
                }
            }
            sessions.append(RaceSession(id: id, title: "正赛", date: instant(race), sourceDate: race.text("date")))
            return RacingRound(id: id, season: season, round: round, originalName: name,
                               name: F1DisplayNames.races[name] ?? name, circuitID: circuit.text("circuitId"),
                               circuit: F1DisplayNames.circuits[circuit.text("circuitId")] ?? circuit.text("circuitName"),
                               date: instant(race), sourceDate: race.text("date"), sessions: sessions, country: circuit.object("Location").text("country"), fetchedUTC: fetched)
        }.sorted { $0.round < $1.round }
    }
    private static func instant(_ object: [String: Any]) -> Date? {
        guard !object.text("time").isEmpty else { return nil }
        return SportsDate.parse(object.text("date") + "T" + object.text("time"))
    }
}
