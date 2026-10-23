import Foundation

enum LineupLayout {
    static func horizontal(_ player: FootballPlayer) -> Int {
        let text = player.position.lowercased()
        if text.contains("center left") { return 1 }; if text.contains("center right") { return 3 }
        if text.contains("left") { return 0 }; if text.contains("right") { return 4 }; return 2
    }
    static func published(_ players: [FootballPlayer], formation: String) -> [[FootballPlayer]]? {
        let parts = formation.replacingOccurrences(of: "–", with: "-").split(separator: "-")
        let shape = parts.compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
        guard shape.count == parts.count, (2...5).contains(shape.count), shape.allSatisfy({ (1...5).contains($0) }), shape.reduce(0, +) == 10, players.count == 11, Set(players.map(\.id)).count == 11 else { return nil }
        let last = shape.count, middle = Array(2..<shape.count)
        let candidates: [[Int]] = players.map { player in
            let p = player.position.lowercased()
            if p.contains("goalkeeper") { return [0] }
            if p.contains("wing back") || p.contains("wing-back") { return [1] + middle.prefix(1) }
            if p.contains("defender") || p.contains("back") { return [1] }
            if p.contains("midfielder") {
                if p.contains("attacking") { return Array(middle.suffix(1)) }
                if p.contains("defensive") { return Array(middle.prefix(1)) }; return middle
            }
            if p.contains("forward") || p.contains("striker") { return (p.contains("left") || p.contains("right")) && middle.count > 1 ? [last - 1, last] : [last] }
            return []
        }
        guard candidates.allSatisfy({ !$0.isEmpty }) else { return nil }
        var remaining = [1] + shape, assignment = Array(repeating: 0, count: 11)
        let order = players.indices.sorted { candidates[$0].count < candidates[$1].count }
        var solution: [Int]?, solutions = 0, visits = 0
        func assign(_ index: Int) {
            visits += 1
            if solutions > 1 || visits > 10_000 { return }
            if index == order.count {
                if remaining.allSatisfy({ $0 == 0 }) { solutions += 1; solution = assignment }; return
            }
            let player = order[index]
            for row in candidates[player] where remaining[row] > 0 {
                remaining[row] -= 1; assignment[player] = row; assign(index + 1); remaining[row] += 1
            }
        }
        assign(0)
        guard solutions == 1, visits <= 10_000, let solution else { return nil }
        return remaining.indices.map { row in players.indices.filter { solution[$0] == row }.map { players[$0] }.sorted { horizontal($0) < horizontal($1) } }
    }
    static func depth(_ player: FootballPlayer) -> Int {
        let p = player.position.lowercased()
        if p.contains("goalkeeper") { return 0 }
        if p.contains("wing back") || p.contains("wing-back") { return 2 }
        if p.contains("defender") || p.contains("back") { return 1 }
        if p.contains("defensive") { return 2 }; if p.contains("attacking") { return 4 }
        if p.contains("midfielder") { return 3 }
        if p.contains("forward") || p.contains("striker") { return p.contains("left") || p.contains("right") ? 4 : 5 }
        return 3
    }
    static func schematic(_ players: [FootballPlayer], formation: String) -> [[FootballPlayer]] {
        let ordered = players.sorted { depth($0) < depth($1) }
        let parts = formation.replacingOccurrences(of: "–", with: "-").split(separator: "-")
        let shape = parts.compactMap { Int($0) }
        if ordered.count == 11, shape.count == parts.count, (2...5).contains(shape.count), shape.allSatisfy({ (1...5).contains($0) }), shape.reduce(0, +) == 10 {
            var offset = 0
            return ([1] + shape).map { count in defer { offset += count }; return Array(ordered[offset..<offset + count]).sorted { horizontal($0) < horizontal($1) } }
        }
        let grouped = Dictionary(grouping: ordered) { player in let d = depth(player); return d <= 1 ? d : d <= 3 ? 2 : 3 }
        return grouped.keys.sorted().flatMap { key -> [[FootballPlayer]] in
            let row = grouped[key]!.sorted { horizontal($0) < horizontal($1) }
            return stride(from: 0, to: row.count, by: 4).map { Array(row[$0..<min($0 + 4, row.count)]) }
        }
    }
}
