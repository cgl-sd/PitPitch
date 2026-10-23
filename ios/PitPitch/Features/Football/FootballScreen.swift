import SwiftUI
import Observation

@MainActor @Observable
final class FootballStore {
    var seasons: [String: ArchivedSeason<FootballFixture>] = [:]
    var errors: [String: String] = [:]
    func load(repository: ArchiveRepository, competition: String, year: Int, force: Bool = false) async {
        let key = "\(competition)/\(year)"
        guard force || seasons[key] == nil else { return }
        do {
            let season = try await repository.football(competition: competition, season: year)
            guard !Task.isCancelled else { return }
            seasons[key] = season
            errors[key] = season.errors.first
        } catch is CancellationError { }
        catch { if !Task.isCancelled { errors[key] = error.localizedDescription } }
    }
}

struct FootballScreen: View {
    let repository: ArchiveRepository
    @Binding var competition: String
    @Binding var year: Int
    @State private var store = FootballStore()
    @Environment(RefreshCoordinator.self) private var refresh
    @State private var attempt = 0
    @ScaledMetric(relativeTo: .subheadline) private var seasonFont: CGFloat = 15
    @AppStorage("footballColumn") private var column = 0
    @State private var positions: [String: String] = [:]
    @State private var phases: [String: Int] = [:]
    private var european: Bool { competition.hasPrefix("uefa") }
    private var phase: Int { phases[key] ?? (store.seasons[key]?.items.contains { $0.isGroupStage && $0.status == "待赛" && ($0.kickoff ?? .distantPast) >= .now } == true ? 0 : 1) }
    private var matches: [FootballFixture] { (store.seasons[key]?.items ?? []).filter { !european || (phase == 0 ? $0.isGroupStage : !$0.isGroupStage) } }
    private var positionKey: String { "\(key)/\(phase)" }
    private var key: String { "\(competition)/\(year)" }
    var body: some View {
        VStack(spacing: 0) {
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(Competition.all) { item in
                        Button { competition = item.id } label: {
                            VStack(spacing: 6) {
                                Text(item.name).font(.subheadline.weight(item.id == competition ? .semibold : .regular))
                                Capsule().fill(item.id == competition ? Palette.green : .clear).frame(width: 18, height: 3)
                            }.frame(minWidth: 44, minHeight: 48)
                        }
                        .foregroundStyle(item.id == competition ? Palette.green : .primary)
                        .accessibilityAddTraits(item.id == competition ? .isSelected : [])
                    }
                }.padding(.horizontal, 16)
            }.scrollIndicators(.hidden)
            HStack(spacing: 6) {
                UnderlineTabs(titles: ["赛程", "积分", "球员"], selection: $column, reselect: { value in
                    attempt += 1
                    if value == 0 { Task { try? await repository.refreshFootball(competition: competition, season: year); refresh.revision += 1 } }
                })
                Menu {
                    ForEach((2020...max(2020, SportsDate.currentFootballSeason())).reversed(), id: \.self) { season in
                        Button { year = season } label: { if year == season { Label("\(String(season))/\(String(season + 1).suffix(2))", systemImage: "checkmark") } else { Text("\(String(season))/\(String(season + 1).suffix(2))") } }
                            .accessibilityIdentifier("football-season-\(season)")
                    }
                } label: {
                    HStack(spacing: 4) { Text("\(String(year))/\(String(year + 1).suffix(2))"); Image(systemName: "chevron.down").font(.system(size: 10, weight: .medium)) }.font(.system(size: min(seasonFont, 24))).lineLimit(1).minimumScaleFactor(0.25).frame(minHeight: 48)
                }.fixedSize(horizontal: true, vertical: false).padding(.horizontal, 10).padding(.bottom, 8).buttonStyle(.plain).foregroundStyle(.primary).accessibilityLabel("赛季，\(String(year))/\(String(year + 1).suffix(2))").accessibilityIdentifier("footballSeason")
            }.padding(.horizontal, 16).frame(minHeight: 48)
            if column != 0 {
                FootballBoards(repository: repository, competition: competition, season: year, players: column == 2, reload: attempt).id("\(key)/\(column)")
            } else if store.seasons[key] != nil {
                if european {
                    CapsuleSegmentedControl(
                        label: "阶段",
                        titles: [year >= 2024 ? "联赛阶段" : "小组赛", "淘汰赛"],
                        selection: Binding(get: { phase }, set: { phases[key] = $0 }),
                        identifierPrefix: "europe-phase"
                    ).padding(.horizontal, 16)
                }
                FixtureList(fixtures: matches, position: Binding(get: { positions[positionKey] ?? matches.first { $0.status == "待赛" && ($0.kickoff ?? .distantPast) >= .now }?.id ?? matches.last?.id }, set: { positions[positionKey] = $0 }), roundNavigation: !european || phase == 0)
                    .id(positionKey)
            } else { Spacer() }
            if let message = store.errors[key] {
                ArchiveFailure(message: message) { store.errors[key] = nil; attempt += 1 }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .navigationBar)
        .safeAreaInset(edge: .top, spacing: 0) { RootBrandBar(kind: 1) }
        .task(id: "\(key)/\(attempt)/\(refresh.revision)") { await store.load(repository: repository, competition: competition, year: year, force: true) }
    }
}

