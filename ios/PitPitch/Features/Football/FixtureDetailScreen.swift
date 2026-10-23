import SwiftUI

struct FixtureDetailScreen: View {
    @State private var fixture: FootballFixture
    let repository: ArchiveRepository
    @State private var column = 0
    @State private var side = 0
    @State private var detail: MatchDetail?
    @State private var error: String?
    @State private var ready = false
    @State private var attempt = 0
    @Environment(RefreshCoordinator.self) private var refresh
    @Environment(\.scenePhase) private var scenePhase
    init(fixture: FootballFixture, repository: ArchiveRepository) {
        self._fixture = State(initialValue: fixture); self.repository = repository
        self._detail = State(initialValue: ViewSnapshots.shared.detail(fixture.id))
    }
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                HStack { Text(Competition.name(fixture.competition)); Spacer(); Text(fixture.timeLabel) }.font(.caption).foregroundStyle(.secondary)
                HStack(spacing: 14) {
                    team(fixture.home)
                    VStack(spacing: 8) {
                        Text(fixture.score ?? "—").font(.largeTitle.bold()).monospacedDigit()
                        Text(fixture.status).font(.subheadline).foregroundStyle(.secondary)
                        ReminderButton(id: fixture.id, title: fixture.home.name + " vs " + fixture.away.name, start: fixture.kickoff)
                    }.frame(minWidth: 84)
                    team(fixture.away)
                }
                UnderlineTabs(titles: ["数据与事件", "首发"], selection: $column).frame(maxWidth: .infinity, alignment: .leading)
                if column == 0 {
                    LabeledContent("场地", value: fixture.venue).font(.subheadline)
                    if let detail {
                        if !detail.statistics.isEmpty { MatchStatisticsView(fixture: fixture, rows: detail.statistics) }
                        MatchTimelineView(fixture: fixture, events: detail.events)
                    }
                    if ready, let detail, detail.statistics.isEmpty && detail.events.isEmpty { Text("暂无已发布数据与事件").font(.subheadline).foregroundStyle(.secondary).padding() }
                } else {
                    CapsuleSegmentedControl(
                        label: "首发球队",
                        titles: [fixture.home.name, fixture.away.name],
                        selection: $side,
                        identifierPrefix: "lineup-team"
                    )
                    if let detail {
                        let team = side == 0 ? fixture.home : fixture.away
                        let players = detail.players.filter { $0.team.id == team.id && $0.starter == true }
                        if !players.isEmpty { LineupPitch(players: players, formation: detail.formations[team.id] ?? "", team: team, repository: repository) }
                        else { Text("首发尚未公布").foregroundStyle(.secondary).padding() }
                    }
                }
                if let error { ArchiveFailure(message: error) { attempt += 1 } }
            }.padding(16)
        }
        .navigationTitle("比赛详情").navigationBarTitleDisplayMode(.inline).tint(Palette.green)
        .task(id: "score/\(refresh.revision)") {
            if let updated = try? await repository.football(competition: fixture.competition, season: fixture.season).items.first(where: { $0.id == fixture.id }), !Task.isCancelled { fixture = updated }
        }
        .task(id: "\(attempt)/\(scenePhase)/\(fixture.status)") {
            guard scenePhase == .active else { return }
            detail = (try? await repository.matchDetail(fixture)) ?? detail
            repeat {
                do {
                    let value = try await repository.matchDetail(fixture, refresh: true)
                    try Task.checkCancellation(); detail = value; ViewSnapshots.shared.retain(value, id: fixture.id); error = nil
                } catch is CancellationError { return }
                catch { if !Task.isCancelled { self.error = error.localizedDescription } }
                guard !Task.isCancelled else { return }; ready = true
                guard !["完场", "终止", "取消", "延期"].contains(fixture.status), fixture.kickoff.map({ $0 <= .now }) == true else { return }
                do { try await Task.sleep(for: .seconds(60)) } catch { return }
            } while !Task.isCancelled
        }
    }
    private func team(_ team: FootballTeam) -> some View {
        NavigationLink(value: AppRoute.team(team, fixture.season, fixture.competition)) {
            VStack(spacing: 12) { TeamBadge(team: team, size: 64); Text(team.name).font(.headline).multilineTextAlignment(.center) }.frame(maxWidth: .infinity).frame(minHeight: 48)
        }.buttonStyle(.plain).accessibilityIdentifier("team-from-match-\(fixture.competition)-\(team.id)")
    }
}

