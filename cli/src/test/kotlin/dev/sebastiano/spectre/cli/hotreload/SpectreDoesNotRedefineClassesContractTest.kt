package dev.sebastiano.spectre.cli.hotreload

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Coexistence premise with Compose Hot Reload's JdwpTracker (#212): Spectre must not call
 * [java.lang.instrument.Instrumentation.redefineClasses], which would look like an external
 * redefine and trip HR's tracker.
 *
 * Scans production Kotlin sources under `cli/`, `agent/`, `agent-runtime/`, and `core/` for the
 * forbidden call site. `redefineModule` (module opens at attach) is allowed and is a different API.
 */
class SpectreDoesNotRedefineClassesContractTest {
    @Test
    fun `production sources never call Instrumentation redefineClasses`() {
        val repoRoot = resolveRepoRoot()
        val roots = listOf("cli", "agent", "agent-runtime", "core").map { repoRoot.resolve(it) }
        val hits = mutableListOf<String>()
        for (root in roots) {
            assertTrue(Files.isDirectory(root), "expected module directory $root")
            val mainSrc = root.resolve("src/main")
            if (!Files.isDirectory(mainSrc)) continue
            Files.walk(mainSrc).use { stream ->
                stream
                    .asSequence()
                    .filter { it.isRegularFile() && it.extension == "kt" }
                    .forEach { path ->
                        val text = path.readText()
                        if (REDEFINE_CLASSES_CALL.containsMatchIn(text)) {
                            hits += path.toString()
                        }
                    }
            }
        }
        assertTrue(
            hits.isEmpty(),
            "Spectre must not call Instrumentation.redefineClasses (HR coexistence). Hits:\n" +
                hits.joinToString("\n"),
        )
    }

    private fun resolveRepoRoot(): Path {
        var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(6) {
            if (
                Files.isRegularFile(dir.resolve("settings.gradle.kts")) &&
                    Files.isDirectory(dir.resolve("cli"))
            ) {
                return dir
            }
            dir = dir.parent ?: fail("walked off filesystem from ${System.getProperty("user.dir")}")
        }
        fail(
            "could not find Spectre repo root (settings.gradle.kts) from " +
                System.getProperty("user.dir") +
                " (cwd name=${Path.of(System.getProperty("user.dir")).name})"
        )
    }

    companion object {
        private val REDEFINE_CLASSES_CALL = Regex("""\bredefineClasses\s*\(""")
    }
}