struct FixtureList: View {
    let fixtures: [FootballFixture]
    var months: Bool
    var roundNavigation: Bool
    var contentTopInset: CGFloat
    var rowTopPadding: CGFloat
    var monthHeaderTopPadding: CGFloat
    var monthHeaderBottomPadding: CGFloat
    @Binding var position: String?
    @State private var initialTarget: String
    @State private var positioned = false
    init(
        fixtures: [FootballFixture],
        position: Binding<String?>,
        months: Bool = false,
        roundNavigation: Bool = true,
        contentTopInset: CGFloat = 0,
        rowTopPadding: CGFloat = 24,
        monthHeaderTopPadding: CGFloat = 10,
        monthHeaderBottomPadding: CGFloat = 10
    ) {
        self.months = months
        self.roundNavigation = roundNavigation && !months
        self.fixtures = fixtures
        self.contentTopInset = contentTopInset
        self.rowTopPadding = rowTopPadding
        self.monthHeaderTopPadding = monthHeaderTopPadding
        self.monthHeaderBottomPadding = monthHeaderBottomPadding
        self._position = position
        self._initialTarget = State(initialValue: position.wrappedValue ?? fixtures.first?.id ?? "")
    }
    var body: some View {
        ScrollViewReader { reader in
        VStack(spacing: 0) {
        if roundNavigation {
            let rounds = Array(Set(fixtures.compactMap(\.round))).sorted()
            let current = fixtures.first { $0.id == position }?.round ?? fixtures.first { $0.id == initialTarget }?.round
            HStack {
                Button { jumpRound(-1, current: current, rounds: rounds, reader: reader) } label: { Image(systemName: "chevron.left").frame(width: 48, height: 44) }.disabled(current == nil || current == rounds.first)
                Spacer()
                Button(current.map { "第 \($0) 轮" } ?? "轮次未提供") { if let current, let id = fixtures.first(where: { $0.round == current })?.id { reader.scrollTo(id, anchor: .top) } }.font(.subheadline).disabled(current == nil)
                Spacer()
                Button { jumpRound(1, current: current, rounds: rounds, reader: reader) } label: { Image(systemName: "chevron.right").frame(width: 48, height: 44) }.disabled(current == nil || current == rounds.last)
            }.tint(Palette.green).padding(.horizontal, 6)
        }
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(fixtures) { fixture in
                    VStack(spacing: 0) {
                        let startsMonth = months && fixtures.first(where: { $0.monthKey == fixture.monthKey })?.id == fixture.id
                        if startsMonth {
                            Text(fixture.monthKey)
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.top, monthHeaderTopPadding)
                                .padding(.bottom, monthHeaderBottomPadding)
                                .accessibilityIdentifier("fixture-month-\(fixture.monthKey)")
                        }
                        NavigationLink(value: AppRoute.match(fixture)) {
                            FixtureRow(fixture: fixture, topPadding: startsMonth ? 0 : rowTopPadding)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("fixture-\(fixture.id)")
                        .accessibilityValue(startsMonth ? "month-start:\(fixture.monthKey)" : "fixture")
                        Divider()
                    }.id(fixture.id)
                }
            }.scrollTargetLayout().frame(maxWidth: .infinity)
        }
        .padding(.top, contentTopInset)
        .accessibilityIdentifier("fixture-list-\(initialTarget)")
        .contentMargins(.horizontal, 16, for: .scrollContent)
        .opacity(positioned ? 1 : 0)
        .task {
            guard !positioned else { return }
            await Task.yield()
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) { reader.scrollTo(initialTarget, anchor: .top) }
            await Task.yield()
            positioned = true
        }
        .onScrollTargetVisibilityChange(idType: String.self, threshold: 0.5) { ids in
            if let first = fixtures.first(where: { ids.contains($0.id) })?.id { position = first }
        }
        }
        }
    }
    private func jumpRound(_ delta: Int, current: Int?, rounds: [Int], reader: ScrollViewProxy) {
        guard let current, let index = rounds.firstIndex(of: current), rounds.indices.contains(index + delta), let target = fixtures.first(where: { $0.round == rounds[index + delta] })?.id else { return }
        position = target
        reader.scrollTo(target, anchor: .top)
    }
}
