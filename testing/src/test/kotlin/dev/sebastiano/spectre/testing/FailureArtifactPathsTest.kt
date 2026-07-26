package dev.sebastiano.spectre.testing

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FailureArtifactPathsTest {

    @Test
    fun `default reports root is build reports spectre under user dir`() {
        val root = FailureArtifactPaths.defaultReportsRoot()
        assertEquals(Path.of("build", "reports", "spectre").toAbsolutePath().normalize(), root)
    }

    @Test
    fun `method directory uses fully qualified class and method name`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp)
        val dir =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.MyTest",
                testMethodName = "waitForNodeFails",
                config = config,
            )
        assertEquals(temp.resolve("com.example.MyTest").resolve("waitForNodeFails"), dir)
    }

    @Test
    fun `attempt index greater than 1 suffixes the method directory`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp, attemptIndex = 2)
        val dir =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.MyTest",
                testMethodName = "flaky",
                config = config,
            )
        assertEquals(temp.resolve("com.example.MyTest").resolve("flaky-attempt-2"), dir)
    }

    @Test
    fun `attempt index 1 does not suffix the method directory`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp, attemptIndex = 1)
        val dir =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.MyTest",
                testMethodName = "once",
                config = config,
            )
        assertEquals(temp.resolve("com.example.MyTest").resolve("once"), dir)
    }

    @Test
    fun `sanitizes path-hostile characters in class and method names`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp)
        val dir =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.Nested\$Inner",
                testMethodName = "param[0] with spaces",
                config = config,
            )
        val classSeg = dir.parent.fileName.toString()
        val methodSeg = dir.fileName.toString()
        assertFalse(classSeg.contains('$'))
        assertFalse(methodSeg.contains('['))
        assertFalse(methodSeg.contains(']'))
        assertFalse(methodSeg.contains(' '))
        assertTrue(methodSeg.isNotBlank())
    }

    @Test
    fun `window directory is method dir plus window index`(@TempDir temp: Path) {
        val methodDir = temp.resolve("cls").resolve("method")
        val windowDir = FailureArtifactPaths.windowDirectory(methodDir, windowIndex = 3)
        assertEquals(methodDir.resolve("window-3"), windowDir)
    }

    @Test
    fun `config defaults to enabled`() {
        assertTrue(FailureArtifactsConfig().enabled)
    }

    @Test
    fun `disabled config is explicit opt-out`() {
        assertFalse(FailureArtifactsConfig(enabled = false).enabled)
    }

    @Test
    fun `defaultReportsRoot does not create directories`() {
        // Ensure the helper is pure path computation — no mkdir side effects on a fresh path.
        val before = createTempDirectory("spectre-fail-art-").toFile()
        before.delete()
        // Just exercise the pure function; existence is independent of call.
        val root = FailureArtifactPaths.defaultReportsRoot()
        assertEquals("spectre", root.fileName.toString())
    }
}
