import Foundation
import UserNotifications

final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate, Sendable {
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        let id = notification.request.identifier, token = notification.request.content.userInfo["token"] as? String
        let valid = await MainActor.run { ReminderCenter.shared.records[id]?.token.uuidString == token }
        return valid ? [.banner, .sound, .list] : []
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let id = response.notification.request.identifier
        await MainActor.run { ReminderCenter.shared.openedID = id }
    }
}

extension ArchiveRepository {
    func notificationRoute(_ id: String) -> AppRoute? {
        let parts = id.split(separator: "-")
        if parts.first == "f1", let year = parts.dropFirst().first.flatMap({ Int($0) }), let races = try? races(season: year), let race = races.first(where: { id == $0.id || $0.sessions.contains(where: { $0.id == id }) }) { return .race(race) }
        guard parts.count >= 4, parts[0] == "fb", parts[1] == "espn" else { return nil }
        let competition = String(parts[2])
        for year in (2020...max(2020, SportsDate.currentFootballSeason())).reversed() {
            if let fixture = try? football(competition: competition, season: year).items.first(where: { $0.id == id }) { return .match(fixture) }
        }
        return nil
    }
}
