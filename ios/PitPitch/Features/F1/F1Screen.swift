import SwiftUI
import Observation

@MainActor @Observable
final class F1Store {
    var seasons: [Int: [RacingRound]] = [:]
    var errors: [Int: String] = [:]
    func load(repository: ArchiveRepository, year: Int, force: Bool = false) async {
        guard force || seasons[year] == nil else { return }
        do {
            let races = try await repository.races(season: year)
            guard !Task.isCancelled else { return }
            seasons[year] = races
            errors[year] = nil
        } catch is CancellationError { }
        catch { if !Task.isCancelled { errors[year] = error.localizedDescription } }
    }
}

struct F1Screen: View {
    let repository: ArchiveRepository
    @State private var year = Calendar.current.component(.year, from: .now)
    @State private var store = F1Store()
    @Environment(RefreshCoordinator.self) private var refresh
    @State private var attempt = 0
    @ScaledMetric(relativeTo: .subheadline) private var seasonFont: CGFloat = 15
    @State private var column = 0
    @State private var positions: [Int: String] = [:]
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                UnderlineTabs(titles: ["赛程", "车手积分", "车队积分"], selection: $column, color: Palette.red)
                Menu {
                    ForEach((2020...max(2020, Calendar.current.component(.year, from: .now))).reversed(), id: \.self) { season in
                        Button { year = season } label: { if year == season { Label(String(season), systemImage: "checkmark") } else { Text(String(season)) } }
                    }
                } label: {
                    HStack(spacing: 4) { Text(String(year)); Image(systemName: "chevron.down").font(.system(size: 10, weight: .medium)) }.font(.system(size: min(seasonFont, 24))).lineLimit(1).minimumScaleFactor(0.25).frame(minHeight: 48)
                }.fixedSize(horizontal: true, vertical: false).padding(.horizontal, 10).padding(.bottom, 8).buttonStyle(.plain).foregroundStyle(.primary).accessibilityLabel("赛季，\(String(year))").accessibilityIdentifier("f1Season")
            }.padding(.horizontal, 16).frame(minHeight: 48)
            if column != 0 {
                RacingBoards(repository: repository, season: year, constructors: column == 2, raceCount: store.seasons[year]?.count ?? 0).id("\(year)/\(column)")
            } else if let races = store.seasons[year] {
                RaceList(races: races, year: year, position: Binding(get: { positions[year] ?? (year == Calendar.current.component(.year, from: .now) ? races.first { !$0.hasResults && ($0.date ?? .distantPast) >= Calendar.current.startOfDay(for: .now) }?.id : nil) ?? races.first?.id }, set: { positions[year] = $0 })).id(year)
            } else { Spacer() }
            if let message = store.errors[year] { ArchiveFailure(message: message) { attempt += 1 } }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .navigationBar)
        .safeAreaInset(edge: .top, spacing: 0) { RootBrandBar(kind: 2) }
        .task(id: "\(year)/\(attempt)/\(refresh.revision)") { await store.load(repository: repository, year: year, force: true) }
        .tint(Palette.red)
    }
}

