import CoreGraphics
import XCTest

@testable import SpectreScreenCaptureCore

final class DisplayScaleTests: XCTestCase {
    func testDisplayModeUsesBackingPixelsInsteadOfLogicalModeDimensions() throws {
        let resolver = DisplayDimensionResolver(
            bounds: { _ in CGRect(x: 0, y: 0, width: 1728, height: 1117) },
            modePixels: { _ in CGSize(width: 3456, height: 2234) },
            screenScale: { _ in nil })

        XCTAssertEqual(
            try resolver.pixelDimensions(for: 41),
            DisplayPixelDimensions(width: 3456, height: 2234))
    }

    func testNSScreenScaleFallbackIsResolvedForTheSameDisplayID() throws {
        let resolver = DisplayDimensionResolver(
            bounds: { _ in CGRect(x: 1728, y: 0, width: 1920, height: 1080) },
            modePixels: { _ in nil },
            screenScale: { id in id == 73 ? 1.0 : nil })

        XCTAssertEqual(
            try resolver.pixelDimensions(for: 73),
            DisplayPixelDimensions(width: 1920, height: 1080))
    }

    func testMissingDisplayDimensionsRemainAnError() {
        let resolver = DisplayDimensionResolver(
            bounds: { _ in .zero }, modePixels: { _ in nil }, screenScale: { _ in nil })

        XCTAssertThrowsError(try resolver.pixelDimensions(for: 41))
    }

    func testOneLogicalPixelProducesOnePixelOnOneXDisplay() throws {
        let dimensions = DisplayPixelDimensions(width: 1920, height: 1080)
        let bounds = CGRect(x: 0, y: 0, width: 1920, height: 1080)

        XCTAssertEqual(try dimensions.outputSize(for: CGSize(width: 1, height: 1), in: bounds), CGSize(width: 1, height: 1))
    }

    func testVerticallyArrangedDisplayUsesTopLeftCoordinateSpace() {
        let retina: CGDirectDisplayID = 41
        let external: CGDirectDisplayID = 73
        let displays = [
            (retina, CGRect(x: 0, y: 0, width: 1512, height: 982)),
            (external, CGRect(x: 0, y: 982, width: 1920, height: 1080)),
        ]

        XCTAssertEqual(
            dominantDisplayID(
                for: CGRect(x: 100, y: 1100, width: 500, height: 300), displays: displays),
            external)
    }

    func testLargestOverlapWinsAcrossVerticalMixedScaleBoundary() {
        let retina: CGDirectDisplayID = 41
        let external: CGDirectDisplayID = 73
        let displays = [
            (retina, CGRect(x: 0, y: 0, width: 1512, height: 982)),
            (external, CGRect(x: 0, y: 982, width: 1920, height: 1080)),
        ]

        XCTAssertEqual(
            dominantDisplayID(
                for: CGRect(x: 100, y: 900, width: 500, height: 300), displays: displays),
            external)
    }
}
