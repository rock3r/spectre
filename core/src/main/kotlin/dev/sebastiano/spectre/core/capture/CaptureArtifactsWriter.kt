package dev.sebastiano.spectre.core.capture

import java.nio.file.Files
import java.nio.file.Path

/** Writes a capture directory's `capture.json` and `screenshot.png` files. */
public object CaptureArtifactsWriter {

    public const val CAPTURE_JSON_NAME: String = "capture.json"
    public const val SCREENSHOT_PNG_NAME: String = "screenshot.png"

    public fun write(
        directory: Path,
        document: CaptureDocument,
        pngBytes: ByteArray,
    ): CaptureArtifactPaths {
        Files.createDirectories(directory)
        val jsonPath = directory.resolve(CAPTURE_JSON_NAME)
        val pngPath = directory.resolve(SCREENSHOT_PNG_NAME)
        Files.writeString(jsonPath, CaptureJson.encode(document))
        Files.write(pngPath, pngBytes)
        return CaptureArtifactPaths(
            directory = directory,
            captureJsonPath = jsonPath,
            screenshotPngPath = pngPath,
        )
    }
}
