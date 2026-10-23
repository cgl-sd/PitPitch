import Foundation

struct FootballStanding: Identifiable, Sendable {
    var id: String { team.id }
    let team: FootballTeam
    let rank: Int
    let played, won, drawn, lost, goalsFor, goalsAgainst, points: String
    var note: String
}
struct FootballTable: Identifiable, Sendable {
    let id, name: String
    let rows: [FootballStanding]
}
struct FootballPlayer: Identifiable, Sendable {
    let id, name, number, position: String
    let team: FootballTeam
    let goals, assists: Double?
    let starter: Bool?
}
struct MatchStatistic: Identifiable, Sendable {
    var id: String { key }
    let key, title, home, away: String
}
struct MatchEvent: Identifiable, Sendable {
    let id, type, clock, text, player: String
    let teamID: String?
    let period: Int?
    var sourceScore: String? = nil
    var seconds: Double? = nil
}
struct MatchDetail: Sendable {
    let statistics: [MatchStatistic]
    let events: [MatchEvent]
    let players: [FootballPlayer]
    let formations: [String: String]
}
struct RacingStanding: Identifiable, Sendable {
    let id, name, constructorID, constructor, points: String
    let rank: Int
}
struct RacingTable: Sendable {
    let rows: [RacingStanding]
    let round: Int?
}
struct RacingResult: Identifiable, Sendable {
    let id, name, constructorID, constructor, points, result, q1, q2, q3: String
    let rank: Int
}