private struct RaceList: View {
    let races: [RacingRound]
    let year: Int
    @Binding var position: String?
    @State private var initialTarget: String
    @State private var positioned = false
    init(races: [RacingRound], year: Int, position: Binding<String?>) {
        self.races = races
        self.year = year
        self._position = position
        self._initialTarget = State(initialValue: position.wrappedValue ?? races.first?.id ?? "")
    }
    private var next: RacingRound? {
        year == Calendar.current.component(.year, from: .now) ? races.first { !$0.hasResults && ($0.date ?? .distantPast) >= Calendar.current.startOfDay(for: .now) } : nil
    }
    var body: some View {
        ScrollViewReader { reader in
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(races) { race in
                    ProportionalScheduleRow(heightRatio: race.id == next?.id ? 0 : 129.0 / 465.0, horizontalInset: 20) {
                        VStack(alignment: .leading, spacing: race.id == next?.id ? 12 : 0) {
                            NavigationLink(value: AppRoute.race(race)) {
                                HStack(spacing: 10) {
                                    VStack(alignment: .leading, spacing: race.id == next?.id ? 6 : 10) {
                                        Text(race.id == next?.id ? "第 \(race.round) 站" : "第 \(race.round) 站 · \(SportsDate.label(race.date, fallback: race.sourceDate))")
                                            .font(.caption2).foregroundStyle(race.id == next?.id ? Palette.red : .secondary)
                                        Text(race.name).font(race.id == next?.id ? .headline : .subheadline.weight(.semibold))
                                        Text(race.circuit).font(.caption2).foregroundStyle(.secondary)
                                    }
                                    Spacer(minLength: 0)
                                    TrackOutline(race: race).frame(width: 76, height: 54)
                                }.frame(minHeight: 54)
                                    .foregroundStyle(.primary).contentShape(Rectangle())
                            }.buttonStyle(.plain).accessibilityIdentifier("race-\(race.id)")
                            if race.id == next?.id {
                                Divider()
                                RaceSessionGrid(race: race).padding(.bottom, 4)
                            }
                        }.padding(.horizontal, 10).padding(.top, race.id == next?.id ? 14 : 16).padding(.bottom, race.id == next?.id ? 14 : 10)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .background(.white, in: RoundedRectangle(cornerRadius: 16))
                            .overlay(RoundedRectangle(cornerRadius: 16).stroke(race.id == next?.id ? Color(.systemGray5) : .clear, lineWidth: 1))
                            .overlay(alignment: .bottom) {
                                if race.id != next?.id { Divider().padding(.horizontal, 10).allowsHitTesting(false) }
                            }
                    }
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("race-card-" + race.id)
                        .id(race.id)
                }
            }.scrollTargetLayout().padding(.horizontal, 16)
        }
        .accessibilityIdentifier("f1-calendar")
        .opacity(positioned ? 1 : 0)
        .task {
            guard !positioned else { return }
            await Task.yield()
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) { reader.scrollTo(initialTarget, anchor: initialTarget == races.first?.id ? .top : initialTarget == races.last?.id ? .bottom : .center) }
            await Task.yield()
            positioned = true
        }
        .onScrollTargetVisibilityChange(idType: String.self, threshold: 0.5) { ids in
            guard positioned else { return }
            if let first = races.first(where: { ids.contains($0.id) })?.id { position = first }
        }
        }
    }
}

private struct RaceSessionGrid: View {
    let race: RacingRound
    @ScaledMetric private var timeSize: CGFloat = 22
    private var rows: Int { (race.sessions.count + 2) / 3 }
    private var height: CGFloat {
        let cellHeight: CGFloat = 53 + min(timeSize, 28) * 1.3
        return CGFloat(rows) * cellHeight + CGFloat(max(0, rows - 1)) * 8
    }
    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 8) {
                ForEach(0..<rows, id: \.self) { row in
                    RaceSessionGridRow(race: race, row: row, width: geometry.size.width / 3, timeSize: timeSize)
                }
            }
        }.frame(height: height)
    }
}

private struct RaceSessionGridRow: View {
    let race: RacingRound
    let row: Int
    let width, timeSize: CGFloat
    private var start: Int { row * 3 }
    private var count: Int { min(3, race.sessions.count - start) }
    var body: some View {
        HStack(spacing: 0) {
            ForEach(0..<3, id: \.self) { column in
                let index = start + column - (3 - count)
                Group {
                    if index >= start {
                        RaceSessionCell(race: race, session: race.sessions[index],
                                        titleSize: min(13, max(8, (width - 56) / 2)),
                                        dateSize: min(10, max(7, (width - 12) / 10)),
                                        clockSize: min(timeSize, (width - 12) / 3.2))
                    } else { Color.clear }
                }.frame(width: width, alignment: .leading)
                    .overlay(alignment: .trailing) {
                        if column < 2 { Rectangle().fill(Color(.systemGray5)).frame(width: 1).padding(.top, 8).allowsHitTesting(false) }
                    }
            }
        }
    }
}

private struct RaceSessionCell: View {
    let race: RacingRound
    let session: RaceSession
    let titleSize, dateSize, clockSize: CGFloat
    private var dateText: String {
        session.date.map { SportsDate.dayLabel($0) } ?? session.sourceDate
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 0) {
                Text(session.title).font(.system(size: titleSize, weight: .semibold)).lineLimit(1)
                Spacer(minLength: 0)
                if session.title == "正赛" {
                    ReminderButton(id: race.id, title: race.name + " · 正赛", start: session.date).frame(width: 44, height: 32)
                }
            }.frame(height: 32)
            Text(dateText).font(.system(size: dateSize)).foregroundStyle(.secondary).lineLimit(1)
            Text(session.date.map { $0.formatted(.dateTime.hour().minute()) } ?? "时间待定")
                .font(.system(size: clockSize, weight: .bold)).monospacedDigit().lineLimit(1)
        }.foregroundStyle(session.title == "正赛" ? Palette.red : .primary).padding(.horizontal, 6)
    }
}
