package dev.sebastiano.spectre.recording.screencapturekit

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Extracts the bundled `spectre-screencapture` Swift helper out of the recording module's jar
 * resources and onto disk so a subprocess can `exec` it.
 *
 * The Gradle `assembleScreenCaptureKitHelper` task stages the binary at
 * `native/macos/spectre-screencapture` inside the jar — this class is the corresponding read side.
 * Lifecycle:
 * 1. First [extract] call opens the resource, copies it to [targetDirProvider]'s temp dir, and
 *    chmods it executable.
 * 2. Subsequent calls return the cached path without re-extracting (the in-memory cache is
 *    per-instance; create one extractor per recorder process and reuse).
 * 3. Caller is responsible for the lifetime of the temp file. Defaults stash it under
 *    `java.io.tmpdir/spectre-screencapture-<random>/` so a JVM exit cleans it up eventually but
 *    doesn't pin live recording.
 *
 * Both seams (resource locator + target dir) are injectable so unit tests can drive the extractor
 * with arbitrary bytes against arbitrary directories without touching the production classpath /
 * temp dir.
 */
internal class HelperBinaryExtractor(
    private val resourceLocator: () -> InputStream? = ::defaultClasspathLookup,
    private val targetDirProvider: () -> Path = ::defaultTargetDir,
) {

    private var cached: Path? = null

    fun extract(): Path {
        cached?.let {
            return it
        }
        val source =
            resourceLocator()
                ?: throw IllegalStateException(
                    "Bundled helper binary not found at classpath resource '$RESOURCE_PATH'. " +
                        "The recording module's macOS build stages 'spectre-screencapture' there " +
                        "via the assembleScreenCaptureKitHelper task — verify the module was " +
                        "built on macOS with the Swift toolchain available."
                )
        val target = targetDirProvider().resolve(BINARY_NAME)
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

    @Suppress("unused")
    private val executableBitSentinel: Set<PosixFilePermission> =
        setOf(
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_EXECUTE,
        )

    private companion object {
        const val BINARY_NAME: String = "spectre-screencapture"
        const val RESOURCE_PATH: String = "native/macos/$BINARY_NAME"

        @JvmStatic
        fun defaultClasspathLookup(): InputStream? =
            HelperBinaryExtractor::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)

        @JvmStatic
        fun defaultTargetDir(): Path = Files.createTempDirectory("spectre-screencapture-")
    }
}
