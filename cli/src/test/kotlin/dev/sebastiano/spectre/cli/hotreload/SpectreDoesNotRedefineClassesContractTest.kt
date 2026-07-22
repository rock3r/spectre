package dev.sebastiano.spectre.cli.hotreload

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
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
 * Scans every production `src/main` Kotlin tree under the Spectre repo (all modules that have a
 * `build.gradle.kts` + `src/main`) for the forbidden call site. `redefineModule` (module opens at
 * attach) is allowed and is a different API.
 */
class SpectreDoesNotRedefineClassesContractTest {
    @Test
    fun `production sources never call Instrumentation redefineClasses`() {
        val repoRoot = resolveRepoRoot()
        val mainSrcRoots = discoverProductionMainSourceRoots(repoRoot)
        assertTrue(
            mainSrcRoots.isNotEmpty(),
            "expected at least one src/main tree under $repoRoot (user.dir=" +
                System.getProperty("user.dir") +
                ")",
        )
        val hits = mutableListOf<String>()
        for (mainSrc in mainSrcRoots) {
            Files.walk(mainSrc).use { stream ->
                stream
                    .asSequence()
                    .filter { it.isRegularFile() && it.extension == "kt" }
                    .forEach { path ->
                        if (REDEFINE_CLASSES_CALL.containsMatchIn(path.readText())) {
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

    /**
     * Finds production modules by looking for immediate child directories (and recording/*) that
     * have both `build.gradle.kts` and `src/main`.
     */
    private fun discoverProductionMainSourceRoots(repoRoot: Path): List<Path> {
        val roots = ArrayList<Path>()
        fun consider(moduleDir: Path) {
            if (!moduleDir.isDirectory()) return
            if (!Files.isRegularFile(moduleDir.resolve("build.gradle.kts"))) return
            val main = moduleDir.resolve("src/main")
            if (main.isDirectory()) roots.add(main)
        }
        // Top-level modules
        repoRoot.listDirectoryEntries().forEach { consider(it) }
        // Nested platform recording modules
        val recording = repoRoot.resolve("recording")
        if (recording.isDirectory()) {
            recording.listDirectoryEntries().forEach { consider(it) }
        }
        // Also consider recording itself
        consider(recording)
        return roots.sortedBy { it.toString() }
    }

    private fun resolveRepoRoot(): Path {
        var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(8) {
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
