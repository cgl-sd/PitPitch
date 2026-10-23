import SwiftUI

private enum HomeEntry: Identifiable {
    case football(HomeCard)
    case race(RacingRound)
    var id: String { switch self { case .football(let card): card.id; case .race(let race): race.id } }
    var date: Date {
        switch self {
        case .football(let card): card.date
        case .race(let race): race.sessions.compactMap(\.date).filter { $0 > .now }.min() ?? .distantFuture
        }
    }
}

struct HomeScreen: View {
    let repository: ArchiveRepository
    let openSchedule: (String) -> Void
    @State private var store = HomeStore()
    @Environment(RefreshCoordinator.self) private var refresh
    @Environment(\.scenePhase) private var scenePhase
    @State private var pullDistance: CGFloat = 0
    @State private var top = true
    @State private var now = Date.now
    @State private var attempt = 0
    @AppStorage("homeCompetitions") private var selected = "eng.1,uefa.champions,f1"
    private var entries: [HomeEntry] {
        let enabled = Set(selected.split(separator: ",").map(String.init))
        var result = HomeRules.cards(store.fixtures.filter { enabled.contains($0.competition) }, now: now).map(HomeEntry.football)
        if enabled.contains("f1"), let race = store.races.first(where: { ($0.date ?? .distantPast) >= now }) { result.append(.race(race)) }
        return result.sorted { $0.date < $1.date }
    }
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 10) {
                Text("赛事赛程").font(.title2.bold()).padding(.top, 12).padding(.bottom, 2)
                ForEach(entries) { entry in
                    switch entry {
                    case .football(let card): footballCard(card)
                    case .race(let race):
                        NavigationLink(value: AppRoute.race(race)) { HomeRaceCard(race: race) }.buttonStyle(.plain).accessibilityIdentifier("home-race-" + race.id)
                    }
                }
                if store.ready && selected.isEmpty { Text("尚未选择首页赛事").foregroundStyle(.secondary); NavigationLink("选择首页赛事", value: AppRoute.settings) }
                else if store.ready && entries.isEmpty && store.errors.isEmpty { Text("暂无后续赛程").foregroundStyle(.secondary).padding(.vertical) }
                Text(refresh.lastSuccess.map { "上次更新：" + $0.formatted(.dateTime.year().month(.twoDigits).day(.twoDigits).hour().minute().locale(Locale(identifier: "zh_CN"))) } ?? "上次更新：未知").font(.caption2).foregroundStyle(.secondary).frame(maxWidth: .infinity).padding(.top, 12)
                if let error = refresh.error { Text(error).font(.caption).foregroundStyle(.secondary) }
                ForEach(store.errors, id: \.self) { message in ArchiveFailure(message: message) { attempt += 1 } }
            }.padding(.horizontal, 16).padding(.bottom, 12)
        }
        .offset(y: refresh.refreshing ? 18 : 0)
        .animation(.easeOut(duration: 0.2), value: refresh.refreshing)
        .onScrollGeometryChange(for: Bool.self) { $0.contentOffset.y <= 0 } action: { _, value in top = value }
        .simultaneousGesture(DragGesture().onChanged { value in if top { pullDistance = max(pullDistance, value.translation.height) } }.onEnded { _ in if pullDistance > 90 { Task { await refresh.refresh() } }; pullDistance = 0 })
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .navigationBar)
        .safeAreaInset(edge: .top, spacing: 0) { RootBrandBar(kind: 0) }
        .task(id: "\(attempt)/\(refresh.revision)") { await store.load(repository: repository) }
        .task(id: "clock/\(scenePhase)") {
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                now = .now
                let next = (store.fixtures.compactMap(\.kickoff) + store.races.flatMap { $0.sessions.compactMap(\.date) }).filter { $0 > now }.min()
                let interval = min(60, max(0.05, (next?.timeIntervalSince(now) ?? 60) + 0.01))
                do { try await Task.sleep(for: .seconds(interval)) } catch { return }
            }
        }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            await store.load(repository: repository)
            await refresh.refresh()
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(120)) } catch { return }
                await refresh.refresh()
            }
        }
    }
    private func footballCard(_ card: HomeCard) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { openSchedule(card.competition) } label: {
                HStack(spacing: 8) {
                    if let logo = CompetitionBrand.names[card.competition] { BrandImage(name: logo).frame(width: 28, height: 28) }
                    Text(Competition.name(card.competition)).font(.subheadline.weight(.semibold))
                    Spacer()
                    if let round = card.round { Text("第 \(round) 轮").font(.caption).foregroundStyle(.secondary) }
                }.padding(.top, 14).padding(.bottom, 4).contentShape(Rectangle())
            }.buttonStyle(.plain)
            ForEach(card.fixtures) { fixture in
                NavigationLink(value: AppRoute.match(fixture)) { FixtureRow(fixture: fixture) }
                    .buttonStyle(.plain).accessibilityIdentifier("fixture-\(fixture.id)")
                if fixture.id != card.fixtures.last?.id { Divider() }
            }
        }
        .padding(.horizontal, 14)
        .background { Button { openSchedule(card.competition) } label: { Color.clear.contentShape(Rectangle()) }.buttonStyle(.plain).accessibilityLabel("\(Competition.name(card.competition))赛程") }
        .background(.white, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(.systemGray5), lineWidth: 1))
    }
}

private struct HomeRaceCard: View {
    let race: RacingRound
    private var primary: RaceSession? { race.sessions.first { $0.title == "正赛" } }
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Palette.red.frame(height: 3)
            LocalMedia(path: race.photoPath, crop: true).frame(height: 140)
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 10) {
                    CountryFlag(country: race.country)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(race.name).font(.headline)
                        Text(race.circuit).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text("第 \(race.round) 站").font(.caption).foregroundStyle(.secondary)
                }
                if let session = primary {
                    HStack { VStack(alignment: .leading, spacing: 4) { Text(SportsDate.label(session.date, fallback: session.sourceDate)).font(.title3.bold()); Text("正赛").font(.caption).foregroundStyle(.secondary) }; Spacer(); ReminderButton(id: race.id, title: race.name + " · 正赛", start: session.date) }
                }
                Divider()
                HStack(alignment: .top) {
                    ForEach(race.sessions.filter { ["练习", "排位赛"].contains($0.title) }) { session in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(session.title).font(.subheadline)
                            Text(SportsDate.label(session.date, fallback: session.sourceDate)).font(.caption).foregroundStyle(.secondary)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }.padding(14)
        }
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(.systemGray5), lineWidth: 1))
        .foregroundStyle(.primary)
    }
}
