import XCTest

final class DownloadFlowUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func button(_ app: XCUIApplication, containing text: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", text)).firstMatch
    }

    func testDownloadsAnIpaAndOffersExport() throws {
        let app = XCUIApplication()
        app.launch()
        guard #available(iOS 16.4, *) else {
            throw XCTSkip("XCUIApplication.open(_:) requires iOS 16.4 or newer")
        }
        app.open(URL(string: "githubstore://repo/Aidoku/Aidoku")!)

        let download = button(app, containing: "Download")
        XCTAssertTrue(download.waitForExistence(timeout: 90), app.debugDescription)
        download.tap()

        let export = button(app, containing: "Export")
        XCTAssertTrue(export.waitForExistence(timeout: 300), app.debugDescription)
        export.tap()

        let sheet = app.otherElements["ActivityListView"]
        XCTAssertTrue(sheet.waitForExistence(timeout: 15), app.debugDescription)
    }
}
