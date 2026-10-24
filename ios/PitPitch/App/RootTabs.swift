import SwiftUI

enum AppRoute: Hashable, Sendable {
    case match(FootballFixture)
    case race(RacingRound)
    case team(FootballTeam, Int, String)
    case settings
}

struct RootTabs: View {
    let dependencies: AppDependencies
    @State private var selectedTab = 0
    @State private var lastHomeTap = Date.distantPast
    @State private var reminders = ReminderCenter.shared
    @Environment(\.scenePhase) private var scenePhase
    @State private var navigationPath: [AppRoute] = []
    @State private var lastMainTab = 0
    @State private var resolvedHomeTeam: FootballTeam?
    @State private var teamIcon: UIImage?
    @State private var footballSeason = SportsDate.currentFootballSeason()
    @AppStorage("homeTeamID") private var teamID = "360"
    @AppStorage("footballCompetition") private var competition = "eng.1"
    @AppStorage("footballColumn") private var footballColumn = 0
    private var homeTeam: FootballTeam? { resolvedHomeTeam?.id == teamID ? resolvedHomeTeam : nil }
    private var showsBottomBar: Bool { navigationPath.isEmpty && selectedTab != 3 }
    private var selection: Binding<Int> {
        Binding(get: { selectedTab }, set: { value in
            if value == 0 {
                if selectedTab == 0 && Date.now.timeIntervalSince(lastHomeTap) < 0.4 {
                    Task { await dependencies.refresh.refresh() }
                }
                lastHomeTap = .now
            }
            selectedTab = value
            if value != 3 { lastMainTab = value }
        })
    }

    var body: some View {
        NavigationStack(path: $navigationPath) {
            TabView(selection: selection) {
                Tab("首页", systemImage: "house", value: 0) {
                    HomeScreen(repository: dependencies.repository, openSchedule: { id in competition = id; footballColumn = 0; selectedTab = 1; lastMainTab = 1 })
                        .toolbar(.visible, for: .tabBar)
                }.accessibilityIdentifier("nav-0")
                Tab("足球", systemImage: "soccerball", value: 1) {
                    FootballScreen(repository: dependencies.repository, competition: $competition, year: $footballSeason)
                        .toolbar(.visible, for: .tabBar)
                }.accessibilityIdentifier("nav-1")
                Tab("F1", systemImage: "flag.checkered", value: 2) {
                    F1Screen(repository: dependencies.repository)
                        .toolbar(.visible, for: .tabBar)
                }.accessibilityIdentifier("nav-2")
                // The trailing system slot presents the home-team action separately.
                Tab(value: 3, role: .search) {
                    Color.clear.toolbar(.hidden, for: .tabBar)
                } label: {
                    Label {
                        Text("主队")
                    } icon: {
                        if let teamIcon { Image(uiImage: teamIcon).renderingMode(.original) }
                        else { Image(systemName: "shield") }
                    }
                }.accessibilityIdentifier("homeTeam")
            }
            .background(HomeTeamTabAction(isEnabled: homeTeam != nil, open: openHomeTeam))
            .toolbar(selectedTab == 3 ? .visible : .hidden, for: .navigationBar)
            .toolbar {
                if selectedTab == 3 {
                    ToolbarItem(placement: .topBarLeading) {
                        Button { selectedTab = lastMainTab } label: { Image(systemName: "chevron.left").foregroundStyle(.white) }
                            .accessibilityLabel("返回")
                    }
                }
            }
            .destinations(dependencies: dependencies)
        }
        .overlay(alignment: .bottom) {
            if let message = reminders.message {
                Text(message).font(.subheadline).padding(.horizontal, 20).padding(.vertical, 12)
                    .glassEffect(.regular, in: .capsule).padding(.bottom, showsBottomBar ? 82 : 16)
                    .accessibilityIdentifier("reminderFeedback")
            }
        }
        .task(id: scenePhase) { if scenePhase == .active { await reminders.reconcile() } else { dependencies.refresh.cancel() } }
        .task(id: reminders.openedID) {
            guard let id = reminders.openedID, let route = await dependencies.repository.notificationRoute(id) else { return }
            navigationPath = [route]; selectedTab = 0; lastMainTab = 0; reminders.openedID = nil
        }
        .tint(selectedTab == 2 ? Palette.red : Palette.green)
        .environment(dependencies.refresh)
        .environment(\.assetRoot, dependencies.assets)
        .task(id: teamID) {
            let id = teamID
            var team = try? await dependencies.repository.team(id: id)
            if team == nil { team = (try? await dependencies.repository.teams(season: SportsDate.currentFootballSeason()))?.first { $0.id == id } }
            guard !Task.isCancelled, teamID == id else { return }
            resolvedHomeTeam = team
        }
        .task(id: homeTeam) {
            teamIcon = nil
            guard let team = homeTeam else { return }
            let local = if let path = team.badgePath { await AssetDataCache.shared.data(dependencies.assets.appendingPathComponent(path)) } else { nil as Data? }
            let data = if let local { local } else if let url = team.badgeURL { await BadgeImageStore.shared.image(url) } else { nil as Data? }
            guard !Task.isCancelled, let data, let image = UIImage(data: data) else { return }
            // The native grouped buttons have 54pt content height on iPhone.
            let size: CGFloat = 54
            let scale = size / max(image.size.width, image.size.height)
            teamIcon = UIGraphicsImageRenderer(size: CGSize(width: size, height: size)).image { _ in
                let dimensions = CGSize(width: image.size.width * scale, height: image.size.height * scale)
                image.draw(in: CGRect(x: (size - dimensions.width) / 2, y: (size - dimensions.height) / 2, width: dimensions.width, height: dimensions.height))
            }.withRenderingMode(.alwaysOriginal)
        }
    }

