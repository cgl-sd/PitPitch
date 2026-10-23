import SwiftUI

struct CountryFlag: View {
    let country: String
    private static let names = ["Australia": "au", "Australian": "au", "China": "cn", "Chinese": "cn", "Japan": "jp", "Japanese": "jp", "Bahrain": "bh", "Saudi Arabia": "sa", "USA": "us", "United States": "us", "American": "us", "Canada": "ca", "Canadian": "ca", "Monaco": "mc", "Monegasque": "mc", "Spain": "es", "Spanish": "es", "Austria": "at", "Austrian": "at", "UK": "gb", "British": "gb", "Belgium": "be", "Belgian": "be", "Hungary": "hu", "Netherlands": "nl", "Dutch": "nl", "Italy": "it", "Italian": "it", "Azerbaijan": "az", "Singapore": "sg", "Mexico": "mx", "Mexican": "mx", "Brazil": "br", "Brazilian": "br", "Qatar": "qa", "UAE": "ae", "France": "fr", "French": "fr", "Germany": "de", "German": "de", "Finnish": "fi", "Polish": "pl", "New Zealander": "nz", "Thai": "th", "Danish": "dk", "Argentine": "ar", "Argentinian": "ar"]
    var body: some View {
        Group { if let code = Self.names[country] { BrandImage(name: "flag_" + code) } else { Color.clear } }.frame(width: 26, height: 18).accessibilityLabel(country)
    }
}
