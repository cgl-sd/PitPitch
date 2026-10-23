import SwiftUI

struct RacingMediaDescriptor: Sendable {
    let path: String
    let bounds: [Double]?
    let face: Bool
}
extension ArchiveRepository {
    func racingMedia(season: Int, kind: String, id: String) throws -> RacingMediaDescriptor? {
        try prepare()
        let item = media.object("\(kind)/\(season)/\(id)"), file = item.text("file")
        guard !file.isEmpty, !file.contains("/"), item.integer("season") == season, item.text("entity") == id else { return nil }
        let face = kind == "driver"
        let key = face ? "faceBounds" : "contentBounds"
        let numbers = (item[key] as? [NSNumber])?.map(\.doubleValue)
        return RacingMediaDescriptor(path: "f1-media/" + file, bounds: numbers?.count == 4 ? numbers : nil, face: face)
    }
}
struct RacingMedia: View {
    let descriptor: RacingMediaDescriptor?
    @Environment(\.assetRoot) private var root
    @State private var image: UIImage?
    var body: some View {
        Group {
            if let image { Image(uiImage: image).resizable().scaledToFit() }
            else { Color.clear }
        }.accessibilityHidden(true)
        .task(id: descriptor?.path) {
            image = nil
            guard let descriptor, let root, let data = await AssetDataCache.shared.data(root.appendingPathComponent(descriptor.path)), let original = UIImage(data: data), let cg = original.cgImage else { return }
            guard !Task.isCancelled else { return }
            if let b = descriptor.bounds {
                var rect = CGRect(x: b[0], y: b[1], width: b[2] - b[0], height: b[3] - b[1])
                if descriptor.face {
                    let side = max(rect.width, rect.height) * 2.2
                    rect = CGRect(x: rect.midX - side / 2, y: rect.midY - side * 0.42, width: side, height: side)
                }
                rect = rect.intersection(CGRect(x: 0, y: 0, width: cg.width, height: cg.height)).integral
                if !rect.isEmpty, let cropped = cg.cropping(to: rect) { image = UIImage(cgImage: cropped); return }
            }
            image = original
        }
    }
}
