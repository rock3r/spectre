package dev.sebastiano.spectre.recording.portal

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Extracts the bundled `spectre-wayland-helper` Rust binary out of the recording module's jar
 * resources and onto disk so a subprocess can `exec` it. Mirrors
 * [dev.sebastiano.spectre.recording.screencapturekit.HelperBinaryExtractor]'s shape — same
 * extraction pattern, same chmod-executable handling, same in-memory cache after first extract —
 * but for the Linux helper instead of the macOS Swift one.
 *
 * Per-arch: the resource path is `native/linux/<arch>/spectre-wayland-helper` where `<arch>` is
 * `x86_64` or `aarch64`. The extractor probes the matching arch from `os.arch` / `uname -m`. Linux
 * ELF doesn't natively support fat / universal binaries the way macOS mach-o does, so we ship
 * per-arch and pick at extraction time.
 *
 * Override seam (env var `SPECTRE_WAYLAND_HELPER`): pointing this at a local
 * `target/release/spectre-wayland-helper` path skips the resource probe entirely and uses that
 * binary directly. Useful during dev iteration on the helper without rebuilding the jar; not for
 * production.
 */
internal class WaylandHelperBinaryExtractor(
    private val envLookup: (String) -> String? = System::getenv,
    private val resourceLocator: (String) -> InputStream? = ::defaultClasspathLookup,
    private val targetDirProvider: () -> Path = ::defaultTargetDir,
    private val archProvider: () -> String = ::defaultArch,
) {

    private var cached: Path? = null

    fun extract(): Path {
        cached?.let {
            return it
        }
        // Dev-override path. The env var lets a developer point the recorder at their
        // `cargo build`'s output directly without re-bundling. Verifies the path is
        // executable before returning so a stale path surfaces a clear error.
        //
        // Trust boundary (R5): `SPECTRE_WAYLAND_HELPER` is a **developer-only escape hatch**.
        // It routes the entire portal-recording pipeline through whatever binary the env var
        // names, with no signature, hash, or path check. Never set it in an environment that
        // ingests untrusted input (CI runners taking input from arbitrary forks, production
        // recording pipelines, etc.). The bundled helper is the only supported configuration
        // for non-dev use.
        envLookup(OVERRIDE_ENV)
            ?.takeIf { it.isNotBlank() }
            ?.let { override ->
                val path = Path.of(override)
                check(Files.isExecutable(path)) {
                    "$OVERRIDE_ENV pointed at $path but it isn't an executable file. Either fix " +
                        "the env var or unset it to use the bundled helper."
                }
                cached = path
                return path
            }

        val arch = archProvider()
        val resourcePath = "$RESOURCE_PATH_BASE/$arch/$BINARY_NAME"
        val source =
            resourceLocator(resourcePath)
                ?: throw HelperNotBundledException(
                    "Bundled helper binary not found at classpath resource '$resourcePath'. " +
                        "The recording module's Linux build stages 'spectre-wayland-helper' " +
                        "there via the assembleWaylandHelper task — verify the module was " +
                        "built on Linux with the Rust toolchain (cargo) available, or set " +
                        "$OVERRIDE_ENV to a locally-built binary path for dev iteration."
                )
        val target = targetDirProvider().resolve(BINARY_NAME)
        Files.createDirectories(target.parent)
        source.use { stream -> Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING) }
        markExecutable(target)
        cached = target
        return target
    }

    private fun markExecutable(path: Path) {
        // POSIX path: explicit mode bits so we get rwxr-xr-x rather than relying on the
        // JVM's umask. Fallback for non-POSIX filesystems (rare on Linux, but tmpfs in
        // some container setups refuses POSIX attribute writes) is the old setExecutable.
        @Suppress("TooGenericExceptionCaught")
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        } catch (_: UnsupportedOperationException) {
            path.toFile().setExecutable(true, false)
        } catch (_: Throwable) {
            path.toFile().setExecutable(true, false)
        }
        check(Files.isExecutable(path)) {
            "Failed to mark Wayland helper at $path as executable — recording will fail to spawn"
        }
    }

    private companion object {
        const val BINARY_NAME: String = "spectre-wayland-helper"
        const val RESOURCE_PATH_BASE: String = "native/linux"
        const val OVERRIDE_ENV: String = "SPECTRE_WAYLAND_HELPER"

        @JvmStatic
        fun defaultClasspathLookup(resource: String): InputStream? =
            WaylandHelperBinaryExtractor::class.java.classLoader.getResourceAsStream(resource)

        @JvmStatic
        fun defaultTargetDir(): Path = Files.createTempDirectory("spectre-wayland-helper-")

        /**
         * Map JVM's `os.arch` to the directory name we ship the helper under. The values mirror
         * what `uname -m` prints (which is what cargo's `--target` uses). Note `aarch64`
         * specifically — JVM reports it for ARM64 Linux even though some other contexts use
         * `arm64`; we use `aarch64` as the canonical Linux Rust-target spelling.
         */
        @JvmStatic
        fun defaultArch(): String =
            when (val osArch = System.getProperty("os.arch").orEmpty().lowercase()) {
                "amd64",
                "x86_64",
                "x64" -> "x86_64"
                "aarch64",
                "arm64" -> "aarch64"
                else -> osArch // surfaces in HelperNotBundledException so the user knows
            }
    }
}

/**
 * Raised when the bundled helper binary isn't at the expected classpath resource. Distinct from a
 * bare `IllegalStateException` so [dev.sebastiano.spectre.recording.AutoRecorder] can pattern-match
 * the cross-platform-jar case (built on macOS or Windows, the Linux helper isn't in the jar) and
 * fall back to ffmpeg region capture rather than throwing.
 *
 * Mirrors [dev.sebastiano.spectre.recording.screencapturekit.HelperNotBundledException]'s role for
 * the SCK helper.
 */
internal class HelperNotBundledException(message: String) : IllegalStateException(message)
