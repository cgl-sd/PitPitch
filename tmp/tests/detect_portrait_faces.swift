import Foundation
import Vision
import ImageIO

// Offline geometry only; never generates, edits or identifies a person.
let folder = URL(fileURLWithPath: CommandLine.arguments[1])
let catalog = try JSONSerialization.jsonObject(with: Data(contentsOf: folder.appendingPathComponent("catalog.json"))) as! [String: [String: Any]]
let files = Set(catalog.values.filter { $0["role"] as? String == "driver" }.compactMap { $0["file"] as? String })
var result: [String: [[Double]]] = [:]
for file in files.sorted() {
    let request = VNDetectFaceRectanglesRequest()
    request.revision = VNDetectFaceRectanglesRequestRevision3
    try VNImageRequestHandler(url: folder.appendingPathComponent(file), options: [:]).perform([request])
    result[file] = (request.results ?? []).map { face in
        let box = face.boundingBox
        return [box.minX, 1 - box.maxY, box.maxX, 1 - box.minY]
    }
}
let data = try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys])
try data.write(to: URL(fileURLWithPath: CommandLine.arguments[2]))
print("Detected face geometry for \(result.count) portrait files")
