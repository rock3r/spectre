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

// HR coexistence: Spectre must never call Instrumentation.redefineClasses (JdwpTracker tripwire).
// redefineModule for attach-time module opens is a different API and is allowed.
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

    private fun discoverProductionMainSourceRoots(repoRoot: Path): List<Path> {
        // Top-level Gradle modules only (recording-macos/linux/windows are siblings of recording/).
        val roots = ArrayList<Path>()
        for (moduleDir in repoRoot.listDirectoryEntries()) {
            if (!moduleDir.isDirectory()) continue
            if (!Files.isRegularFile(moduleDir.resolve("build.gradle.kts"))) continue
            val main = moduleDir.resolve("src").resolve("main")
            if (main.isDirectory()) {
                roots.add(main)
            }
        }
        return roots.sortedBy { it.toString() }
    }

    private fun resolveRepoRoot(): Path {
        var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(8) {
            val settings = dir.resolve("settings.gradle.kts")
            val cli = dir.resolve("cli")
            if (Files.isRegularFile(settings) && Files.isDirectory(cli)) {
                return dir
            }
            dir = dir.parent ?: fail("walked off filesystem from ${System.getProperty("user.dir")}")
        }
        fail(
            "could not find Spectre repo root from " +
                System.getProperty("user.dir") +
                " (cwd name=" +
                Path.of(System.getProperty("user.dir")).name +
                ")"
        )
    }

    companion object {
        private val REDEFINE_CLASSES_CALL = Regex("""\bredefineClasses\s*\(""")
    }
}