enum MatchPresentation {
    static let blue = Color(red: 22 / 255, green: 133 / 255, blue: 216 / 255)
    static let nodes: Set<String> = ["kickoff", "halftime", "start-2nd-half", "end-regular-time", "end-extra-time", "end-match", "stoppage-time"]

    // Each side has its own half-width limit and grows outward from the centre.
    static func fractions(home: String, away: String) -> (home: Double, away: Double)? {
        func value(_ text: String) -> Double? {
            guard let number = Double(text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "%", with: "")), number.isFinite, number >= 0 else { return nil }
            return number
        }
        guard let h = value(home), let a = value(away) else { return nil }
        let maximum = max(h, a)
        return maximum > 0 ? (h / maximum, a / maximum) : (0, 0)
    }

    static func visible(_ event: MatchEvent) -> Bool {
        event.type.hasPrefix("goal") || event.type.contains("own-goal") || event.type == "penalty---scored" || event.type.contains("red-card") || event.type.contains("second-yellow") || nodes.contains(event.type)
    }

    static func label(_ event: MatchEvent, finished: Bool) -> String {
        switch event.type {
        case "kickoff": return "开赛"
        case "halftime": return "上半场结束"
        case "start-2nd-half": return "下半场开始"
        case "end-regular-time": return finished ? "下半场结束" : "常规时间结束"
        case "end-extra-time": return "加时赛结束"
        case "end-match": return "结束"
        case "stoppage-time": return event.text
        default:
            if event.type.contains("second-yellow") { return "两黄变红" }
            if event.type.contains("red-card") { return "红牌" }
            if event.type.contains("own-goal") { return "乌龙球" }
            return event.type == "penalty---scored" ? "点球进球" : "进球"
        }
    }
}

private struct MatchStatisticsView: View {
    let fixture: FootballFixture
    let rows: [MatchStatistic]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("技术统计").font(.title3.bold()).padding(.bottom, 12)
            VStack(spacing: 0) {
                HStack(spacing: 16) {
                    statisticTeam(fixture.home)
                    statisticTeam(fixture.away)
                }.padding(.bottom, 12)
                ForEach(rows) { stat in
                    VStack(spacing: 8) {
                        HStack(spacing: 0) {
                            Text(stat.home).frame(maxWidth: .infinity, alignment: .leading)
                            Text(stat.title).font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity)
                            Text(stat.away).frame(maxWidth: .infinity, alignment: .trailing)
                        }.font(.system(.headline, design: .default)).monospacedDigit()
                        if let fractions = MatchPresentation.fractions(home: stat.home, away: stat.away) {
                            HStack(spacing: 4) {
                                bar(fractions.home, home: true)
                                bar(fractions.away, home: false)
                            }.frame(height: 5).accessibilityHidden(true)
                        }
                    }.padding(.vertical, 10)
                }
            }.padding(.horizontal, 16).padding(.vertical, 12)
        }.accessibilityElement(children: .contain).accessibilityIdentifier("match-statistics")
    }

    private func statisticTeam(_ team: FootballTeam) -> some View {
        HStack(spacing: 6) {
            TeamBadge(team: team, size: 24)
            Text(team.name).font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }

    private func bar(_ fraction: Double, home: Bool) -> some View {
        GeometryReader { geometry in
            Capsule().fill(MatchPresentation.blue)
                .frame(width: geometry.size.width * fraction)
                .frame(maxWidth: .infinity, alignment: home ? .trailing : .leading)
        }
    }
}

