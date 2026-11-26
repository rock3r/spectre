package dev.sebastiano.spectre.recording

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureKDocContractTest {

    @Test
    fun `recording KDoc does not promise automatic region fallbacks`() {
        val sources =
            listOf(
                    "recording/src/main/kotlin/dev/sebastiano/spectre/recording/FfmpegWindowRecorder.kt",
                    "recording/src/main/kotlin/dev/sebastiano/spectre/recording/" +
                        "screencapturekit/HelperNotBundledException.kt",
                )
                .joinToString(separator = "\n") { root.resolve(it).readText() }

        assertFalse(sources.contains("handles that fallback"))
        assertFalse(sources.contains("catch ONLY this and fall back"))
        assertTrue(sources.contains("explicitly request region capture"))
    }

    private companion object {
        val root: Path = Path.of("..").toAbsolutePath().normalize()
    }
}
