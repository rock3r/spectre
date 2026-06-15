package dev.sebastiano.spectre.recording.screencapturekit

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Extracts the bundled `spectre-screencapture` Swift helper out of the recording module's jar
 * resources and onto disk so a subprocess can `exec` it.
 *
 * The Gradle `assembleScreenCaptureKitHelper` task stages the binary at
 * `native/macos/spectre-screencapture` inside the jar — this class is the corresponding read side.
 * Lifecycle:
 * 1. First [extract] call checks for the [OVERRIDE_ENV] env var and returns that path directly if
 *    set (dev-iteration escape hatch, skips JAR extraction).
 * 2. Otherwise it opens the classpath resource, copies it to [HELPER_DIR_PROPERTY] (if set) or
 *    [targetDirProvider]'s directory, and chmods it executable.
 * 3. Subsequent calls return the cached path without re-extracting (the in-memory cache is
 *    per-instance; create one extractor per recorder process and reuse).
 * 4. Caller is responsible for the lifetime of the file. When [HELPER_DIR_PROPERTY] is not set the
 *    default stashes it under `java.io.tmpdir/spectre-screencapture-<random>/` so a JVM exit cleans
 *    it up eventually but doesn't pin live recording.
 *
 * All four seams (env lookup, system-property lookup, resource locator, target dir) are injectable
 * so unit tests can drive the extractor with arbitrary bytes against arbitrary directories without
 * touching env / system properties / classpath / temp dir.
 *
 * ## macOS TCC and stable extraction paths
 *
 * On macOS, `AVAssetWriter.startWriting()` requires Screen Recording permission (System Settings →
 * Privacy & Security → Screen & System Audio Recording). macOS identifies unsigned or ad-hoc-signed
 * binaries by their path on disk, so the permission grant is lost every time the binary lands in a
 * new random temp directory.
 *
 * To allow a persistent TCC grant, set [HELPER_DIR_PROPERTY] to a stable directory before the test
 * JVM starts. From Gradle:
 * ```kotlin
 * systemProperty(
 *     "spectre.recording.screencapturekit.helperDir",
 *     layout.buildDirectory.dir("spectre-helper").get().asFile.absolutePath,
 * )
 * ```
 *
 * Then grant Screen Recording to `<helperDir>/spectre-screencapture` once in System Settings. The
 * binary is re-extracted to the same path on every subsequent run, so TCC continues to recognise
 * it. A `./gradlew clean` removes the file, but the TCC grant survives and takes effect again the
 * next time the binary is extracted to the same path.
 */
internal class HelperBinaryExtractor(
    private val envLookup: (String) -> String? = System::getenv,
    private val sysPropLookup: (String) -> String? = System::getProperty,
    private val resourceLocator: () -> InputStream? = ::defaultClasspathLookup,
    private val targetDirProvider: () -> Path = ::defaultTargetDir,
) {

    private var cached: Path? = null

    fun extract(): Path {
        cached?.let {
            return it
        }

        // Env var override: use a pre-existing binary at the given path directly, skipping
        // classpath extraction. Mirrors WaylandHelperBinaryExtractor's SPECTRE_WAYLAND_HELPER
        // pattern. Developer-only escape hatch for iterating on the Swift helper without
        // rebuilding the JAR. Never set this in environments that ingest untrusted input.
        envLookup(OVERRIDE_ENV)
            ?.takeIf { it.isNotBlank() }
            ?.let { override ->
                val path = Path.of(override)
                check(Files.isExecutable(path)) {
                    "$OVERRIDE_ENV points at '$path' but it is not an executable file. " +
                        "Fix the env var or unset it to use the bundled helper."
                }
                cached = path
                return path
            }

        val source =
            resourceLocator()
                ?: throw HelperNotBundledException(
                    "Bundled helper binary not found at classpath resource '$RESOURCE_PATH'. " +
                        "The recording module's macOS build stages 'spectre-screencapture' there " +
                        "via the assembleScreenCaptureKitHelper task — verify the module was " +
                        "built on macOS with the Swift toolchain available."
                )
        // Stable-dir system property: extract to a caller-controlled directory instead of a
        // fresh temp dir. When set the binary lands at the same path on every JVM launch, so
        // macOS TCC can persistently identify it. Without it the default produces a new random
        // temp dir each run and TCC grants evaporate. See class-level KDoc for setup details.
        val dir =
            sysPropLookup(HELPER_DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: targetDirProvider()
        val target = dir.resolve(BINARY_NAME)
        Files.createDirectories(target.parent)
        source.use { stream -> Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING) }
        markExecutable(target)
        cached = target
        return target
    }

    private fun markExecutable(path: Path) {
        // POSIX path: explicit mode bits so we get rwxr-xr-x rather than relying on the JVM's
        // umask. Fallback for non-POSIX filesystems (rare on macOS, but Linux CI on tmpfs
        // sometimes refuses POSIX attribute writes): plain `setExecutable(true)`.
        @Suppress("TooGenericExceptionCaught")
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        } catch (_: UnsupportedOperationException) {
            path.toFile().setExecutable(true, false)
        } catch (_: Throwable) {
            path.toFile().setExecutable(true, false)
        }
        check(Files.isExecutable(path)) {
            "Failed to mark helper at $path as executable — recording will fail to spawn"
        }
    }

    private companion object {
        const val BINARY_NAME: String = "spectre-screencapture"
        const val RESOURCE_PATH: String = "native/macos/$BINARY_NAME"

        /**
         * Environment variable that, when set, points the extractor at a pre-existing
         * `spectre-screencapture` binary and skips classpath extraction entirely. Mirrors
         * [dev.sebastiano.spectre.recording.portal.WaylandHelperBinaryExtractor]'s
         * `SPECTRE_WAYLAND_HELPER` escape hatch. Developer-only; do not use in production recording
         * pipelines or environments that ingest untrusted input.
         */
        const val OVERRIDE_ENV: String = "SPECTRE_SCREENCAPTURE_HELPER"

        /**
         * JVM system property that pins the extraction directory to a stable, persistent path
         * instead of the default random temp directory. Required for macOS TCC Screen Recording
         * grants to survive across JVM launches — see the class-level KDoc for the full rationale
         * and Gradle wiring instructions.
         */
        const val HELPER_DIR_PROPERTY: String = "spectre.recording.screencapturekit.helperDir"

        @JvmStatic
        fun defaultClasspathLookup(): InputStream? =
            HelperBinaryExtractor::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)

        @JvmStatic
        fun defaultTargetDir(): Path = Files.createTempDirectory("spectre-screencapture-")
    }
}
