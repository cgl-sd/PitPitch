import XCTest
import UIKit

@MainActor
final class PortFeatureTests: XCTestCase {
    func testTeamRosterScrollingKeepsSegmentBackgroundClear() throws {
        let app = XCUIApplication()
        app.launchEnvironment["PITPITCH_TEST_NETWORK_FAILURE"] = "1"
        app.launch()
        let homeTeam = app.tabBars.buttons["主队"]
        XCTAssertTrue(homeTeam.waitForExistence(timeout: 20))
        homeTeam.tap()
        let scheduleTab = app.buttons["team-profile-section-0"]
        let rosterTab = app.buttons["team-profile-section-1"]
        XCTAssertTrue(rosterTab.waitForExistence(timeout: 10))
        rosterTab.tap()
        let roster = app.scrollViews["team-roster-list"]
        let forwards = app.staticTexts["team-position-Forward"]
        XCTAssertTrue(forwards.waitForExistence(timeout: 15))
        let controlFrame = scheduleTab.frame.union(rosterTab.frame)
        let backgroundBefore = try segmentImage(in: controlFrame, viewportWidth: app.frame.width)
        capture("team-roster-before-scroll")
        for index in 1...3 {
            roster.swipeUp()
            XCTAssertEqual(rosterTab.frame.union(scheduleTab.frame), controlFrame)
            XCTAssertEqual(try segmentImage(in: controlFrame, viewportWidth: app.frame.width), backgroundBefore,
                           "Roster content must not draw behind the fixed segments after swipe \(index)")
            capture("team-roster-after-scroll-\(index)")
        }
        XCTAssertFalse(forwards.isHittable, "The test must scroll the roster away from its initial position")
        scheduleTab.tap()
        XCTAssertTrue(scheduleTab.isSelected)
        rosterTab.tap()
        XCTAssertTrue(rosterTab.isSelected)
    }

    private func segmentImage(in frame: CGRect, viewportWidth: CGFloat) throws -> Data {
        let screenshot = XCUIScreen.main.screenshot().image
        let source = try XCTUnwrap(screenshot.cgImage)
        let scale = CGFloat(source.width) / viewportWidth
        let pixels = frame.applying(CGAffineTransform(scaleX: scale, y: scale))
        let image = try XCTUnwrap(source.cropping(to: pixels.integral))
        return try XCTUnwrap(UIImage(cgImage: image).pngData())
    }

