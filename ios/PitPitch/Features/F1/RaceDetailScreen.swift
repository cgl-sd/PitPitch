import SwiftUI

struct RaceDetailScreen: View {
    let race: RacingRound
    let repository: ArchiveRepository
    @State private var column = 0
    @State private var results: [RacingResult] = []
    @State private var qualifying: [RacingResult] = []
    @State private var raceKnown = false
    @State private var qualifyingKnown = false
    @State private var error: String?
    @State private var attempt = 0
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                TrackOutline(race: race).frame(maxWidth: .infinity).frame(height: 220)
                HStack(spacing: 12) {
                    CountryFlag(country: race.country)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(race.name).font(.system(size: 19, weight: .semibold))
                        Text("\(String(race.season)) · 第 \(race.round) 站 · \(race.circuit)")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                }.padding(.vertical, 8)
                CapsuleSegmentedControl(
                    label: "分站栏目",
                    titles: ["赛程", "排位", "正赛"],
                    selection: $column,
                    accent: Palette.red,
                    identifierPrefix: "race-detail"
                )
                if column == 0 {
                    ForEach(race.sessions) { session in
                        HStack {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(session.title).font(.headline)
                                Text(SportsDate.label(session.date, fallback: session.sourceDate)).font(.subheadline)
                            }
                            Spacer()
                            ReminderButton(id: session.title == "正赛" ? race.id : session.id, title: race.name + " · " + session.title, start: session.date)
                        }.foregroundStyle(session.title == "正赛" ? Palette.red : .primary).padding(.vertical, 8)
                        Divider()
                    }
                } else {
                    HStack { Text("名次").frame(width: 32); Text("车手"); Spacer(); Text(column == 1 ? "圈速" : "成绩 / 积分") }.font(.caption).foregroundStyle(.secondary)
                    ForEach(column == 1 ? qualifying : results) { row in
                        HStack(spacing: 10) {
                            Text(String(row.rank)).font(.headline).frame(width: 32)
                            Rectangle().fill(F1Colors.color(row.constructorID, season: race.season)).frame(width: 3, height: 28)
                            VStack(alignment: .leading, spacing: 4) { Text(row.name).font(.subheadline.weight(.semibold)); Text(row.constructor).font(.caption).foregroundStyle(.secondary) }
                            Spacer()
                            VStack(alignment: .trailing, spacing: 4) { Text(row.result).font(.subheadline).monospacedDigit(); if column == 2 { Text(row.points).font(.caption.bold()).monospacedDigit() } }
                        }.padding(.vertical, 10)
                        Divider()
                    }
                    if (column == 1 ? qualifyingKnown : raceKnown) && (column == 1 ? qualifying : results).isEmpty { Text("暂无已发布成绩").foregroundStyle(.secondary).padding() }
                }
                if let error { ArchiveFailure(message: error) { attempt += 1 } }
            }.padding(16)
        }.navigationTitle("F1 分站详情").navigationBarTitleDisplayMode(.inline).tint(Palette.red)
        .task(id: attempt) {
            if let value = try? await repository.results(race: race, qualifying: false) { results = value; raceKnown = true }
            if let value = try? await repository.results(race: race, qualifying: true) { qualifying = value; qualifyingKnown = true }
            async let raceResults = repository.results(race: race, qualifying: false, refresh: true)
            async let qualResults = repository.results(race: race, qualifying: true, refresh: true)
            do { let value = try await raceResults; try Task.checkCancellation(); results = value; raceKnown = true } catch is CancellationError { } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
            do { let value = try await qualResults; try Task.checkCancellation(); qualifying = value; qualifyingKnown = true } catch is CancellationError { } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
        }
    }
}
