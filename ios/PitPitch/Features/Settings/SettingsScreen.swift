import SwiftUI

struct SettingsScreen: View {
    let repository: ArchiveRepository
    @State private var updates = AppUpdateChecker()
    @AppStorage("homeCompetitions") private var selected = "eng.1,uefa.champions,f1"
    var body: some View {
        Form {
            Section("主队选择") {
                NavigationLink("选择主队") { TeamSelectionScreen(repository: repository) }
            }
            Section("首页展示") {
                ForEach(Competition.all + [Competition(id: "f1", name: "F1")]) { item in
                    Toggle(item.name, isOn: Binding(get: { selected.split(separator: ",").contains(Substring(item.id)) }, set: { enabled in
                        var values = Set(selected.split(separator: ",").map(String.init))
                        if enabled { values.insert(item.id) } else { values.remove(item.id) }
                        selected = values.sorted().joined(separator: ",")
                    }))
                }
            }
            Section("更新与关于") {
                Button { Task { await updates.check() } } label: {
                    HStack { Text("检查更新"); Spacer(); Text(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "未知").foregroundStyle(.secondary) }
                }.disabled(updates.checking).accessibilityIdentifier("checkForUpdates")
                if let result = updates.result { Text(result).font(.caption).foregroundStyle(.secondary) }
                if let url = updates.storeURL { Link("前往 App Store 更新", destination: url) }
                NavigationLink("隐私和关于") { AboutScreen() }
            }
        }.navigationTitle("设置").navigationBarTitleDisplayMode(.inline).tint(Palette.green)
    }
}
private struct AboutScreen: View {
    private let dataSources = [
        AboutRow(label: "足球赛事", value: "ESPN"),
        AboutRow(label: "F1 赛事", value: "Jolpica"),
        AboutRow(label: "名称与轮次", value: "Wikidata、OpenFootball、FixtureDownload、央视、曼联中文官网"),
        AboutRow(label: "图片与赛道", value: "Formula 1、FlagCDN、Wikimedia Commons、bacinger/f1-circuits 及各图片权利人")
    ]
    private let licenses = [
        AboutRow(label: "CC0 / 公有领域", value: "Wikidata、OpenFootball，以及采用此许可的 Wikimedia Commons 资源。"),
        AboutRow(label: "MIT", value: "bacinger/f1-circuits\n© 2019–2025 Tomislav Bacinger"),
        AboutRow(label: "CC BY-SA 3.0 / 4.0", value: "Wikimedia Commons 赛道轮廓；原作者、来源、改编说明及许可原文随资源保留。"),
        AboutRow(label: "SIL Open Font License 1.1", value: "Roboto 启动字标字形\n© 2011 The Roboto Project Authors"),
        AboutRow(label: "其他图片与商标", value: "版权归各权利人，按各自许可使用；公开访问不代表开源授权。")
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                AboutSection(title: "数据来源", identifier: "settings-data-sources", rows: dataSources)
                AboutSection(title: "开源协议", identifier: "settings-open-source", rows: licenses)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
        }
        .background(Color(uiColor: .systemBackground))
        .navigationTitle("隐私和关于")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct AboutSection: View {
    let title: String
    let identifier: String
    let rows: [AboutRow]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title)
                .font(.title3.weight(.semibold))
            ForEach(rows) { row in
                VStack(alignment: .leading, spacing: 4) {
                    Text(row.label)
                        .font(.subheadline.weight(.medium))
                    Text(row.value)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineSpacing(3)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(identifier)
    }
}

private struct AboutRow: Identifiable {
    var id: String { label }
    let label: String
    let value: String
}

private struct TeamSelectionScreen: View {
    let repository: ArchiveRepository
    @AppStorage("homeTeamID") private var teamID = "360"
    @Environment(\.dismiss) private var dismiss
    @State private var teams: [FootballTeam] = []
    @State private var search = ""
    @State private var visibleLetter = ""
    @State private var scrolling = false
    private var filtered: [FootballTeam] { teams.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) || $0.originalName.localizedCaseInsensitiveContains(search) } }
    var body: some View {
        ScrollView {
        LazyVStack(spacing: 0) {
        ForEach(filtered) { team in
            Button { teamID = team.id; dismiss() } label: {
                HStack(spacing: 14) {
                    TeamBadge(team: team, size: 32)
                    Text(team.name).foregroundStyle(.primary)
                    Spacer()
                    if team.id == teamID { Image(systemName: "checkmark").foregroundStyle(Palette.green) }
                }.frame(minHeight: 44)
            }.padding(.horizontal, 16).padding(.vertical, 7).id(team.id)
            Divider().padding(.leading, 62)
        }
        }.scrollTargetLayout()
        }
        .onScrollTargetVisibilityChange(idType: String.self) { ids in
            if let team = filtered.first(where: { ids.contains($0.id) }) { visibleLetter = String((team.name.applyingTransform(.toLatin, reverse: false) ?? team.name).folding(options: .diacriticInsensitive, locale: Locale(identifier: "en_US_POSIX")).prefix(1)).uppercased() }
        }
        .onScrollPhaseChange { _, phase in withAnimation(.easeOut(duration: 0.4)) { scrolling = phase != .idle } }
        .overlay(alignment: .trailing) { Text(visibleLetter).font(.title3.bold()).padding(12).background(.thinMaterial, in: .capsule).padding(.trailing, 8).opacity(scrolling ? 1 : 0).accessibilityHidden(true) }
        .navigationTitle("选择主队").navigationBarTitleDisplayMode(.inline)
        .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索球队")
        .task { teams = (try? await repository.teams(season: SportsDate.currentFootballSeason())) ?? [] }
    }
}