    func testHistoricalFootballTablesPlayersAndTeamRoster() throws {
        let app = XCUIApplication(); app.launch()
        XCTAssertTrue(app.tabBars.buttons["足球"].waitForExistence(timeout: 20)); app.tabBars.buttons["足球"].tap()
        app.buttons["英超"].tap()
        app.buttons["footballSeason"].tap(); app.buttons["2020/21"].tap()
        app.buttons["积分"].tap()
        XCTAssertTrue(app.staticTexts["积分"].firstMatch.waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "曼城")).firstMatch.waitForExistence(timeout: 10))
        capture("football-table-2020")
        app.buttons["球员"].tap()
        let goals = app.buttons["player-board-0"]
        let assists = app.buttons["player-board-1"]
        XCTAssertTrue(goals.waitForExistence(timeout: 10))
        XCTAssertEqual(goals.label, "进球榜")
        XCTAssertEqual(assists.label, "助攻榜")
        XCTAssertEqual(goals.frame.height, 44, accuracy: 1)
        XCTAssertEqual(assists.frame.height, 44, accuracy: 1)
        XCTAssertTrue(app.staticTexts["—"].firstMatch.waitForExistence(timeout: 10))
        app.buttons["footballSeason"].tap()
        let season2024 = app.buttons["football-season-2024"]
        XCTAssertTrue(season2024.waitForExistence(timeout: 5))
        season2024.tap()
        XCTAssertTrue(app.buttons["footballSeason"].label.contains("2024/25"))
        XCTAssertTrue(app.staticTexts["Mohamed Salah"].waitForExistence(timeout: 15))
        capture("football-goals-2024")
        assists.tap()
        XCTAssertTrue(app.staticTexts["Mohamed Salah"].waitForExistence(timeout: 5))
        capture("football-assists-2024")
        app.tabBars.buttons["主队"].tap()
        let teamDetail = app.otherElements["team-detail"]
        XCTAssertTrue(teamDetail.waitForExistence(timeout: 10))
        XCTAssertFalse(teamDetail.buttons["teamCompetition"].exists)
        XCTAssertFalse(teamDetail.staticTexts["2026/27"].exists)
        let profileHeader = teamDetail.otherElements["team-profile-header"]
        let scheduleTab = teamDetail.buttons["team-profile-section-0"]
        let rosterTab = teamDetail.buttons["team-profile-section-1"]
        XCTAssertTrue(profileHeader.waitForExistence(timeout: 10))
        XCTAssertTrue(scheduleTab.waitForExistence(timeout: 10))
        XCTAssertEqual(scheduleTab.frame.minY - profileHeader.frame.maxY, 12, accuracy: 1)
        XCTAssertEqual(scheduleTab.frame.width, rosterTab.frame.width, accuracy: 1)
        XCTAssertEqual(scheduleTab.frame.minY, rosterTab.frame.minY, accuracy: 1)
        XCTAssertEqual(scheduleTab.frame.height, 44, accuracy: 1)
        XCTAssertEqual(rosterTab.frame.height, 44, accuracy: 1)
        let scheduleList = teamDetail.scrollViews
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "fixture-list-"))
            .firstMatch
        XCTAssertTrue(scheduleList.waitForExistence(timeout: 10))
        let scheduleContentGap = scheduleList.frame.minY - scheduleTab.frame.maxY
        XCTAssertEqual(scheduleContentGap, 8, accuracy: 1)
        let visibleMonths = teamDetail.staticTexts
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "fixture-month-"))
            .allElementsBoundByIndex
            .filter {
                $0.frame.minY >= scheduleList.frame.minY
                    && $0.frame.maxY <= scheduleList.frame.maxY
            }
        let month = try XCTUnwrap(
            visibleMonths.min(by: { $0.frame.minY < $1.frame.minY })
        )
        let monthKey = String(month.identifier.dropFirst("fixture-month-".count))
        let monthStart = teamDetail.buttons.matching(
            NSPredicate(format: "value == %@", "month-start:\(monthKey)")
        ).firstMatch
        XCTAssertTrue(monthStart.exists)
        let fixtureID = String(monthStart.identifier.dropFirst("fixture-".count))
        let fixtureTime = teamDetail.staticTexts["fixture-time-\(fixtureID)"]
        XCTAssertTrue(fixtureTime.exists)
        XCTAssertLessThan(fixtureTime.frame.minY - month.frame.maxY, 16)
        let firstVisibleFixture = try XCTUnwrap(
            teamDetail.buttons
                .matching(NSPredicate(format: "identifier BEGINSWITH %@", "fixture-"))
                .allElementsBoundByIndex
                .filter {
                    $0.frame.minY >= scheduleList.frame.minY
                        && $0.frame.minY < scheduleList.frame.maxY
                }
                .min(by: { $0.frame.minY < $1.frame.minY })
        )
        let firstFixtureID = String(firstVisibleFixture.identifier.dropFirst("fixture-".count))
        let firstFixtureTime = teamDetail.staticTexts["fixture-time-\(firstFixtureID)"]
        XCTAssertTrue(firstFixtureTime.exists)
        let scheduleFirstContentGap = firstFixtureTime.frame.minY - scheduleTab.frame.maxY
        capture("team-schedule-spacing")
        app.buttons["队员"].tap()
        let rosterList = teamDetail.scrollViews["team-roster-list"]
        XCTAssertTrue(rosterList.waitForExistence(timeout: 10))
        let forward = teamDetail.staticTexts["team-position-Forward"]
        XCTAssertTrue(forward.waitForExistence(timeout: 15))
        let rosterFirstContentGap = forward.frame.minY - rosterTab.frame.maxY
        XCTAssertEqual(scheduleFirstContentGap, rosterFirstContentGap, accuracy: 2)
        capture("team-roster")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["column-赛程-积分-球员-0"].tap()
    }
    func testF1StandingsAndPublishedQualifyingAndRaceResults() throws {
        let app = XCUIApplication(); app.launch()
        XCTAssertTrue(app.tabBars.buttons["F1"].waitForExistence(timeout: 20)); app.tabBars.buttons["F1"].tap()
        app.buttons["f1Season"].tap(); app.buttons["2020"].tap()
        app.buttons["车手积分"].tap()
        XCTAssertTrue(app.staticTexts["Lewis Hamilton"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["347"].exists)
        capture("f1-drivers-2020")
        app.buttons["车队积分"].tap()
        XCTAssertTrue(app.staticTexts["Mercedes"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["573"].exists)
        capture("f1-constructors-2020")
        app.buttons["column-赛程-车手积分-车队积分-0"].tap()
        let race = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "race-")).firstMatch
        XCTAssertTrue(race.waitForExistence(timeout: 10)); race.tap()
        let scheduleDetail = app.buttons["race-detail-0"]
        let qualifying = app.buttons["race-detail-1"]
        let raceResult = app.buttons["race-detail-2"]
        XCTAssertTrue(scheduleDetail.waitForExistence(timeout: 10))
        XCTAssertEqual(scheduleDetail.frame.height, 44, accuracy: 1)
        XCTAssertEqual(qualifying.frame.height, 44, accuracy: 1)
        XCTAssertEqual(raceResult.frame.height, 44, accuracy: 1)
        qualifying.tap()
        XCTAssertTrue(app.staticTexts["Valtteri Bottas"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["1:02.939"].exists)
        capture("f1-qualifying-2020")
        raceResult.tap()
        XCTAssertTrue(app.staticTexts["Valtteri Bottas"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["25"].exists)
        capture("f1-race-result-2020")
        app.navigationBars.buttons.element(boundBy: 0).tap()
    }
    func testNotificationPermissionBookingAndCancellation() throws {
        let app = XCUIApplication(); app.launch()
        XCTAssertTrue(app.tabBars.buttons["首页"].waitForExistence(timeout: 20)); app.tabBars.buttons["首页"].tap()
        let bell = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND (label BEGINSWITH %@ OR label BEGINSWITH %@)", "reminder-", "预约：", "取消预约：")).firstMatch
        XCTAssertTrue(bell.waitForExistence(timeout: 15))
        let id = bell.identifier
        addUIInterruptionMonitor(withDescription: "通知权限") { alert in
            if alert.buttons["允许"].exists { alert.buttons["允许"].tap(); return true }
            if alert.buttons["Allow"].exists { alert.buttons["Allow"].tap(); return true }
            return false
        }
        if bell.label.hasPrefix("取消预约") { bell.tap() }
        app.buttons.matching(NSPredicate(format: "identifier == %@ AND label BEGINSWITH %@", id, "预约：")).firstMatch.tap()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        if springboard.alerts.firstMatch.waitForExistence(timeout: 3) {
            let alert = springboard.alerts.firstMatch
            if alert.buttons["允许"].exists { alert.buttons["允许"].tap() } else if alert.buttons["Allow"].exists { alert.buttons["Allow"].tap() }
        }
        let subscribed = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label BEGINSWITH %@", id, "取消预约")).firstMatch
        XCTAssertTrue(subscribed.waitForExistence(timeout: 10))
        capture("reminder-booked")
        subscribed.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier == %@ AND label BEGINSWITH %@", id, "预约：")).firstMatch.waitForExistence(timeout: 5))
        capture("reminder-cancelled")
    }
    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
}

extension PortFeatureTests {
    func testRealMatchStatisticsEventsAndBothLineupPitches() throws {
        let app = XCUIApplication(); app.launch()
        XCTAssertTrue(app.tabBars.buttons["足球"].waitForExistence(timeout: 20)); app.tabBars.buttons["足球"].tap()
        XCTAssertTrue(app.buttons["footballSeason"].waitForExistence(timeout: 10))
        app.buttons["英超"].tap()
        app.buttons["column-赛程-积分-球员-0"].tap()
        app.buttons["footballSeason"].tap()
        let seasonOption = app.buttons["2025/26"]
        XCTAssertTrue(seasonOption.waitForExistence(timeout: 5))
        seasonOption.tap()
        let selectedSeason = NSPredicate(format: "label CONTAINS %@", "2025/26")
        if !selectedSeason.evaluate(with: app.buttons["footballSeason"]), seasonOption.exists, seasonOption.isHittable {
            seasonOption.tap()
        }
        XCTAssertTrue(app.buttons.matching(identifier: "footballSeason").matching(selectedSeason).firstMatch.waitForExistence(timeout: 5))
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "fixture-")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 15))
        let contentTop = app.buttons["footballSeason"].frame.maxY
        let bottom = app.tabBars.buttons["足球"].frame.minY
        let visible = try XCTUnwrap(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "fixture-")).allElementsBoundByIndex.first { $0.isHittable && $0.frame.minY >= contentTop && $0.frame.maxY < bottom })
        visible.tap()
        XCTAssertTrue(app.staticTexts["控球率"].waitForExistence(timeout: 30))
        capture("match-statistics")
        XCTAssertTrue(app.images["开赛"].exists)
        XCTAssertTrue(app.images["结束"].exists)
        let matchScroll = app.scrollViews.firstMatch
        for _ in 0..<4 {
            matchScroll.swipeUp()
            if app.images["开赛"].isHittable { break }
        }
        capture("match-timeline-top")
        let timelineRows = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "detail-event-"))
        XCTAssertGreaterThan(timelineRows.count, 3)
        let frames = timelineRows.allElementsBoundByIndex.map(\.frame).sorted { $0.minY < $1.minY }
        for (previous, next) in zip(frames, frames.dropFirst()) {
            XCTAssertGreaterThan(previous.height, 20)
            XCTAssertLessThanOrEqual(previous.maxY, next.minY + 1, "Timeline rows overlap")
        }
        for _ in 0..<4 {
            if app.images["结束"].isHittable { break }
            matchScroll.swipeUp()
        }
        XCTAssertTrue(app.images["结束"].isHittable)
        capture("match-timeline-end")
        for _ in 0..<8 {
            if app.buttons["column-数据与事件-首发-1"].isHittable { break }
            matchScroll.swipeDown()
        }
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "team-from-match-eng.1-")).firstMatch.tap()
        let teamDetail = app.otherElements["team-detail"]
        XCTAssertTrue(teamDetail.waitForExistence(timeout: 10))
        XCTAssertFalse(teamDetail.buttons["teamCompetition"].exists)
        XCTAssertTrue(teamDetail.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "2025/2026 赛季")).firstMatch.exists)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["column-数据与事件-首发-1"].tap()
        let formation = app.staticTexts.matching(NSPredicate(format: "label MATCHES %@", "[1-5](-[1-5]){1,4}")).firstMatch
        XCTAssertTrue(formation.waitForExistence(timeout: 10))
        capture("match-lineup-home")
        let lineupAway = app.buttons["lineup-team-1"]
        XCTAssertTrue(lineupAway.waitForExistence(timeout: 5))
        XCTAssertEqual(lineupAway.frame.height, 44, accuracy: 1)
        lineupAway.tap()
        XCTAssertTrue(formation.waitForExistence(timeout: 5))
        capture("match-lineup-away")
        app.navigationBars.buttons.element(boundBy: 0).tap()
    }
    func testSettingsSearchPrivacyAndNativeUpdateCheck() throws {
        let app = XCUIApplication(); app.launch()
        XCTAssertTrue(app.tabBars.buttons["首页"].waitForExistence(timeout: 20)); app.tabBars.buttons["首页"].tap()
        app.buttons["设置"].tap()
        app.buttons["选择主队"].tap()
        XCTAssertTrue(app.searchFields.firstMatch.waitForExistence(timeout: 10))
        app.searchFields.firstMatch.tap(); app.searchFields.firstMatch.typeText("Manchester United")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "曼联")).firstMatch.waitForExistence(timeout: 10))
        capture("team-search-english")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.navigationBars["选择主队"].waitForExistence(timeout: 5))
        app.navigationBars["选择主队"].buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 5))
        app.swipeUp()
        let update = app.buttons["checkForUpdates"]
        XCTAssertTrue(update.waitForExistence(timeout: 5)); update.tap()
        let result = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@ OR label CONTAINS %@", "App Store", "最新版本", "检查失败")).firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 25))
        capture("ios-update-check")
        app.buttons["隐私和关于"].tap()
        XCTAssertTrue(app.staticTexts["数据来源"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["足球赛事"].exists)
        XCTAssertTrue(app.staticTexts["名称与轮次"].exists)
        XCTAssertTrue(app.staticTexts["图片与赛道"].exists)
        app.swipeUp()
        XCTAssertTrue(app.staticTexts["开源协议"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["CC0 / 公有领域"].exists)
        XCTAssertTrue(app.staticTexts["其他图片与商标"].exists)
        capture("privacy-and-attribution")
    }
}

