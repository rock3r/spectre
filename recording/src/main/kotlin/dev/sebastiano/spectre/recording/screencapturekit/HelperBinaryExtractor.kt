package dev.sebastiano.spectre.recording.screencapturekit

import dev.sebastiano.spectre.recording.HelperExtractionPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * Extracts the bundled `SpectreCaptureHelper.app` ScreenCaptureKit helper out of the recording
 * module's jar resources and onto disk so a subprocess can `exec` the nested executable.
 *
 * The Gradle assemble tasks stage a proper macOS app bundle at [HelperAppBundle.RESOURCE_ROOT]
 * inside the jar — this class is the corresponding read side.
 *
 * Lifecycle:
 * 1. First [extract] call checks for the [OVERRIDE_ENV] env var and returns that path directly if
 *    set (dev-iteration escape hatch, skips JAR extraction). Accepts either a nested executable
 *    path or a path to the `.app` bundle itself.
 * 2. Otherwise it loads the staged bundle resources, copies them to [HELPER_DIR_PROPERTY] (if set)
 *    or [targetDirProvider]'s directory under a fixed [HelperAppBundle.APP_DIR_NAME], and chmods
 *    the executable.
 * 3. Subsequent calls return the cached executable path without re-extracting.
 * 4. Caller is responsible for the lifetime of the files. By default the helper lands in Spectre's
 *    stable per-user helper directory so macOS TCC can keep recognising the same bundle path and
 *    identity.
 *
 * All seams (env lookup, system-property lookup, material locator, target dir) are injectable so
 * unit tests can drive the extractor with arbitrary bytes against arbitrary directories without
 * touching env / system properties / classpath / temp dir.
 *
 * ## macOS TCC and stable bundle identity
 *
 * Screen Recording TCC for an app-bundled helper pins to **bundle ID + code signature** when the
 * helper is Developer ID signed (#191). Ad-hoc / unsigned local builds still benefit from a fixed
 * on-disk path (`…/helpers/SpectreCaptureHelper.app`) so path-based recognition survives rebuilds
 * that keep the same install location.
 *
 * Preflight, capture, and request always exec [HelperAppBundle.EXECUTABLE_RELATIVE_PATH] so grants
 * accrue to the Spectre helper row in Settings, not the spawning terminal or JVM.
 */
internal class HelperBinaryExtractor(
    private val envLookup: (String) -> String? = System::getenv,
    private val sysPropLookup: (String) -> String? = System::getProperty,
    private val materialLocator: () -> HelperAppBundleMaterial? = ::defaultMaterialLookup,
    private val targetDirProvider: () -> Path = ::defaultTargetDir,
) {

    private var cached: Path? = null

    @Synchronized
    fun extract(): Path {
        cached?.let {
            return it
        }

        // Env var override: use a pre-existing binary or .app at the given path, skipping
        // classpath extraction. Mirrors WaylandHelperBinaryExtractor's SPECTRE_WAYLAND_HELPER
        // pattern. Developer-only escape hatch for iterating on the Swift helper without
        // rebuilding the JAR. Never set this in environments that ingest untrusted input.
        envLookup(OVERRIDE_ENV)
            ?.takeIf { it.isNotBlank() }
            ?.let { override ->
                val path = resolveOverrideExecutable(Path.of(override))
                check(Files.isExecutable(path)) {
                    "$OVERRIDE_ENV points at '$override' but no executable helper was found at " +
                        "'$path'. Point it at ${HelperAppBundle.EXECUTABLE_NAME} or " +
                        "${HelperAppBundle.APP_DIR_NAME}, or unset it to use the bundled helper."
                }
                cached = path
                return path
            }

        val material =
            materialLocator()
                ?: throw HelperNotBundledException(
                    "Bundled helper app not found under classpath resource " +
                        "'${HelperAppBundle.RESOURCE_ROOT}'. The recording module's macOS build " +
                        "stages '${HelperAppBundle.APP_DIR_NAME}' there via the " +
                        "assembleScreenCaptureKitHelper task — verify the module was built on " +
                        "macOS with the Swift toolchain available."
                )

        val propertyDir =
            sysPropLookup(HELPER_DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        // Fixed app path (no content-hash subdir): stable TCC identity across updates that
        // overwrite the same bundle in place. Fingerprint is used only to detect staleness.
        val appRoot =
            if (propertyDir != null) {
                propertyDir.resolve(HelperAppBundle.APP_DIR_NAME)
            } else {
                targetDirProvider().resolve(HelperAppBundle.APP_DIR_NAME)
            }
        val executable = appRoot.resolve(HelperAppBundle.EXECUTABLE_RELATIVE_PATH)
        val extracted =
            HelperExtractionPaths.withExtractionLock(appRoot.parent) {
                writeBundleIfNeeded(appRoot, material)
                markExecutable(executable)
                executable
            }
        cached = extracted
        return extracted
    }

    private fun writeBundleIfNeeded(appRoot: Path, material: HelperAppBundleMaterial) {
        val executable = appRoot.resolve(HelperAppBundle.EXECUTABLE_RELATIVE_PATH)
        val infoPlist = appRoot.resolve("Contents/Info.plist")
        val pkgInfo = appRoot.resolve("Contents/PkgInfo")
        val icon = appRoot.resolve("Contents/Resources/AppIcon.icns")
        val fingerprintFile = appRoot.resolve(ContentsRelative.FINGERPRINT_FILE)

        val desiredFingerprint = material.contentFingerprint()
        val currentFingerprint =
            if (Files.isRegularFile(fingerprintFile)) {
                Files.readString(fingerprintFile).trim()
            } else {
                null
            }
        val upToDate =
            currentFingerprint == desiredFingerprint &&
                Files.isRegularFile(executable) &&
                Files.isRegularFile(infoPlist) &&
                Files.isRegularFile(pkgInfo) &&
                (material.iconIcns == null || Files.isRegularFile(icon))

        if (upToDate) return

        Files.createDirectories(executable.parent)
        Files.createDirectories(icon.parent)
        Files.write(executable, material.executable)
        Files.write(infoPlist, material.infoPlist)
        Files.write(pkgInfo, material.pkgInfo)
        if (material.iconIcns != null) {
            Files.write(icon, material.iconIcns)
        } else if (Files.exists(icon)) {
            Files.delete(icon)
        }
        Files.writeString(fingerprintFile, desiredFingerprint)
    }

    private fun markExecutable(path: Path) {
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
        /**
         * Environment variable that, when set, points the extractor at a pre-existing helper
         * executable or `.app` and skips classpath extraction entirely. Mirrors
         * [dev.sebastiano.spectre.recording.portal.WaylandHelperBinaryExtractor]'s
         * `SPECTRE_WAYLAND_HELPER` escape hatch. Developer-only; do not use in production recording
         * pipelines or environments that ingest untrusted input.
         */
        const val OVERRIDE_ENV: String = "SPECTRE_SCREENCAPTURE_HELPER"

        /**
         * JVM system property that overrides the default stable extraction directory. The
         * [HelperAppBundle.APP_DIR_NAME] is created under this directory. Useful when a project
         * wants the helper at a repo- or build-specific path for macOS TCC grants.
         */
        const val HELPER_DIR_PROPERTY: String = "spectre.recording.screencapturekit.helperDir"

        @JvmStatic
        fun defaultMaterialLookup(): HelperAppBundleMaterial? {
            val executable =
                HelperBinaryExtractor::class
                    .java
                    .classLoader
                    .getResourceAsStream(HelperAppBundle.EXECUTABLE_RESOURCE_PATH)
                    ?.use { it.readBytes() } ?: return null
            val infoPlist =
                HelperBinaryExtractor::class
                    .java
                    .classLoader
                    .getResourceAsStream(HelperAppBundle.INFO_PLIST_RESOURCE_PATH)
                    ?.use { it.readBytes() } ?: return null
            val pkgInfo =
                HelperBinaryExtractor::class
                    .java
                    .classLoader
                    .getResourceAsStream(HelperAppBundle.PKG_INFO_RESOURCE_PATH)
                    ?.use { it.readBytes() } ?: "APPL????".toByteArray(Charsets.US_ASCII)
            val iconIcns =
                HelperBinaryExtractor::class
                    .java
                    .classLoader
                    .getResourceAsStream(HelperAppBundle.ICON_RESOURCE_PATH)
                    ?.use { it.readBytes() }
            return HelperAppBundleMaterial(
                executable = executable,
                infoPlist = infoPlist,
                pkgInfo = pkgInfo,
                iconIcns = iconIcns,
            )
        }

        @JvmStatic
        fun defaultTargetDir(): Path =
            HelperExtractionPaths.defaultHelperDir(HelperAppBundle.EXECUTABLE_NAME)

        fun resolveOverrideExecutable(override: Path): Path {
            if (Files.isDirectory(override) && override.fileName.toString().endsWith(".app")) {
                return override.resolve(HelperAppBundle.EXECUTABLE_RELATIVE_PATH)
            }
            // If the override is the app root named without trailing check, still try nested path
            // when the bare path is not executable.
            if (!Files.isExecutable(override)) {
                val nested = override.resolve(HelperAppBundle.EXECUTABLE_RELATIVE_PATH)
                if (Files.isExecutable(nested)) return nested
            }
            return override
        }
    }
}

/**
 * In-memory material for staging [HelperAppBundle.APP_DIR_NAME] on disk. Test seams inject this
 * instead of jar resources.
 */
internal data class HelperAppBundleMaterial(
    val executable: ByteArray,
    val infoPlist: ByteArray,
    val pkgInfo: ByteArray = "APPL????".toByteArray(Charsets.US_ASCII),
    val iconIcns: ByteArray? = null,
) {
    fun contentFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(executable)
        digest.update(infoPlist)
        digest.update(pkgInfo)
        iconIcns?.let { digest.update(it) }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_BYTE_WIDTH, '0')
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HelperAppBundleMaterial) return false
        return executable.contentEquals(other.executable) &&
            infoPlist.contentEquals(other.infoPlist) &&
            pkgInfo.contentEquals(other.pkgInfo) &&
            ((iconIcns == null && other.iconIcns == null) ||
                (iconIcns != null &&
                    other.iconIcns != null &&
                    iconIcns.contentEquals(other.iconIcns)))
    }

    override fun hashCode(): Int {
        var result = executable.contentHashCode()
        result = HASH_PRIME * result + infoPlist.contentHashCode()
        result = HASH_PRIME * result + pkgInfo.contentHashCode()
        result = HASH_PRIME * result + (iconIcns?.contentHashCode() ?: 0)
        return result
    }

    private companion object {
        private const val BYTE_MASK: Int = 0xff
        private const val HEX_RADIX: Int = 16
        private const val HEX_BYTE_WIDTH: Int = 2
        private const val HASH_PRIME: Int = 31
    }
}

/** Relative paths written under the extracted `.app` that are not part of the public bundle API. */
private object ContentsRelative {
    const val FINGERPRINT_FILE: String = "Contents/Resources/.spectre-helper-fingerprint"
}
