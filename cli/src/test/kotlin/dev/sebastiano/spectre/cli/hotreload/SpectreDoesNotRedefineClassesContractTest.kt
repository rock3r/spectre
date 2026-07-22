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
                    .filter {
                        it.isRegularFile() && (it.extension == "kt" || it.extension == "java")
                    }
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
        return repoRoot
            .listDirectoryEntries()
            .filter { it.isDirectory() }
            .filter { Files.isRegularFile(it.resolve("build.gradle.kts")) }
            .map { it.resolve("src").resolve("main") }
            .filter { it.isDirectory() }
            .sortedBy { it.toString() }
    }

    private fun resolveRepoRoot(): Path {
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val ancestors = generateSequence(start) { it.parent }.take(8).toList()
        return ancestors.firstOrNull { dir ->
            Files.isRegularFile(dir.resolve("settings.gradle.kts")) &&
                Files.isDirectory(dir.resolve("cli"))
        }
            ?: fail(
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