extension PortFeatureTests {
    func testColdLaunchDefaultsToCurrentSeasonAndSessionKeepsHistoricalSelection() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-footballSeason", "2020"]
        app.launchEnvironment["PITPITCH_TEST_NETWORK_FAILURE"] = "1"
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["足球"].waitForExistence(timeout: 20)); app.tabBars.buttons["足球"].tap()
        XCTAssertTrue(app.buttons["footballSeason"].label.contains("2026/27"))
        app.buttons["footballSeason"].tap(); app.buttons["2024/25"].tap()
        app.buttons["column-赛程-积分-球员-2"].tap()
        XCTAssertTrue(app.staticTexts["Mohamed Salah"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["footballSeason"].label.contains("2024/25"))
        capture("offline-archived-leaders")
        app.tabBars.buttons["首页"].tap()
        app.tabBars.buttons["足球"].tap()
        XCTAssertTrue(app.buttons["footballSeason"].label.contains("2024/25"))
        app.terminate(); app.launch()
        XCTAssertTrue(app.tabBars.buttons["足球"].waitForExistence(timeout: 20)); app.tabBars.buttons["足球"].tap()
        XCTAssertTrue(app.buttons["footballSeason"].label.contains("2026/27"))
        capture("football-cold-launch-current-season")
    }
    func testLargeDynamicTypeKeepsF1ColumnsAndDecimalPointsVisible() throws {
        let app = XCUIApplication(); app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launchEnvironment["PITPITCH_TEST_NETWORK_FAILURE"] = "1"; app.launch()
        XCTAssertTrue(app.tabBars.buttons["F1"].waitForExistence(timeout: 20)); app.tabBars.buttons["F1"].tap()
        app.buttons["f1Season"].tap(); app.buttons["2021"].tap()
        app.buttons["column-赛程-车手积分-车队积分-1"].tap()
        XCTAssertTrue(app.staticTexts["Max Verstappen"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["395.5"].exists)
        XCTAssertGreaterThan(app.staticTexts["Max Verstappen"].frame.height, 25)
        XCTAssertLessThan(app.staticTexts["Max Verstappen"].frame.height, app.frame.height * 0.55)
        XCTAssertTrue(app.staticTexts["395.5"].isHittable)
        XCTAssertLessThanOrEqual(app.staticTexts["395.5"].frame.maxX, app.frame.maxX)
        for i in 0...2 { XCTAssertTrue(app.buttons["column-赛程-车手积分-车队积分-\(i)"].isHittable) }
        capture("f1-accessibility-large-decimals")
        app.buttons["column-赛程-车手积分-车队积分-0"].tap()
    }
}

extension PortFeatureTests {
    func testLargeDynamicTypeKeepsFootballTableReadable() throws {
        let app = XCUIApplication(); app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launchEnvironment["PITPITCH_TEST_NETWORK_FAILURE"] = "1"; app.launch()
        XCTAssertTrue(app.tabBars.buttons["足球"].waitForExistence(timeout: 20)); app.tabBars.buttons["足球"].tap()
        app.buttons["footballSeason"].tap(); app.buttons["2020/21"].tap()
        app.buttons["column-赛程-积分-球员-1"].tap()
        let city = app.buttons["standing-eng.1-2020-382"]
        XCTAssertTrue(city.waitForExistence(timeout: 10))
        XCTAssertTrue(city.isHittable)
        XCTAssertTrue(city.label.contains("曼城")); XCTAssertTrue(city.label.contains("86"))
        XCTAssertLessThan(city.frame.height, app.frame.height * 0.85)
        XCTAssertLessThanOrEqual(city.frame.maxX, app.frame.maxX)
        capture("football-accessibility-large-table")
        app.buttons["column-赛程-积分-球员-0"].tap()
    }
}
