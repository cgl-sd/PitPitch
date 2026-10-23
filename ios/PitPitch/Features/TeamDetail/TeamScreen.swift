import SwiftUI

private enum TeamProfileLayout {
    static let segmentedControlTopInset: CGFloat = 12
    static let contentTopInset: CGFloat = 8
    static let monthHeaderTopPadding: CGFloat = 4
    static let monthHeaderBottomPadding: CGFloat = 0
    static let fixtureRowTopPadding: CGFloat = 14
    static let rosterContentTopInset: CGFloat = 23.5
}

struct TeamScreen: View {
    let team: FootballTeam
    let season: Int
    let repository: ArchiveRepository
    @State private var fixtures: [FootballFixture] = []
    @State private var playersByCompetition: [String: [FootballPlayer]] = [:]
    private var players: [FootballPlayer] { playersByCompetition[competition] ?? [] }
    @State private var error: String?
    @State private var positions: [String: String] = [:]
    private let competition = "all"
    private let initialCompetition: String
    @State private var column = 0
    @State private var color = Palette.green
    @State private var rank: String?
    @State private var rankNumber = "—"
    @State private var leagueName: String?
    private var filtered: [FootballFixture] { fixtures.filter { competition == "all" || $0.competition == competition } }
    private var competitions: [Competition] { Competition.all.filter { c in fixtures.contains { $0.competition == c.id } } }
    init(team: FootballTeam, season: Int, repository: ArchiveRepository, initialCompetition: String = "all") {
        self.team = team; self.season = season; self.repository = repository
        self.initialCompetition = initialCompetition
    }
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(team.name).font(.title2.bold())
                    if team.originalName != team.name { Text(team.originalName).font(.system(size: 13)) }
                    Text("\(String(season))/\(String(season + 1)) 赛季" + (rank.map { " · " + ($0.components(separatedBy: " 第 ").first ?? "") } ?? ""))
                        .font(.system(size: 12))
                }.frame(maxWidth: .infinity, alignment: .leading)
                VStack(spacing: 6) {
                    TeamBadge(team: team, size: 64)
                    HStack(spacing: 6) {
                        Text(rankNumber).font(.system(size: 22, weight: .bold))
                        Text((leagueName ?? "") + "排名").font(.system(size: 12))
                    }.opacity(rank == nil ? 0 : 1)
                }.frame(width: 100)
            }.padding(16).foregroundStyle(.white)
                .background {
                    GeometryReader { geometry in
                        ZStack(alignment: .center) {
                            LinearGradient(colors: [color, color.opacity(0.75)], startPoint: .leading, endPoint: .trailing)
                            TeamBadge(team: team, size: max(geometry.size.height, 150))
                                .opacity(0.14)
                        }
                        .frame(width: geometry.size.width, height: geometry.size.height)
                        .clipped()
                    }
                    .ignoresSafeArea(edges: .top)
                    .allowsHitTesting(false)
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("team-profile-header")
            CapsuleSegmentedControl(
                label: "球队详情栏目",
                titles: ["赛程", "队员"],
                selection: $column,
                identifierPrefix: "team-profile-section"
            )
            .padding(.horizontal, 16)
            .padding(.top, TeamProfileLayout.segmentedControlTopInset)
            if column == 0 {
                if !filtered.isEmpty {
                    FixtureList(
                        fixtures: filtered,
                        position: Binding(
                            get: {
                                positions[competition]
                                    ?? filtered.first { $0.status == "待赛" && ($0.kickoff ?? .distantPast) >= .now }?.id
                                    ?? filtered.last?.id
                            },
                            set: { positions[competition] = $0 }
                        ),
                        months: true,
                        contentTopInset: TeamProfileLayout.contentTopInset,
                        rowTopPadding: TeamProfileLayout.fixtureRowTopPadding,
                        monthHeaderTopPadding: TeamProfileLayout.monthHeaderTopPadding,
                        monthHeaderBottomPadding: TeamProfileLayout.monthHeaderBottomPadding
                    ).id(competition)
                } else { Spacer() }
            } else {
                TeamRosterList(
                    players: players,
                    contentTopInset: TeamProfileLayout.rosterContentTopInset
                )
            }
            if let error { Text(error).font(.caption).foregroundStyle(.secondary).padding() }
        }
        .navigationTitle("").navigationBarTitleDisplayMode(.inline).tint(Palette.green)
        .toolbarBackground(.hidden, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("team-detail")
        .task(id: "\(team.id)/\(season)") {
            if let hex = await repository.teamColor(team.id) { color = Color(hex: hex) }
            do { fixtures = try await repository.teamFixtures(id: team.id, season: season) } catch { self.error = error.localizedDescription }
            for c in competitions where !c.id.hasPrefix("uefa") {
                if let tables = try? await repository.tables(competition: c.id, season: season), let row = tables.flatMap(\.rows).first(where: { $0.id == team.id }) { rank = "\(c.name) 第 \(row.rank) 名"; rankNumber = String(row.rank); leagueName = c.name; break }
            }
        }
        .task(id: "\(competition)/\(fixtures.count)") {
            guard let selected = competitions.first(where: { $0.id == initialCompetition }) ?? competitions.first(where: { !$0.id.hasPrefix("uefa") }) ?? competitions.first else { return }
            let filter = competition
            if let values = try? await repository.roster(team: team, competition: selected.id, season: season), !Task.isCancelled { playersByCompetition[filter] = values }
            do { let values = try await repository.roster(team: team, competition: selected.id, season: season, refresh: true); try Task.checkCancellation(); playersByCompetition[filter] = values } catch { }
        }
    }
}

private struct TeamRosterList: View {
    let players: [FootballPlayer]
    let contentTopInset: CGFloat
    private let positions = ["Forward", "Midfielder", "Defender", "Goalkeeper", "Unknown"]
    private let knownPositions = ["Forward", "Midfielder", "Defender", "Goalkeeper"]
    private let positionNames = [
        "Forward": "前锋",
        "Midfielder": "中场",
        "Defender": "后卫",
        "Goalkeeper": "门将",
        "Unknown": "其他位置"
    ]

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(positions, id: \.self) { position in
                    let rows = players.filter {
                        position == "Unknown"
                            ? !knownPositions.contains($0.position)
                            : $0.position == position
                    }
                    if !rows.isEmpty {
                        Text(positionNames[position] ?? "其他位置")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.top, TeamProfileLayout.monthHeaderTopPadding)
                            .padding(.bottom, TeamProfileLayout.monthHeaderBottomPadding)
                            .accessibilityIdentifier("team-position-\(position)")
                        ForEach(rows) { player in
                            HStack {
                                Text(player.number.isEmpty ? "—" : player.number)
                                    .fixedSize(horizontal: true, vertical: false)
                                    .frame(minWidth: 30)
                                    .foregroundStyle(.secondary)
                                Text(player.name).font(.subheadline)
                                Spacer()
                            }
                            .padding(.vertical, 6)
                            .frame(minHeight: 48)
                            Divider()
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, contentTopInset)
        }
        .scrollIndicators(.hidden)
        .accessibilityIdentifier("team-roster-list")
    }
}