    private func openHomeTeam() {
        guard navigationPath.isEmpty, let team = homeTeam else { return }
        navigationPath.append(.team(team, SportsDate.currentFootballSeason(), "all"))
    }
}

/// The trailing system control is an action, so UIKit must not first select its search tab.
/// Keep SwiftUI's delegate for all other behavior, including the three native glass tabs.
private struct HomeTeamTabAction: UIViewControllerRepresentable {
    let isEnabled: Bool
    let open: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIViewController(context: Context) -> Probe {
        let controller = Probe()
        controller.connect = { [weak coordinator = context.coordinator] in coordinator?.connect(from: $0) }
        return controller
    }
    func updateUIViewController(_ controller: Probe, context: Context) {
        context.coordinator.open = open
        context.coordinator.isEnabled = isEnabled
        controller.connect?(controller)
    }
    static func dismantleUIViewController(_ controller: Probe, coordinator: Coordinator) { coordinator.restore() }

    final class Probe: UIViewController {
        var connect: ((UIViewController) -> Void)?
        override func loadView() {
            view = UIView()
            view.isUserInteractionEnabled = false
            view.backgroundColor = .clear
        }
        override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); connect?(self) }
        override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); connect?(self) }
    }

    final class Coordinator: NSObject, UITabBarControllerDelegate {
        var open: (() -> Void)?
        var isEnabled = false
        private weak var controller: UITabBarController?
        // NSObject's selector forwarding is nonisolated; it only passes through this weak reference.
        nonisolated(unsafe) private weak var original: (any UITabBarControllerDelegate)?

        func connect(from probe: UIViewController) {
            if let controller, controller.delegate === self {
                controller.tabs.last?.isEnabled = isEnabled
                controller.tabBar.items?.last?.isEnabled = isEnabled
                return
            }
            func find(in parent: UIViewController) -> UITabBarController? {
                if let tabs = parent as? UITabBarController { return tabs }
                for child in parent.children { if let tabs = find(in: child) { return tabs } }
                return nil
            }
            var parent: UIViewController? = probe
            while let candidate = parent {
                if let tabs = find(in: candidate), tabs.tabs.count == 4 || tabs.viewControllers?.count == 4 {
                    if controller !== tabs { restore() }
                    controller = tabs
                    original = tabs.delegate
                    tabs.delegate = self
                    tabs.tabs.last?.isEnabled = isEnabled
                    tabs.tabBar.items?.last?.isEnabled = isEnabled
                    return
                }
                parent = candidate.parent
            }
        }
        func restore() {
            if controller?.delegate === self { controller?.delegate = original }
            controller = nil
            original = nil
        }
        func tabBarController(_ tabBarController: UITabBarController, shouldSelectTab tab: UITab) -> Bool {
            if tabBarController.tabs.last === tab {
                if isEnabled { open?() }
                return false
            }
            return original?.tabBarController?(tabBarController, shouldSelectTab: tab) ?? true
        }
        func tabBarController(_ tabBarController: UITabBarController, shouldSelect viewController: UIViewController) -> Bool {
            if tabBarController.viewControllers?.last === viewController {
                if isEnabled { open?() }
                return false
            }
            return original?.tabBarController?(tabBarController, shouldSelect: viewController) ?? true
        }
        nonisolated override func responds(to selector: Selector!) -> Bool {
            super.responds(to: selector) || (original?.responds(to: selector) ?? false)
        }
        nonisolated override func forwardingTarget(for selector: Selector!) -> Any? {
            original ?? super.forwardingTarget(for: selector)
        }
    }
}

private struct Destinations: ViewModifier {
    let dependencies: AppDependencies
    func body(content: Content) -> some View {
        content.navigationDestination(for: AppRoute.self) { route in
            Group {
            switch route {
            case .match(let fixture): FixtureDetailScreen(fixture: fixture, repository: dependencies.repository)
            case .race(let race): RaceDetailScreen(race: race, repository: dependencies.repository)
            case .team(let team, let year, let competition): TeamScreen(team: team, season: year, repository: dependencies.repository, initialCompetition: competition)
            case .settings: SettingsScreen(repository: dependencies.repository)
            }
            }.toolbar(.visible, for: .navigationBar)
        }
    }
}

extension View {
    func destinations(dependencies: AppDependencies) -> some View { modifier(Destinations(dependencies: dependencies)) }
}
