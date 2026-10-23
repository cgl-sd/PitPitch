import SwiftUI
import UIKit

enum Palette {
    static let red = Color(red: 0.88, green: 0.08, blue: 0.10)
    static let green = Color(red: 0.10, green: 0.48, blue: 0.28)
}

struct CapsuleSegmentedControl: View {
    let label: String
    let titles: [String]
    @Binding var selection: Int
    var accent = Palette.green
    var identifierPrefix: String
    @ScaledMetric(relativeTo: .subheadline) private var scaledFont: CGFloat = 13
    private let controlHeight: CGFloat = 44

    var body: some View {
        GeometryReader { geometry in
            let segmentWidth = geometry.size.width / CGFloat(max(1, titles.count))
            let font = fittedFont(for: segmentWidth)
            HStack(spacing: 0) {
                ForEach(titles.indices, id: \.self) { index in
                    Button {
                        selection = index
                    } label: {
                        Text(titles[index])
                            .font(.system(size: font, weight: selection == index ? .semibold : .regular))
                            .foregroundStyle(selection == index ? accent : Color.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.08)
                            .allowsTightening(true)
                            .padding(.horizontal, 6)
                            .frame(width: segmentWidth, height: controlHeight)
                            .background(selection == index ? Color.white : Color.clear, in: Capsule())
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(titles[index])
                    .accessibilityIdentifier("\(identifierPrefix)-\(index)")
                    .accessibilityAddTraits(selection == index ? .isSelected : [])
                }
            }
            .frame(width: geometry.size.width, height: controlHeight)
            .background(Color(.secondarySystemFill))
            .clipShape(Capsule())
            .overlay(Capsule().stroke(Color(.separator).opacity(0.35), lineWidth: 1))
        }
        .frame(height: controlHeight)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(label)
    }

    private func fittedFont(for segmentWidth: CGFloat) -> CGFloat {
        let availableTextWidth = max(1, segmentWidth - 12)
        let widestText = titles.map {
            ($0 as NSString).size(withAttributes: [.font: UIFont.systemFont(ofSize: scaledFont, weight: .semibold)]).width
        }.max() ?? 1
        return min(scaledFont, max(8, scaledFont * availableTextWidth / max(1, widestText)))
    }
}

struct PitPitchWordmark: View {
    @ScaledMetric(relativeTo: .title2) private var brandWidth: CGFloat = 110
    var body: some View {
        Text("\(Text("Pit").foregroundColor(Palette.red))\(Text("Pitch").foregroundColor(Palette.green))")
            .font(.title2.weight(.black).italic()).tracking(-1)
            .lineLimit(1).minimumScaleFactor(0.4).frame(width: min(190, brandWidth), alignment: .leading).fixedSize(horizontal: false, vertical: true)
            .accessibilityLabel("PitPitch").accessibilityIdentifier("pitpitchWordmark")
    }
}

private struct AssetRootKey: EnvironmentKey {
    static let defaultValue: URL? = nil
}
extension EnvironmentValues {
    var assetRoot: URL? {
        get { self[AssetRootKey.self] }
        set { self[AssetRootKey.self] = newValue }
    }
}

actor AssetDataCache {
    static let shared = AssetDataCache()
    private var cache: [URL: Data] = [:]
    private var order: [URL] = []
    func data(_ url: URL) -> Data? {
        if let value = cache[url] { return value }
        guard let data = try? Data(contentsOf: url), data.count <= 4 * 1_024 * 1_024 else { return nil }
        cache[url] = data
        order.append(url)
        while order.count > 128 { cache.removeValue(forKey: order.removeFirst()) }
        return data
    }
}

struct TeamBadge: View {
    let team: FootballTeam
    var size: CGFloat = 24
    @Environment(\.assetRoot) private var root
    @State private var data: Data?
    var body: some View {
        Group {
            if let data, let image = UIImage(data: data) {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                RoundedRectangle(cornerRadius: 5).fill(Color(.systemGray6))
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
        .task(id: (team.badgePath ?? "") + (team.badgeURL ?? "")) {
            data = nil
            guard let root else { return }
            let local = if let path = team.badgePath { await AssetDataCache.shared.data(root.appendingPathComponent(path)) } else { nil as Data? }
            let result: Data?
            if let local { result = local } else if let url = team.badgeURL { result = await BadgeImageStore.shared.image(url) } else { result = nil }
            guard !Task.isCancelled else { return }
            data = result
        }
    }
}

// Ratios measured between the reference screenshots' horizontal separators.
// Intrinsic content remains the lower bound for narrow screens and Dynamic Type.
struct ProportionalScheduleRow: Layout {
    let heightRatio: CGFloat
    var horizontalInset: CGFloat = 0
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let content = subviews.first else { return .zero }
        let width = proposal.width ?? content.sizeThatFits(.unspecified).width
        let intrinsic = content.sizeThatFits(ProposedViewSize(width: width, height: nil)).height
        return CGSize(width: width, height: max(intrinsic, max(0, width - horizontalInset) * heightRatio))
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: CGPoint(x: bounds.midX, y: bounds.midY), anchor: .center,
                             proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
    }
}

struct FixtureRow: View {
    let fixture: FootballFixture
    var topPadding: CGFloat = 24
    var body: some View {
        ProportionalScheduleRow(heightRatio: 121.0 / 490.0) {
            VStack(spacing: 4) {
                HStack {
                    Text(fixture.timeLabel)
                        .font(.caption)
                        .accessibilityIdentifier("fixture-time-\(fixture.id)")
                    Spacer()
                    if fixture.status != "待赛" { Text(fixture.status).font(.caption2) }
                }.foregroundStyle(.secondary)
                HStack(spacing: 0) {
                    HStack(spacing: 8) {
                        Text(fixture.home.name).font(.subheadline)
                            .lineLimit(1).minimumScaleFactor(0.6)
                            .frame(minWidth: 0, maxWidth: .infinity, alignment: .trailing)
                        TeamBadge(team: fixture.home)
                    }.frame(minWidth: 0, maxWidth: .infinity)
                    Group {
                        if fixture.status == "待赛" { ReminderButton(id: fixture.id, title: fixture.home.name + " vs " + fixture.away.name, start: fixture.kickoff) }
                        else { Text(fixture.score ?? "—").font(.headline).monospacedDigit().foregroundStyle(fixture.status == "完场" ? Palette.green : .secondary) }
                    }.frame(width: 68)
                    HStack(spacing: 8) {
                        TeamBadge(team: fixture.away)
                        Text(fixture.away.name).font(.subheadline)
                            .lineLimit(1).minimumScaleFactor(0.6)
                            .frame(minWidth: 0, maxWidth: .infinity, alignment: .leading)
                    }.frame(minWidth: 0, maxWidth: .infinity)
                }.frame(minHeight: 48)
            }
            .padding(.top, topPadding)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .foregroundStyle(.primary)
            .contentShape(Rectangle())
        }
        .accessibilityElement(children: .contain)
    }
}

struct ArchiveFailure: View {
    let message: String
    let retry: () -> Void
    var body: some View {
        VStack(spacing: 12) {
            Text(message).foregroundStyle(.secondary)
            Button("重试", action: retry).buttonStyle(.bordered)
        }.frame(maxWidth: .infinity).padding()
    }
}

struct BrandImage: View {
    let name: String
    var body: some View {
        if let url = Bundle.main.url(forResource: name, withExtension: "png", subdirectory: "branding/drawable-nodpi"),
           let image = UIImage(contentsOfFile: url.path) {
            Image(uiImage: image).resizable().scaledToFit()
        }
    }
}

struct LocalMedia: View {
    let path: String?
    var crop = false
    @Environment(\.assetRoot) private var root
    @State private var data: Data?
    var body: some View {
        GeometryReader { geometry in
            Group {
                if let data, let image = UIImage(data: data) {
                    if crop { Image(uiImage: image).resizable().scaledToFill() }
                    else { Image(uiImage: image).resizable().scaledToFit() }
                } else { Rectangle().fill(Color(.systemGray6)) }
            }.frame(width: geometry.size.width, height: geometry.size.height).clipped()
        }
        .task(id: path) {
            data = nil
            guard let root, let path else { return }
            let value = await AssetDataCache.shared.data(root.appendingPathComponent(path))
            guard !Task.isCancelled else { return }
            data = value
        }
    }
}

struct TrackOutline: View {
    let race: RacingRound
    var body: some View {
        if let name = race.layoutFile,
           let url = Bundle.main.url(forResource: String(name.dropLast(4)), withExtension: "png", subdirectory: "MediaDerived"),
           let image = UIImage(contentsOfFile: url.path) {
            Image(uiImage: image).resizable().scaledToFit().accessibilityLabel(race.circuit + "赛道布局图")
        } else { Color.clear.accessibilityLabel("赛道布局图暂缺") }
    }
}

enum CompetitionBrand {
    static let names = ["eng.1": "league_premier", "esp.1": "league_laliga", "ita.1": "league_serie_a", "ger.1": "league_bundesliga", "fra.1": "league_ligue_1", "uefa.champions": "league_champions", "uefa.europa": "league_europa"]
}

// Root branding uses the same 16pt content edge and 52pt bar on all three tabs.
struct RootBrandBar: View {
    var kind: Int
    var body: some View {
        HStack(spacing: 0) {
            if kind == 0 { PitPitchWordmark() }
            else if kind == 1 { Image(systemName: "soccerball").font(.system(size: 26)).accessibilityLabel("足球") }
            else { BrandImage(name: "ic_f1_wordmark").frame(width: 104, height: 26).accessibilityLabel("F1") }
            Spacer(minLength: 0)
            if kind == 0 {
                NavigationLink(value: AppRoute.settings) { Label("设置", systemImage: "gearshape").labelStyle(.iconOnly).frame(width: 44, height: 44) }
                    .buttonStyle(.plain).glassEffect(.regular, in: .circle)
            }
        }.padding(.horizontal, 16).frame(height: 52).background(.white)
    }
}
