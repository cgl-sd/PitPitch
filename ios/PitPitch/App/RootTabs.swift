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
    @AppStorage("footballSeason") private var footballSeason = SportsDate.currentFootballSeason()
    @AppStorage("homeTeamID") private var teamID = "360"
    @AppStorage("footballCompetition") private var competition = "eng.1"
    @AppStorage("footballColumn") private var footballColumn = 0
    private var homeTeam: FootballTeam? { resolvedHomeTeam?.id == teamID ? resolvedHomeTeam : nil }
    private var atRoot: Bool { navigationPath.isEmpty }
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
                }.accessibilityIdentifier("nav-0")
                Tab("足球", systemImage: "soccerball", value: 1) {
                    FootballScreen(repository: dependencies.repository, competition: $competition, year: $footballSeason)
                }.accessibilityIdentifier("nav-1")
                Tab("F1", systemImage: "flag.checkered", value: 2) {
                    F1Screen(repository: dependencies.repository)
                }.accessibilityIdentifier("nav-2")
                // The trailing system slot presents the home-team action separately.
                Tab(value: 3, role: .search) {
                    Group {
                        if let team = homeTeam {
                            TeamScreen(team: team, season: SportsDate.currentFootballSeason(), repository: dependencies.repository)
                                .id(team.id)
                        } else { Color.white }
                    }
                } label: {
                    Label {
                        Text("主队")
                    } icon: {
                        if let teamIcon { Image(uiImage: teamIcon).renderingMode(.original) }
                        else { Image(systemName: "shield") }
                    }
                }.accessibilityIdentifier("homeTeam")
            }
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
                    .glassEffect(.regular, in: .capsule).padding(.bottom, atRoot ? 82 : 16)
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