private struct MatchTimelineView: View {
    let fixture: FootballFixture
    let events: [MatchEvent]
    private var visible: [MatchEvent] { events.filter(MatchPresentation.visible) }

    var body: some View {
        if !visible.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                Text("比赛事件").font(.title3.bold()).padding(.bottom, 12)
                HStack(spacing: 0) {
                    timelineTeam(fixture.home)
                    Spacer().frame(width: 56)
                    timelineTeam(fixture.away)
                }.padding(.vertical, 8)
                VStack(spacing: 0) {
                    ForEach(Array(visible.enumerated()), id: \.element.id) { index, event in
                        eventRow(event)
                            .background {
                                GeometryReader { geometry in
                                    Path { path in
                                        let x = geometry.size.width / 2
                                        path.move(to: CGPoint(x: x, y: index == 0 ? geometry.size.height / 2 : 0))
                                        path.addLine(to: CGPoint(x: x, y: index == visible.count - 1 ? geometry.size.height / 2 : geometry.size.height))
                                    }.stroke(Color(white: 0.94), lineWidth: 1)
                                }
                            }
                            .accessibilityElement(children: .contain)
                            .accessibilityIdentifier("detail-event-\(event.id)")
                    }
                }
            }.accessibilityElement(children: .contain).accessibilityIdentifier("match-timeline")
        }
    }

    private func timelineTeam(_ team: FootballTeam) -> some View {
        HStack(spacing: 6) {
            TeamBadge(team: team, size: 20)
            Text(team.name).font(.caption)
        }.frame(maxWidth: .infinity)
    }

    @ViewBuilder private func eventRow(_ event: MatchEvent) -> some View {
        if MatchPresentation.nodes.contains(event.type) {
            stageNode(event).frame(maxWidth: .infinity).padding(.vertical, 12)
        } else if event.teamID == fixture.home.id || event.teamID == fixture.away.id {
            HStack(alignment: .center, spacing: 0) {
                sideCard(event, teamID: fixture.home.id)
                Text(event.clock.isEmpty ? "—" : event.clock)
                    .font(.subheadline.bold()).monospacedDigit()
                    .lineLimit(1).minimumScaleFactor(0.5)
                    .frame(width: 56).padding(.vertical, 6).background(.white)
                sideCard(event, teamID: fixture.away.id)
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(.vertical, 6)
        } else {
            eventCard(event).padding(.vertical, 6)
        }
    }

    private func sideCard(_ event: MatchEvent, teamID: String) -> some View {
        VStack(spacing: 0) {
            if event.teamID == teamID { eventCard(event) }
        }.frame(maxWidth: .infinity)
    }

    private func stageNode(_ event: MatchEvent) -> some View {
        VStack(spacing: 6) {
            if event.type == "kickoff" || event.type == "end-match" {
                whistle(label: MatchPresentation.label(event, finished: fixture.status == "完场"))
            } else {
                Text(MatchPresentation.label(event, finished: fixture.status == "完场") + (event.sourceScore.map { " " + $0 } ?? ""))
                    .font(.subheadline).multilineTextAlignment(.center)
                    .padding(.horizontal, 10).padding(.vertical, 4).background(.white)
                if event.type == "end-regular-time", fixture.status == "完场" { whistle(label: "结束") }
            }
        }.accessibilityIdentifier("event-node-\(event.id)")
    }

    private func whistle(label: String) -> some View {
        WhistleIcon().frame(width: 22, height: 22)
            .background(.white)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(label).accessibilityAddTraits(.isImage)
            .accessibilityIdentifier("timeline-whistle-\(label)")
    }

    private func eventCard(_ event: MatchEvent) -> some View {
        let label = MatchPresentation.label(event, finished: fixture.status == "完场")
        let red = event.type.contains("red-card") || event.type.contains("second-yellow")
        return HStack(alignment: .top, spacing: 6) {
            Group {
                if red { RoundedRectangle(cornerRadius: 1).fill(Palette.red).frame(width: 10, height: 14) }
                else { Image(systemName: "soccerball").font(.system(size: 18)) }
            }.frame(width: 18, height: 18)
            VStack(alignment: .leading, spacing: 4) {
                // The source's first participant is the scorer; keep all participants in the model.
                Text((event.player.components(separatedBy: " · ").first.flatMap { $0.isEmpty ? nil : $0 + " " } ?? "") + label)
                    .font(.caption).lineLimit(1).minimumScaleFactor(0.1)
                if let score = event.sourceScore { Text("[\(score)]").font(.caption).foregroundStyle(MatchPresentation.blue) }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 8).padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
        .background(Color(red: 247 / 255, green: 248 / 255, blue: 250 / 255), in: RoundedRectangle(cornerRadius: 4))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("event-content-\(event.id)")
    }
}

// A vector whistle keeps stage markers visible on systems without a whistle SF Symbol.
private struct WhistleIcon: View {
    var body: some View {
        Canvas { context, size in
            context.scaleBy(x: size.width / 24, y: size.height / 24)
            var body = Path()
            body.move(to: CGPoint(x: 10, y: 7))
            body.addLine(to: CGPoint(x: 22, y: 7))
            body.addLine(to: CGPoint(x: 22, y: 10))
            body.addLine(to: CGPoint(x: 16, y: 10))
            body.addCurve(to: CGPoint(x: 16, y: 13), control1: CGPoint(x: 16, y: 11), control2: CGPoint(x: 16, y: 12))
            body.addArc(center: CGPoint(x: 10.5, y: 13), radius: 5.5, startAngle: .degrees(0), endAngle: .degrees(270), clockwise: false)
            body.closeSubpath()
            context.fill(body, with: .foreground)
            let ring = Path(ellipseIn: CGRect(x: 2, y: 6, width: 5, height: 5))
            context.stroke(ring, with: .foreground, lineWidth: 2)
            context.fill(Path(ellipseIn: CGRect(x: 8, y: 10.5, width: 5, height: 5)), with: .color(.white))
            context.fill(Path(ellipseIn: CGRect(x: 9.5, y: 12, width: 2, height: 2)), with: .foreground)
        }
    }
}

private struct LineupPitch: View {
    let players: [FootballPlayer]
    let formation: String
    let team: FootballTeam
    let repository: ArchiveRepository
    @State private var color = Palette.green
    private var verified: [[FootballPlayer]]? { LineupLayout.published(players, formation: formation) }
    private var rows: [[FootballPlayer]] { Array((verified ?? LineupLayout.schematic(players, formation: formation)).reversed()) }
    var body: some View {
        VStack(spacing: 12) {
            HStack { if !formation.isEmpty { Text(formation).font(.headline) }; Spacer(); if verified == nil { Text("站位示意").font(.caption).foregroundStyle(.secondary) } }
            VStack(spacing: 26) {
                ForEach(rows.indices, id: \.self) { row in
                    HStack(alignment: .top, spacing: 4) {
                        ForEach(rows[row]) { player in
                            VStack(spacing: 5) {
                                ZStack { Image(systemName: "tshirt.fill").font(.system(size: 34)).foregroundStyle(color).shadow(color: .black.opacity(0.25), radius: 1); Text(player.number).font(.caption.bold()).foregroundStyle(.white).shadow(color: .black, radius: 1) }
                                Text(player.name).font(.caption2.weight(.medium)).multilineTextAlignment(.center).foregroundStyle(.white).fixedSize(horizontal: false, vertical: true)
                            }.frame(maxWidth: .infinity).accessibilityElement(children: .combine)
                        }
                    }
                }
            }.padding(.vertical, 24).padding(.horizontal, 8).frame(maxWidth: .infinity)
                .background(Color(red: 0.12, green: 0.43, blue: 0.24))
                .overlay(Rectangle().stroke(.white.opacity(0.6), lineWidth: 1).padding(8))
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }.task { if let hex = await repository.teamColor(team.id) { color = Color(hex: hex) } }
    }
}
