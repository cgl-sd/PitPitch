import Foundation
import Observation
import UserNotifications

@MainActor @Observable
final class AppDependencies {
    let repository: ArchiveRepository
    let assets: URL
    let refresh: RefreshCoordinator
    let notificationDelegate = NotificationDelegate()
    init() {
        assets = Bundle.main.resourceURL!.appendingPathComponent("assets", isDirectory: true)
        repository = ArchiveRepository(root: assets)
        refresh = RefreshCoordinator(repository: repository)
        UNUserNotificationCenter.current().delegate = notificationDelegate
    }
}
