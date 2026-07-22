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
 * Scans every production `src/main` Kotlin tree under the Spectre repo (all modules, not a
 * hard-coded subset) for the forbidden call site. `redefineModule` (module opens at attach) is
 * allowed and is a different API.
 */
class SpectreDoesNotRedefineClassesContractTest {
    @Test
    fun `production sources never call Instrumentation redefineClasses`() {
        val repoRoot = resolveRepoRoot()
        val mainSrcRoots = discoverProductionMainSourceRoots(repoRoot)
        assertTrue(mainSrcRoots.isNotEmpty(), "expected at least one src/main tree under $repoRoot")
        val hits = mutableListOf<String>()
        for (mainSrc in mainSrcRoots) {
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

    /**
     * Walks the repo for production `src/main` directories (skip `build/`, `.git/`, nested
     * `.worktrees` if present).
     */
    private fun discoverProductionMainSourceRoots(repoRoot: Path): List<Path> {
        val found = ArrayList<Path>()
        Files.walk(repoRoot, 6).use { stream ->
            stream.forEach { path ->
                if (!Files.isDirectory(path)) return@forEach
                if (path.fileName.toString() != "main") return@forEach
                if (path.parent?.fileName?.toString() != "src") return@forEach
                val s = path.toString().replace('\\', '/')
                if ("/build/" in s || "/.git/" in s || "/.worktrees/" in s) return@forEach
                found.add(path)
            }
        }
        return found.sortedBy { it.toString() }
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
