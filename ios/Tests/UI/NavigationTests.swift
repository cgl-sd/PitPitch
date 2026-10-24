import XCTest

@MainActor
final class NavigationTests: XCTestCase {
    func testHomePullToRefreshRequestsIncrementalUpdate() throws {
        let app = XCUIApplication()
        app.launchEnvironment["PITPITCH_TEST_NETWORK_FAILURE"] = "1"
        app.launchEnvironment["PITPITCH_TEST_DISABLE_AUTO_REFRESH"] = "1"
        app.launch()
        XCTAssertTrue(app.staticTexts["赛事赛程"].waitForExistence(timeout: 20))
        XCTAssertFalse(app.staticTexts["联网更新失败，已保留本地资料"].exists)
        app.scrollViews.firstMatch.swipeDown()
        XCTAssertTrue(app.staticTexts["联网更新失败，已保留本地资料"].waitForExistence(timeout: 5))
    }
    func testHomeDoubleTapRequestsIncrementalUpdate() throws {
        let app = XCUIApplication()
        app.launchEnvironment["PITPITCH_TEST_NETWORK_FAILURE"] = "1"
        app.launchEnvironment["PITPITCH_TEST_DISABLE_AUTO_REFRESH"] = "1"
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["首页"].waitForExistence(timeout: 20))
        XCTAssertFalse(app.staticTexts["联网更新失败，已保留本地资料"].exists)
        app.tabBars.buttons["首页"].doubleTap()
        XCTAssertTrue(app.staticTexts["联网更新失败，已保留本地资料"].waitForExistence(timeout: 5))
    }
    func testColdLaunchPerformance() throws {
        let app = XCUIApplication()
        app.launchEnvironment["PITPITCH_TEST_NETWORK_FAILURE"] = "1"
        measure(metrics: [XCTApplicationLaunchMetric()]) {
            app.launch()
            XCTAssertTrue(app.tabBars.buttons["首页"].waitForExistence(timeout: 20))
            app.terminate()
        }
    }
    func testHomeTeamOpensImmediatelyFromEveryMainTab() throws {
        let app = XCUIApplication()
        app.launchEnvironment["PITPITCH_TEST_NETWORK_FAILURE"] = "1"
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["主队"].waitForExistence(timeout: 20))
        for title in ["首页", "足球", "F1"] {
            app.tabBars.buttons[title].tap()
            let start = Date.now
            app.tabBars.buttons["主队"].tap()
            XCTAssertTrue(app.otherElements["team-profile-header"].waitForExistence(timeout: 2))
            XCTAssertLessThan(Date.now.timeIntervalSince(start), 3.5)
            capture("immediate-home-team-\(title)")
            app.navigationBars.buttons.element(boundBy: 0).tap()
            XCTAssertTrue(app.tabBars.buttons[title].isSelected)
        }
    }
    func testOfflineTabsHistoricalSeasonsAndDetailNavigation() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["首页"].waitForExistence(timeout: 20))
        app.tabBars.buttons["首页"].tap()
        XCTAssertTrue(app.staticTexts["赛事赛程"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.tabBars.buttons["主队"].waitForExistence(timeout: 15))
        XCTAssertLessThan(app.tabBars.buttons["首页"].frame.midX, app.tabBars.buttons["足球"].frame.midX)
        XCTAssertLessThan(app.tabBars.buttons["足球"].frame.midX, app.tabBars.buttons["F1"].frame.midX)
        XCTAssertLessThan(app.tabBars.buttons["F1"].frame.midX, app.tabBars.buttons["主队"].frame.midX)
        XCTAssertTrue(app.tabBars.firstMatch.exists)
        XCTAssertGreaterThan(app.staticTexts["pitpitchWordmark"].frame.width, 80)
        capture("home")
        app.tabBars.buttons["足球"].tap()
        XCTAssertTrue(app.buttons["footballSeason"].waitForExistence(timeout: 10))
        app.buttons["column-赛程-积分-球员-0"].tap()
        app.buttons["footballSeason"].tap()
        app.buttons["2020/21"].tap()
        let fixture = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "fixture-")).firstMatch
        XCTAssertTrue(fixture.waitForExistence(timeout: 15))
        capture("football-2020")
        let contentTop = app.buttons["footballSeason"].frame.maxY
        let contentBottom = app.tabBars.buttons["足球"].frame.minY
        let visibleFixture = try XCTUnwrap(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "fixture-")).allElementsBoundByIndex.first {
            $0.isHittable && $0.frame.minY >= contentTop && $0.frame.maxY < contentBottom
        })
        let fixtureID = visibleFixture.identifier
        let before = visibleFixture.frame
        visibleFixture.tap()
        XCTAssertTrue(app.navigationBars["比赛详情"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.tabBars.firstMatch.isHittable)
        capture("football-detail")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.5)).press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.90, dy: 0.5)))
        XCTAssertTrue(app.buttons["footballSeason"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons[fixtureID].frame.minY, before.minY, accuracy: 2)
        app.tabBars.buttons["F1"].tap()
        XCTAssertTrue(app.buttons["f1Season"].waitForExistence(timeout: 10))
        app.buttons["column-赛程-车手积分-车队积分-0"].tap()
        app.buttons["f1Season"].tap()
        app.buttons["2020"].tap()
        let race = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "race-")).firstMatch
        XCTAssertTrue(race.waitForExistence(timeout: 15))
        capture("f1-2020")
        try XCTUnwrap(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "race-")).allElementsBoundByIndex.first { $0.isHittable }).tap()
        XCTAssertTrue(app.navigationBars["F1 分站详情"].waitForExistence(timeout: 5))
        capture("f1-detail")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.tabBars.buttons["首页"].tap()
        XCTAssertTrue(app.staticTexts["赛事赛程"].waitForExistence(timeout: 5))
        app.tabBars.buttons["主队"].tap()
        XCTAssertTrue(app.buttons["team-profile-section-0"].waitForExistence(timeout: 10))
        let list = try XCTUnwrap(app.scrollViews.matching(NSPredicate(format: "identifier BEGINSWITH %@", "fixture-list-")).allElementsBoundByIndex.first { $0.isHittable })
        let targetID = String(list.identifier.dropFirst("fixture-list-".count))
        let target = list.buttons["fixture-" + targetID]
        XCTAssertTrue(target.waitForExistence(timeout: 10))
        XCTAssertTrue(target.isHittable)
        capture("home-team")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["赛事赛程"].waitForExistence(timeout: 5))
        app.buttons["设置"].tap()
        XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 5))
        capture("settings")
    }
    func testRootAlignmentAndCurrentF1Weekend() throws {
        let app = XCUIApplication()
        app.launchEnvironment["PITPITCH_TEST_NETWORK_FAILURE"] = "1"
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["首页"].waitForExistence(timeout: 20))
        app.tabBars.buttons["首页"].tap()
        XCTAssertTrue(app.staticTexts["赛事赛程"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["pitpitchWordmark"].frame.minX, app.staticTexts["赛事赛程"].frame.minX, accuracy: 2)
        capture("aligned-home")
        app.tabBars.buttons["足球"].tap()
        let first = app.buttons["column-赛程-积分-球员-0"]
        let second = app.buttons["column-赛程-积分-球员-1"]
        XCTAssertTrue(first.waitForExistence(timeout: 10))
        XCTAssertEqual(first.frame.width, second.frame.width, accuracy: 1)
        capture("aligned-football")
        app.tabBars.buttons["F1"].tap()
        app.buttons["column-赛程-车手积分-车队积分-0"].tap()
        app.buttons["f1Season"].tap(); app.buttons["2026"].tap()
        let bell = app.buttons["reminder-f1-2026-18"]
        XCTAssertTrue(bell.waitForExistence(timeout: 15))
        let visibleBell = app.buttons.matching(identifier: "reminder-f1-2026-18").allElementsBoundByIndex.first { $0.isHittable }
        XCTAssertNotNil(visibleBell)
        capture("expanded-f1-weekend")
    }
    func testF1ColdLaunchCentersNextWeekendAndKeepsManualPosition() throws {
        let app = XCUIApplication()
        app.launchEnvironment["PITPITCH_TEST_NETWORK_FAILURE"] = "1"
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["F1"].waitForExistence(timeout: 20))
        app.tabBars.buttons["F1"].tap()
        XCTAssertTrue(app.buttons["f1Season"].label.contains("2026"))
        let list = app.scrollViews["f1-calendar"]
        XCTAssertTrue(list.waitForExistence(timeout: 15))
        let card = app.otherElements["race-card-f1-2026-18"]
        XCTAssertTrue(card.waitForExistence(timeout: 10))
        let visibleMidY = (list.frame.minY + app.tabBars.buttons["F1"].frame.minY) / 2
        XCTAssertEqual(card.frame.midY, visibleMidY, accuracy: 16)
        capture("f1-next-weekend-centered")
        list.swipeUp()
        let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "race-f1-2026-")).allElementsBoundByIndex
        let visible = try XCTUnwrap(rows.first { $0.isHittable && $0.frame.minY > list.frame.minY && $0.frame.maxY < list.frame.maxY })
        let id = visible.identifier
        let y = visible.frame.minY
        app.tabBars.buttons["首页"].tap(); app.tabBars.buttons["F1"].tap()
        XCTAssertEqual(app.buttons[id].frame.minY, y, accuracy: 2)
        capture("f1-roomier-calendar")
    }
    func testMeasuredScheduleRowProportions() throws {
        let app = XCUIApplication()
        app.launchEnvironment["PITPITCH_TEST_NETWORK_FAILURE"] = "1"
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["F1"].waitForExistence(timeout: 20)); app.tabBars.buttons["F1"].tap()
        let card = app.otherElements["race-card-f1-2026-19"]
        XCTAssertTrue(card.waitForExistence(timeout: 15))
        XCTAssertEqual(card.frame.height, (card.frame.width - 20) * 129 / 465, accuracy: 2)
        capture("measured-f1-proportion")
        app.tabBars.buttons["足球"].tap(); app.buttons["英超"].tap()
        app.buttons["column-赛程-积分-球员-0"].tap()
        let top = app.buttons["footballSeason"].frame.maxY
        let bottom = app.tabBars.buttons["足球"].frame.minY
        let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "fixture-")).allElementsBoundByIndex
        let row = try XCTUnwrap(rows.first { $0.isHittable && $0.frame.minY >= top && $0.frame.maxY < bottom })
        XCTAssertEqual(row.frame.height, row.frame.width * 121 / 490, accuracy: 2)
        capture("measured-football-proportion")
    }
    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
