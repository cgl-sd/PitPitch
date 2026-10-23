import SwiftUI

struct UnderlineTabs: View {
    let titles: [String]
    @Binding var selection: Int
    var color = Palette.green
    var reselect: ((Int) -> Void)? = nil
    @ScaledMetric(relativeTo: .subheadline) private var scaledFont: CGFloat = 15
    var body: some View {
        GeometryReader { geometry in
            let spacing: CGFloat = 4
            let font = commonFont(width: geometry.size.width, spacing: spacing)
            HStack(spacing: spacing) {
                ForEach(titles.indices, id: \.self) { index in
                    Button { if selection == index { reselect?(index) }; selection = index } label: {
                        VStack(spacing: 5) {
                            Text(titles[index]).font(.system(size: font, weight: selection == index ? .semibold : .regular)).lineLimit(1)
                            Capsule().fill(selection == index ? color : .clear).frame(width: 18, height: 3)
                        }.frame(width: (geometry.size.width - spacing * CGFloat(max(0, titles.count - 1))) / CGFloat(titles.count), height: 48).contentShape(Rectangle())
                    }.buttonStyle(.plain).foregroundStyle(selection == index ? color : .primary)
                        .accessibilityIdentifier("column-" + titles.joined(separator: "-") + "-\(index)")
                        .accessibilityAddTraits(selection == index ? .isSelected : [])
                }
            }
        }.frame(height: 48).frame(maxWidth: .infinity)
    }
    private func commonFont(width: CGFloat, spacing: CGFloat) -> CGFloat {
        var low: CGFloat = 1, high = min(scaledFont, 32)
        for _ in 0..<20 {
            let mid = (low + high) / 2
            let required = CGFloat(titles.count) * (CGFloat(titles.map(\.count).max() ?? 1) * mid + 8) + spacing * CGFloat(max(0, titles.count - 1))
            if required <= width { low = mid } else { high = mid }
        }
        return low
    }

}
struct FootballBoards: View {
    let repository: ArchiveRepository
    let competition: String
    let season: Int
    let players: Bool
    var reload = 0
    @State private var snapshots = ViewSnapshots.shared
    @Environment(RefreshCoordinator.self) private var refresh
    @Environment(\.dynamicTypeSize) private var typeSize
    @ScaledMetric(relativeTo: .subheadline) private var rankWidth: CGFloat = 24
    private var scope: String { "\(competition)/\(season)" }
    private var tables: [FootballTable] {
        get { snapshots.footballTables[scope] ?? [] }
        nonmutating set { snapshots.footballTables[scope] = newValue }
    }
    private var roster: [FootballPlayer] {
        get { snapshots.footballPlayers[scope] ?? [] }
        nonmutating set { snapshots.footballPlayers[scope] = newValue }
    }
    @State private var mode = 0
    @State private var error: String?
    @State private var ready = false
    @State private var retry = 0
    var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                if players {
                    CapsuleSegmentedControl(
                        label: "球员榜",
                        titles: ["进球榜", "助攻榜"],
                        selection: $mode,
                        identifierPrefix: "player-board"
                    ).padding(.vertical, 8)
                    ForEach(Array(ranked.enumerated()), id: \.element.id) { index, player in
                        HStack(spacing: 12) {
                            Text(String(index + 1)).frame(width: rankWidth).foregroundStyle(.secondary)
                            VStack(alignment: .leading, spacing: 5) {
                                Text(player.name).font(.subheadline.weight(.semibold))
                                HStack { TeamBadge(team: player.team, size: 18); Text(player.team.name).font(.caption).foregroundStyle(.secondary) }
                            }
                            Spacer()
                            Text((mode == 0 ? player.goals : player.assists).map { $0.formatted(.number.precision(.fractionLength(0...1))) } ?? "—").font(.title3.bold()).monospacedDigit()
                        }.padding(.vertical, 12)
                        Divider()
                    }
                } else {
                    ForEach(tables) { table in
                        if tables.count > 1 { Text(table.name).font(.headline).frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 12) }
                        if typeSize.isAccessibilitySize { Text("球队").font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 10) }
                        else { HStack {
                            Text("#").frame(width: 24); Text("球队").frame(maxWidth: .infinity, alignment: .leading)
                            Text("赛").frame(width: 26); Text("胜/平/负").frame(width: 62); Text("进/失").frame(width: 44); Text("积分").frame(width: 38)
                        }.font(.caption2).foregroundStyle(.secondary).padding(.vertical, 10) }
                        ForEach(table.rows) { row in
                            NavigationLink(value: AppRoute.team(row.team, season, competition)) {
                                if typeSize.isAccessibilitySize {
                                    VStack(alignment: .leading, spacing: 8) {
                                        HStack(spacing: 12) { Text(String(row.rank)).font(.headline).fixedSize(); TeamBadge(team: row.team, size: 24); Text(row.team.name).font(.headline).fixedSize(horizontal: false, vertical: true) }
                                        HStack(alignment: .firstTextBaseline) { Text("积分").font(.caption); Text(row.points).font(.title3.bold()).monospacedDigit() }
                                        Text("已赛 \(row.played) · 胜 \(row.won) · 平 \(row.drawn) · 负 \(row.lost)").font(.caption).fixedSize(horizontal: false, vertical: true)
                                        Text("进球 \(row.goalsFor) · 失球 \(row.goalsAgainst)").font(.caption).fixedSize(horizontal: false, vertical: true)
                                    }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 12).foregroundStyle(.primary)
                                } else {
                                HStack(spacing: 0) {
                                    Text(String(row.rank)).frame(width: 24).foregroundStyle(.secondary)
                                    HStack(spacing: 6) { TeamBadge(team: row.team, size: 24); Text(row.team.name).font(.caption).fixedSize(horizontal: false, vertical: true) }.frame(maxWidth: .infinity, alignment: .leading)
                                    Text(row.played).frame(width: 26)
                                    Text("\(row.won)/\(row.drawn)/\(row.lost)").frame(width: 62)
                                    Text("\(row.goalsFor)/\(row.goalsAgainst)").frame(width: 44)
                                    Text(row.points).fontWeight(.bold).frame(width: 38)
                                }.font(.caption2).foregroundStyle(.primary).padding(.vertical, 12)
                                }
                            }.buttonStyle(.plain).accessibilityIdentifier("standing-\(competition)-\(season)-\(row.id)")
                                .overlay(alignment: .leading) {
                                    if let color = zone(row.note) {
                                        Rectangle().fill(color).frame(width: 2).padding(.vertical, 8)
                                            .offset(x: -5).allowsHitTesting(false)
                                    }
                                }
                            Divider()
                        }
                    }
                    if !tables.isEmpty { Text(rules).font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 16) }
                }
                if ready && (players ? ranked.isEmpty : tables.isEmpty) && error == nil { Text("暂无已发布数据").foregroundStyle(.secondary).padding() }
                if let error { ArchiveFailure(message: error) { retry += 1 } }
            }.padding(.horizontal, 16)
        }
        .task(id: "cache/\(competition)/\(season)/\(players)/\(refresh.revision)") {
            if players, let values = try? await repository.leaders(competition: competition, season: season) { if !Task.isCancelled { roster = values } }
            else if !players, let values = try? await repository.tables(competition: competition, season: season) { if !Task.isCancelled { tables = values } }
        }
        .task(id: "\(competition)/\(season)/\(players)/\(retry)/\(reload)") {
            ready = false; error = nil
            if players { roster = (try? await repository.leaders(competition: competition, season: season)) ?? roster }
            else { tables = (try? await repository.tables(competition: competition, season: season)) ?? tables }
            guard !Task.isCancelled else { return }; ready = players ? !roster.isEmpty : !tables.isEmpty
            do {
                if players { let fresh = try await repository.leaders(competition: competition, season: season, refresh: true); try Task.checkCancellation(); roster = fresh }
                else { let fresh = try await repository.tables(competition: competition, season: season, refresh: true); try Task.checkCancellation(); tables = fresh }
            } catch is CancellationError { } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
            if !Task.isCancelled { ready = true }
        }
    }
    private var ranked: [FootballPlayer] {
        FootballRanking.ranked(roster, mode: mode)
    }
    private func zone(_ note: String) -> Color? {
        let text = note.lowercased()
        if text.contains("relegat") { return .red }
        if text.contains("champions") { return .blue }
        if text.contains("conference") { return .green }
        if text.contains("europa") { return .orange }
        return nil
    }
    private var rules: String {
        let sorting: String = ["esp.1", "ita.1"].contains(competition) ? "同分先比较相互战绩。" : "同分主要比较净胜球、进球数。"
        if competition.hasPrefix("uefa") { return season >= 2024 ? "胜3分、平1分、负0分；同分先比较净胜球、进球数。前8名直入十六强，第9–24名进入附加赛。" : "胜3分、平1分、负0分；同分主要比较相互战绩。小组前两名晋级淘汰赛。" }
        return "胜3分、平1分、负0分；" + sorting + (competition == "ger.1" ? "前列获得欧战资格；末两名降级，倒数第三参加附加赛。" : competition == "fra.1" && season >= 2023 ? "前列获得欧战资格；末两名降级，倒数第三参加附加赛。" : "前列获得欧战资格；末三名降级。")
    }
}

enum FootballRanking {
    static func ranked(_ players: [FootballPlayer], mode: Int) -> [FootballPlayer] {
        var unique: [String: FootballPlayer] = [:]
        for player in players {
            guard let previous = unique[player.id] else {
                unique[player.id] = player
                continue
            }
            let candidateValue = mode == 0 ? player.goals : player.assists
            let previousValue = mode == 0 ? previous.goals : previous.assists
            if (candidateValue ?? -Double.infinity) > (previousValue ?? -Double.infinity) {
                unique[player.id] = player
            }
        }
        return unique.values.sorted { lhs, rhs in
            let left = mode == 0 ? lhs.goals : lhs.assists
            let right = mode == 0 ? rhs.goals : rhs.assists
            switch (left, right) {
            case let (left?, right?):
                if left != right { return left > right }
            case (.some, nil):
                return true
            case (nil, .some):
                return false
            case (nil, nil):
                break
            }
            return lhs.name.localizedStandardCompare(rhs.name) == .orderedAscending
        }
    }
}
