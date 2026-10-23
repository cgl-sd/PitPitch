import Foundation
import UserNotifications
import Observation
import SwiftUI
import UIKit

struct SportsReminder: Codable, Sendable {
    let id, title: String
    let start: Date
    let token: UUID
}

@MainActor @Observable
final class ReminderCenter {
    static let shared = ReminderCenter()
    private(set) var records: [String: SportsReminder] = [:]
    private(set) var busy = Set<String>()
    var message: String?
    var openedID: String?
    private var feedback: Task<Void, Never>?
    private let center = UNUserNotificationCenter.current()
    private let file: URL
    private var readable = true
    init(file: URL? = nil) {
        self.file = file ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("DeviceReminders/subscriptions.json")
        do {
            if FileManager.default.fileExists(atPath: self.file.path) {
                records = try JSONDecoder().decode([String: SportsReminder].self, from: Data(contentsOf: self.file))
            }
        } catch { readable = false }
    }
    func persist(_ values: [String: SportsReminder]) throws {
        let directory = file.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var excluded = directory, attributes = URLResourceValues(); attributes.isExcludedFromBackup = true
        try excluded.setResourceValues(attributes)
        try JSONEncoder().encode(values).write(to: file, options: .atomic)
    }
    func toggle(id: String, title: String, start: Date?) async {
        guard busy.isEmpty else { return }
        busy.insert(id); defer { busy.remove(id) }
        guard readable else { show("提醒记录读取失败，请稍后重试"); return }
        if records[id] != nil {
            var next = records; next[id] = nil
            do {
                try persist(next)
                center.removePendingNotificationRequests(withIdentifiers: [id])
                center.removeDeliveredNotifications(withIdentifiers: [id])
                records = next; show("已取消")
            } catch { show("取消失败：\(error.localizedDescription)") }
            return
        }
        guard let start, start.addingTimeInterval(-300) > .now else { show("已超过预约时间"); return }
        do {
            let settings = await center.notificationSettings()
            var authorized = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional || settings.authorizationStatus == .ephemeral
            if settings.authorizationStatus == .notDetermined { authorized = try await center.requestAuthorization(options: [.alert, .sound, .badge]) }
            guard authorized else { show("请在系统设置中允许 PitPitch 通知"); return }
            guard (await center.pendingNotificationRequests()).count < 60 else { show("待发送提醒已达上限，请先取消部分预约"); return }
            let record = SportsReminder(id: id, title: title, start: start, token: UUID())
            var next = records; next[id] = record
            // A notification can be reconciled even if the process exits before persistence.
            // Persist only after system registration, then remove it if that write fails.
            try await register(record)
            do { try persist(next) }
            catch { center.removePendingNotificationRequests(withIdentifiers: [id]); throw error }
            records = next; show("已预约")
        } catch { show("预约失败：\(error.localizedDescription)") }
    }
    private func register(_ record: SportsReminder) async throws {
        guard record.start.addingTimeInterval(-300) > .now else { throw ArchiveError.invalid("reminder time") }
        let content = UNMutableNotificationContent()
        content.title = "比赛即将开始"
        content.body = record.title
        content.sound = .default
        content.userInfo = ["id": record.id, "token": record.token.uuidString]
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        var date = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: record.start.addingTimeInterval(-300))
        date.timeZone = calendar.timeZone
        try await center.add(UNNotificationRequest(identifier: record.id, content: content, trigger: UNCalendarNotificationTrigger(dateMatching: date, repeats: false)))
        let requests = await center.pendingNotificationRequests()
        guard requests.contains(where: { $0.identifier == record.id && $0.content.userInfo["token"] as? String == record.token.uuidString }) else { throw ArchiveError.invalid("notification registration") }
    }
    func reconcile() async {
        guard readable else { return }
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
        let pending = await center.pendingNotificationRequests()
        let stale = pending.filter { records[$0.identifier]?.token.uuidString != ($0.content.userInfo["token"] as? String) }.map(\.identifier)
        center.removePendingNotificationRequests(withIdentifiers: stale)
        let pendingIDs = Set(pending.map(\.identifier)).subtracting(stale)
        for record in records.values.sorted(by: { $0.start < $1.start }) where record.start.addingTimeInterval(-300) > .now && !pendingIDs.contains(record.id) {
            if (await center.pendingNotificationRequests()).count >= 60 { break }
            try? await register(record)
        }
    }
    private func show(_ text: String) {
        feedback?.cancel(); message = text
        UIAccessibility.post(notification: .announcement, argument: text)
        feedback = Task { try? await Task.sleep(for: .milliseconds(1400)); if !Task.isCancelled { message = nil } }
    }
}
struct ReminderButton: View {
    let id, title: String
    let start: Date?
    @State private var center = ReminderCenter.shared
    var body: some View {
        if center.records[id] != nil || (start.map { $0.addingTimeInterval(-300) > .now } == true) {
            Button { Task { await center.toggle(id: id, title: title, start: start) } } label: {
                Image(systemName: center.records[id] == nil ? "bell" : "bell.fill").frame(width: 44, height: 44)
            }.buttonStyle(.plain).accessibilityIdentifier("reminder-" + id).disabled(!center.busy.isEmpty).accessibilityLabel(center.records[id] == nil ? "预约：\(title)" : "取消预约：\(title)")
        }
    }
}
