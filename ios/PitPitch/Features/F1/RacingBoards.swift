import SwiftUI

struct RacingBoards: View {
    let repository: ArchiveRepository
    let season: Int
    let constructors: Bool
    let raceCount: Int
    @State private var snapshots = ViewSnapshots.shared
    @Environment(RefreshCoordinator.self) private var refresh
    @Environment(\.dynamicTypeSize) private var typeSize
    private var scope: String { "\(season)/\(constructors)" }
    private var table: RacingTable? {
        get { snapshots.racingTables[scope] }
        nonmutating set { snapshots.racingTables[scope] = newValue }
    }
    @State private var paths: [String: RacingMediaDescriptor] = [:]
    @State private var cars: [String: RacingMediaDescriptor] = [:]
    @State private var error: String?
    @State private var retry = 0
    @ScaledMetric(relativeTo: .headline) private var rankWidth: CGFloat = 22
    var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                if let table {
                    if let round = table.round, raceCount > 0, round <= raceCount {
                        Text("\(round) / \(raceCount)").font(.subheadline)
                            .frame(maxWidth: .infinity, alignment: .trailing)
                            .padding(.top, 16).padding(.bottom, 8)
                        ProgressView(value: Double(round), total: Double(max(round, raceCount))).tint(Palette.red).padding(.bottom, 20).accessibilityLabel("已发布积分分站 \(round)，全年 \(raceCount) 站")
                    }
                    if typeSize.isAccessibilitySize {
                        Text(constructors ? "车队" : "车手").font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 8)
                    } else {
                        HStack { Text(constructors ? "车队" : "车手").padding(.leading, rankWidth + 90); Spacer(); Text("积分") }.font(.caption).foregroundStyle(.secondary).padding(.vertical, 8)
                    }
                    ForEach(table.rows) { row in
                        VStack(alignment: .trailing, spacing: 8) {
                            HStack(spacing: 10) {
                                Text(String(row.rank)).font(.headline).foregroundStyle(row.rank <= 3 ? Palette.red : .secondary).frame(width: rankWidth)
                                RacingMedia(descriptor: paths[row.id])
                                    .frame(width: constructors ? 40 : 60, height: constructors ? 44 : 64)
                                    .frame(width: 60, height: 64)
                                Rectangle().fill(F1Colors.color(row.constructorID, season: season)).frame(width: 3, height: 28).overlay(Rectangle().stroke(Color.gray.opacity(0.25), lineWidth: 0.5))
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(row.name).font(.subheadline.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
                                    if constructors { RacingMedia(descriptor: cars[row.id]).frame(maxWidth: 120).frame(height: 28) }
                                    else { Text(row.constructor).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
                                }.frame(maxWidth: .infinity, alignment: .leading)
                                if !typeSize.isAccessibilitySize { Text(row.points).font(.title3.bold()).monospacedDigit().fixedSize() }
                            }
                            if typeSize.isAccessibilitySize {
                                HStack(alignment: .firstTextBaseline, spacing: 12) { Text("积分").font(.caption).foregroundStyle(.secondary); Text(row.points).font(.title3.bold()).monospacedDigit().fixedSize() }
                            }
                        }.padding(.vertical, 8).padding(.horizontal, 4).background(row.rank == 1 ? Palette.red.opacity(0.045) : .clear)
                        Divider()
                    }
                    if table.rows.isEmpty { Text("暂无已发布积分").foregroundStyle(.secondary).padding() }
                }
                if let error, !constructors || table == nil { ArchiveFailure(message: error) { retry += 1 } }
            }.padding(.horizontal, 16)
        }
        .task(id: "cache/\(season)/\(constructors)/\(refresh.revision)") {
            if let values = try? await repository.racingTable(season: season, constructors: constructors), !Task.isCancelled { table = values; await images() }
        }
        .task(id: "\(season)/\(constructors)/\(retry)") {
            table = (try? await repository.racingTable(season: season, constructors: constructors)) ?? table
            await images()
            do {
                let value = try await repository.racingTable(season: season, constructors: constructors, refresh: true)
                try Task.checkCancellation(); table = value; error = nil; await images()
            } catch is CancellationError { } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
        }
    }
    private func images() async {
        var pictures: [String: RacingMediaDescriptor] = [:], vehicles: [String: RacingMediaDescriptor] = [:]
        for row in table?.rows ?? [] {
            pictures[row.id] = try? await repository.racingMedia(season: season, kind: constructors ? "logo" : "driver", id: row.id)
            if constructors { vehicles[row.id] = try? await repository.racingMedia(season: season, kind: "car", id: row.id) }
        }
        guard !Task.isCancelled else { return }
        paths = pictures; cars = vehicles
    }
}

enum F1Colors {
    static func color(_ id: String, season: Int) -> Color { Color(hex: seasons[season]?[id] ?? "888888") }
    // Same verified season-specific palette as Android.
    static let seasons: [Int: [String: String]] = [
        2020: ["alfa": "9B0000", "alphatauri": "FFFFFF", "ferrari": "DC0000", "haas": "787878", "mclaren": "FF8700", "mercedes": "00D2BE", "racing_point": "F596C8", "red_bull": "1E41FF", "renault": "FFF500", "williams": "0082FA"],
        2021: ["alfa": "900000", "alphatauri": "2B4562", "alpine": "0090FF", "aston_martin": "006F62", "ferrari": "DC0004", "haas": "FFFFFF", "mclaren": "FF9800", "mercedes": "00D2BE", "red_bull": "0600EF", "williams": "005AFF"],
        2022: ["alfa": "B12039", "alphatauri": "4E7C9B", "alpine": "2293D1", "aston_martin": "2D826D", "ferrari": "ED1C24", "haas": "B6BABD", "mclaren": "F58020", "mercedes": "6CD3BF", "red_bull": "1E5BC6", "williams": "37BEDD"],
        2023: ["alfa": "C92D4B", "alphatauri": "5E8FAA", "alpine": "2293D1", "aston_martin": "358C75", "ferrari": "F91536", "haas": "B6BABD", "mclaren": "F58020", "mercedes": "6CD3BF", "red_bull": "3671C6", "williams": "37BEDD"],
        2024: ["alpine": "0093CC", "aston_martin": "229971", "ferrari": "E8002D", "haas": "B6BABD", "mclaren": "FF8000", "mercedes": "27F4D2", "rb": "6692FF", "red_bull": "3671C6", "sauber": "52E252", "williams": "64C4FF"],
        2025: ["alpine": "0093CC", "aston_martin": "229971", "ferrari": "E80020", "haas": "B6BABD", "mclaren": "FF8000", "mercedes": "27F4D2", "rb": "6692FF", "red_bull": "3671C6", "sauber": "52E252", "williams": "64C4FF"],
        2026: ["mercedes": "27F4D2", "ferrari": "E8002D", "mclaren": "FF8000", "red_bull": "3671C6", "rb": "6692FF", "alpine": "00A1E8", "haas": "DEE1E2", "audi": "FF2D00", "williams": "1868DB", "aston_martin": "229971", "cadillac": "AAAAAD"]
    ]
}
extension Color {
    init(hex: String) {
        let value = UInt64(hex.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0x888888
        self.init(red: Double((value >> 16) & 255) / 255, green: Double((value >> 8) & 255) / 255, blue: Double(value & 255) / 255)
    }
}
