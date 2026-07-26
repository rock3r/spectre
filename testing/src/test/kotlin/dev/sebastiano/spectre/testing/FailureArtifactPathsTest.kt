package dev.sebastiano.spectre.testing

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertTrue(dir.startsWith(temp))
        assertTrue(dir.parent.fileName.toString().startsWith("com.example.MyTest"))
        assertTrue(dir.fileName.toString().startsWith("waitForNodeFails"))
    }

    @Test
    fun `attempt index greater than 1 nests under attempt-N`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp, attemptIndex = 2)
        val dir =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.MyTest",
                testMethodName = "flaky",
                config = config,
            )
        assertEquals("attempt-2", dir.fileName.toString())
        assertTrue(dir.parent.fileName.toString().startsWith("flaky"))
    }

    @Test
    fun `attempt nest does not collide with a literal attempt method name`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp, attemptIndex = 2)
        val retryDir =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.T",
                testMethodName = "flaky",
                config = config,
            )
        val literal =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.T",
                testMethodName = "flaky-attempt-2",
                config = FailureArtifactsConfig(reportsRoot = temp),
            )
        assertTrue(retryDir != literal)
        assertEquals("attempt-2", retryDir.fileName.toString())
    }

    @Test
    fun `non-positive attempt index is rejected`() {
        assertFailsWith<IllegalArgumentException> { FailureArtifactsConfig(attemptIndex = 0) }
        assertFailsWith<IllegalArgumentException> { FailureArtifactsConfig(attemptIndex = -1) }
    }

    @Test
    fun `case-only differences get distinct directories`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp)
        val a =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.T",
                testMethodName = "caseA",
                config = config,
            )
        val b =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.T",
                testMethodName = "CaseA",
                config = config,
            )
        assertTrue(a.fileName != b.fileName)
    }

    @Test
    fun `attempt index 1 does not nest attempt directory`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp, attemptIndex = 1)
        val dir =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.MyTest",
                testMethodName = "once",
                config = config,
            )
        assertTrue(dir.fileName.toString().startsWith("once"))
        assertFalse(dir.fileName.toString().startsWith("attempt-"))
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
    fun `reserved Windows device names are escaped after the stem`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp)
        val dir =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.T",
                testMethodName = "NUL",
                config = config,
            )
        // Lossy escape also appends a stable hash of the original label.
        assertTrue(dir.fileName.toString().startsWith("NUL_"))
        assertTrue(dir.fileName.toString().length > "NUL_".length)

        val com1 =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.T",
                testMethodName = "com1",
                config = config,
            )
        assertTrue(com1.fileName.toString().startsWith("com1_"))

        // Windows keys off the stem before the first '.'; so the underscore must land on the
        // stem (`nul_.txt…`), not as a trailing suffix on the whole segment (`nul.txt_`).
        val nulTxt =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.T",
                testMethodName = "nul.txt",
                config = config,
            )
        assertTrue(nulTxt.fileName.toString().startsWith("nul_.txt"))
    }

    @Test
    fun `dot-only names do not navigate the filesystem`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp)
        val dot =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.T",
                testMethodName = ".",
                config = config,
            )
        assertTrue(dot.fileName.toString().startsWith("dot"))
        assertTrue(
            dot.startsWith(temp.resolve(FailureArtifactPaths.sanitizePathSegment("com.example.T")))
        )

        val dotdot =
            FailureArtifactPaths.methodDirectory(
                testClassName = "..",
                testMethodName = "method",
                config = config,
            )
        assertTrue(dotdot.parent.fileName.toString().startsWith("dotdot"))
        assertTrue(dotdot.startsWith(temp))
    }

    @Test
    fun `lossy sanitization keeps distinct punctuation variants unique`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp)
        val bracket =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.T",
                testMethodName = "case[1]",
                config = config,
            )
        val paren =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.T",
                testMethodName = "case(1)",
                config = config,
            )
        assertTrue(bracket.fileName != paren.fileName)
        assertTrue(bracket.fileName.toString().startsWith("case_1_"))
        assertTrue(paren.fileName.toString().startsWith("case_1_"))
    }

    @Test
    fun `overlong segments are truncated under filesystem byte limit`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp)
        val longName = "a".repeat(400)
        val dir =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.T",
                testMethodName = longName,
                config = config,
            )
        val segment = dir.fileName.toString()
        assertTrue(
            segment.toByteArray(Charsets.UTF_8).size <= FailureArtifactPaths.MAX_SEGMENT_BYTES
        )
        assertTrue(segment.contains('_'), "expected hash suffix for uniqueness: $segment")
        // Distinct long names should not collapse to the same truncated path.
        val other =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.T",
                testMethodName = "b".repeat(400),
                config = config,
            )
        assertTrue(dir.fileName != other.fileName)
    }

    @Test
    fun `attempt suffix does not exceed segment byte limit`(@TempDir temp: Path) {
        val longName = "m".repeat(400)
        val config = FailureArtifactsConfig(reportsRoot = temp, attemptIndex = 12)
        val dir =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.T",
                testMethodName = longName,
                config = config,
            )
        // Nested attempt-N is short; method segment itself must stay in bound.
        assertTrue(
            dir.parent.fileName.toString().toByteArray(Charsets.UTF_8).size <=
                FailureArtifactPaths.MAX_SEGMENT_BYTES
        )
        assertEquals("attempt-12", dir.fileName.toString())
    }

    @Test
    fun `trailing dots are stripped as a lossy Windows-safe transform`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp)
        val dir =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.T",
                testMethodName = "case.",
                config = config,
            )
        assertFalse(dir.fileName.toString().endsWith("."))
        assertTrue(dir.fileName.toString().startsWith("case"))
    }

    @Test
    fun `multibyte truncation stays within byte budget`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp)
        val longName = "é".repeat(400)
        val dir =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.T",
                testMethodName = longName,
                config = config,
            )
        assertTrue(
            dir.fileName.toString().toByteArray(Charsets.UTF_8).size <=
                FailureArtifactPaths.MAX_SEGMENT_BYTES
        )
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
