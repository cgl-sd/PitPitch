import Foundation

struct Competition: Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    static let all = [Competition(id: "eng.1", name: "英超"), Competition(id: "esp.1", name: "西甲"),
                      Competition(id: "ita.1", name: "意甲"), Competition(id: "ger.1", name: "德甲"),
                      Competition(id: "fra.1", name: "法甲"), Competition(id: "uefa.champions", name: "欧冠"),
                      Competition(id: "uefa.europa", name: "欧联")]
    static func name(_ id: String) -> String { all.first { $0.id == id }?.name ?? id }
}

struct FootballTeam: Identifiable, Hashable, Sendable {
    let id: String
    let originalName: String
    let name: String
    let badgePath: String?
    var badgeURL: String? = nil
}

struct FootballFixture: Identifiable, Hashable, Sendable {
    let id: String
    let providerID: String
    let competition: String
    let season: Int
    let home: FootballTeam
    let away: FootballTeam
    let kickoff: Date?
    let sourceDate: String
    let score: String?
    let status: String
    let round: Int?
    let venue: String
    let fetchedUTC: String
    var stage: String = ""
    var isGroupStage: Bool { stage.localizedCaseInsensitiveContains("league phase") || stage.localizedCaseInsensitiveContains("group") || stage == "league-phase" || stage == "group-stage" }
    var monthKey: String {
        guard let kickoff else { return String(sourceDate.prefix(7)) }
        let parts = Calendar.current.dateComponents([.year, .month], from: kickoff)
        return String(format: "%04d-%02d", parts.year ?? 0, parts.month ?? 0)
    }
    var timeLabel: String { SportsDate.label(kickoff, fallback: sourceDate) }
}

struct RaceSession: Identifiable, Hashable, Sendable {
    let id: String
    let title: String
    let date: Date?
    let sourceDate: String
}

struct RacingRound: Identifiable, Hashable, Sendable {
    let id: String
    let season: Int
    let round: Int
    let originalName: String
    let name: String
    let circuitID: String
    let circuit: String
    let date: Date?
    let sourceDate: String
    let sessions: [RaceSession]
    var photoPath: String? = nil
    var layoutFile: String? = nil
    var hasResults = false
    var country = ""
    let fetchedUTC: String
}

struct ArchivedSeason<T: Sendable>: Sendable {
    let items: [T]
    let errors: [String]
}

enum SportsDate {
    static func parse(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        if let date = formatter.date(from: value) { return date }
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: value) { return date }
        // ESPN timestamps sometimes omit seconds; retain the exact supplied minute.
        if value.count == 17 && value.hasSuffix("Z") {
            return ISO8601DateFormatter().date(from: String(value.dropLast()) + ":00Z")
        }
        return nil
    }
    static func label(_ date: Date?, fallback: String) -> String {
        guard let date else { return fallback.isEmpty ? "时间待定" : "\(fallback.prefix(10)) · 时间待定" }
        return dayLabel(date) + " " + date.formatted(.dateTime.hour().minute().locale(Locale(identifier: "zh_CN")))
    }
    static func dayLabel(_ date: Date) -> String {
        let day = date.formatted(.dateTime.month(.twoDigits).day(.twoDigits).locale(Locale(identifier: "zh_CN")))
        let weekday = date.formatted(.dateTime.weekday(.abbreviated).locale(Locale(identifier: "zh_CN")))
        return day + " " + weekday
    }
    static func currentFootballSeason(now: Date = .now) -> Int {
        let components = Calendar.current.dateComponents([.year, .month], from: now)
        return (components.year ?? 2026) - ((components.month ?? 7) < 7 ? 1 : 0)
    }
}
