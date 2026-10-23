import SwiftUI

@main
struct PitPitchApp: App {
    @State private var dependencies = AppDependencies()
    var body: some Scene {
        WindowGroup {
            if NSClassFromString("XCTestCase") == nil {
                RootTabs(dependencies: dependencies).preferredColorScheme(.light)
            } else { Color.clear }
        }
    }
}
