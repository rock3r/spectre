import CoreGraphics
import XCTest

@testable import SpectreScreenCaptureCore

final class DisplayScaleTests: XCTestCase {
    func testDisplayModeUsesBackingPixelsInsteadOfLogicalModeDimensions() throws {
        let resolver = DisplayDimensionResolver(
            bounds: { _ in CGRect(x: 0, y: 0, width: 1728, height: 1117) },
            modePixels: { _ in CGSize(width: 3456, height: 2234) },
            rotation: { _ in 0 },
            screenScale: { _ in nil })

        XCTAssertEqual(
            try resolver.pixelDimensions(for: 41),
            DisplayPixelDimensions(width: 3456, height: 2234))
    }

    func testQuarterTurnRotatesNativeModePixelsBeforeSizingOneXNonSquareCrop() throws {
        let resolver = DisplayDimensionResolver(
            bounds: { id in
                XCTAssertEqual(id, 73)
                return CGRect(x: 0, y: 0, width: 1080, height: 1920)
            },
            modePixels: { id in
                XCTAssertEqual(id, 73)
                return CGSize(width: 1920, height: 1080)
            },
            rotation: { id in
                XCTAssertEqual(id, 73)
                return 90
            },
            screenScale: { _ in
                XCTFail("mode pixels should win")
                return nil
            })

        let dimensions = try resolver.pixelDimensions(for: 73)

        XCTAssertEqual(dimensions, DisplayPixelDimensions(width: 1080, height: 1920))
        XCTAssertEqual(
            try dimensions.outputSize(
                for: CGSize(width: 120, height: 80),
                in: CGRect(x: 0, y: 0, width: 1080, height: 1920)),
            CGSize(width: 120, height: 80))
    }

    func testThreeQuarterTurnAndEquivalentRotationsSizeTwoXNonSquareCrop() throws {
        for rotation in [270.0, -90.0, 450.0] {
            let resolver = DisplayDimensionResolver(
                bounds: { _ in CGRect(x: 0, y: 0, width: 1080, height: 1920) },
                modePixels: { _ in CGSize(width: 3840, height: 2160) },
                rotation: { _ in rotation },
                screenScale: { _ in nil })

            let dimensions = try resolver.pixelDimensions(for: 73)

            XCTAssertEqual(dimensions, DisplayPixelDimensions(width: 2160, height: 3840))
            XCTAssertEqual(
                try dimensions.outputSize(
                    for: CGSize(width: 120, height: 80),
                    in: CGRect(x: 0, y: 0, width: 1080, height: 1920)),
                CGSize(width: 240, height: 160))
        }
    }

    func testLandscapeModeDimensionsKeepTheirOrientation() throws {
        let resolver = DisplayDimensionResolver(
            bounds: { _ in CGRect(x: 0, y: 0, width: 1920, height: 1080) },
            modePixels: { _ in CGSize(width: 3840, height: 2160) },
            rotation: { _ in 180 },
            screenScale: { _ in nil })

        XCTAssertEqual(
            try resolver.pixelDimensions(for: 73),
            DisplayPixelDimensions(width: 3840, height: 2160))
    }

    func testNSScreenScaleFallbackIsResolvedForTheSameDisplayID() throws {
        let resolver = DisplayDimensionResolver(
            bounds: { _ in CGRect(x: 1728, y: 0, width: 1920, height: 1080) },
            modePixels: { _ in nil },
            rotation: { _ in
                XCTFail("rotation is irrelevant without a mode")
                return 90
            },
            screenScale: { id in id == 73 ? 1.0 : nil })

        XCTAssertEqual(
            try resolver.pixelDimensions(for: 73),
            DisplayPixelDimensions(width: 1920, height: 1080))
    }

    func testMissingDisplayDimensionsRemainAnError() {
        let resolver = DisplayDimensionResolver(
            bounds: { _ in .zero }, modePixels: { _ in nil }, rotation: { _ in 0 },
            screenScale: { _ in nil })

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
