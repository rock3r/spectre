package dev.sebastiano.spectre.recording.screencapturekit

import dev.sebastiano.spectre.recording.HelperExtractionPaths
import java.net.JarURLConnection
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.jar.JarFile
import kotlin.io.path.isRegularFile

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
 * 2. Otherwise it loads **every** file under the staged app resource tree (including
 *    `Contents/_CodeSignature/` when present), copies them byte-for-byte to a fixed install path,
 *    and chmods the executable. The sealed signature and any stapled ticket payload travel with the
 *    tree; extraction must not rewrite sealed contents.
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
                // Mode bits are not part of the code signature hash; ensure the nested Mach-O
                // is executable after extract without rewriting sealed file contents.
                markExecutable(executable)
                executable
            }
        cached = extracted
        return extracted
    }

    private fun writeBundleIfNeeded(appRoot: Path, material: HelperAppBundleMaterial) {
        // Fingerprint lives *outside* the .app so we never mutate sealed Resources/CodeSignature.
        val fingerprintFile = appRoot.parent.resolve(".${HelperAppBundle.APP_DIR_NAME}.fingerprint")
        val desiredFingerprint = material.contentFingerprint()
        val currentFingerprint =
            if (Files.isRegularFile(fingerprintFile)) {
                Files.readString(fingerprintFile).trim()
            } else {
                null
            }
        val executable = appRoot.resolve(HelperAppBundle.EXECUTABLE_RELATIVE_PATH)
        val upToDate =
            currentFingerprint == desiredFingerprint &&
                Files.isRegularFile(executable) &&
                material.files.keys.all { rel -> Files.isRegularFile(appRoot.resolve(rel)) }

        if (upToDate) return

        if (Files.exists(appRoot)) {
            appRoot.toFile().deleteRecursively()
        }
        for ((relative, bytes) in material.files) {
            val target = appRoot.resolve(relative)
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }
        Files.createDirectories(fingerprintFile.parent)
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
            val classLoader = HelperBinaryExtractor::class.java.classLoader
            val sample =
                classLoader.getResource(HelperAppBundle.INFO_PLIST_RESOURCE_PATH) ?: return null
            val files =
                when (sample.protocol) {
                    "file" -> loadFromFilesystem(sample.toURI())
                    "jar" -> loadFromJar(sample)
                    else -> return null
                }
            if (files.isEmpty()) return null
            if (!files.containsKey(HelperAppBundle.EXECUTABLE_RELATIVE_PATH)) return null
            return HelperAppBundleMaterial(files = files)
        }

        private fun loadFromFilesystem(infoPlistUri: URI): Map<String, ByteArray> {
            val infoPlist = Path.of(infoPlistUri)
            // …/SpectreCaptureHelper.app/Contents/Info.plist → app root
            val appRoot = infoPlist.parent.parent
            val files = linkedMapOf<String, ByteArray>()
            Files.walkFileTree(
                appRoot,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (file.isRegularFile()) {
                            val rel = appRoot.relativize(file).toString().replace('\\', '/')
                            files[rel] = Files.readAllBytes(file)
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            return files
        }

        private fun loadFromJar(sample: java.net.URL): Map<String, ByteArray> {
            val connection = sample.openConnection() as JarURLConnection
            // Prefer the shared JarFile from the connection when available.
            @Suppress("TooGenericExceptionCaught")
            val jarFile: JarFile =
                try {
                    connection.jarFile
                } catch (_: Exception) {
                    val jarUrl = sample.toString().substringBefore("!/")
                    val jarPath = jarUrl.removePrefix("jar:file:").let { URI(it).path }
                    JarFile(jarPath)
                }
            val prefix = HelperAppBundle.RESOURCE_ROOT.trimEnd('/') + "/"
            val files = linkedMapOf<String, ByteArray>()
            val entries = jarFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (!entry.name.startsWith(prefix)) continue
                val rel = entry.name.removePrefix(prefix)
                if (rel.isEmpty()) continue
                jarFile.getInputStream(entry).use { stream -> files[rel] = stream.readBytes() }
            }
            return files
        }

        @JvmStatic
        fun defaultTargetDir(): Path =
            HelperExtractionPaths.defaultHelperDir(HelperAppBundle.EXECUTABLE_NAME)

        fun resolveOverrideExecutable(override: Path): Path {
            if (Files.isDirectory(override) && override.fileName.toString().endsWith(".app")) {
                return override.resolve(HelperAppBundle.EXECUTABLE_RELATIVE_PATH)
            }
            if (!Files.isExecutable(override)) {
                val nested = override.resolve(HelperAppBundle.EXECUTABLE_RELATIVE_PATH)
                if (Files.isExecutable(nested)) return nested
            }
            return override
        }
    }
}

/**
 * Full on-disk material for [HelperAppBundle.APP_DIR_NAME]. Keys are paths relative to the `.app`
 * root (e.g. `Contents/MacOS/spectre-screencapture`, `Contents/_CodeSignature/CodeResources`).
 *
 * Must include every sealed file from the staged jar so Developer ID signatures and stapled tickets
 * survive extraction (#191).
 */
internal data class HelperAppBundleMaterial(val files: Map<String, ByteArray>) {
    fun contentFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (key in files.keys.sorted()) {
            digest.update(key.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(files.getValue(key))
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_BYTE_WIDTH, '0')
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HelperAppBundleMaterial) return false
        if (files.size != other.files.size) return false
        for ((key, value) in files) {
            val otherValue = other.files[key] ?: return false
            if (!value.contentEquals(otherValue)) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = files.size
        for ((key, value) in files) {
            result = HASH_PRIME * result + key.hashCode()
            result = HASH_PRIME * result + value.contentHashCode()
        }
        return result
    }

    private companion object {
        private const val BYTE_MASK: Int = 0xff
        private const val HEX_RADIX: Int = 16
        private const val HEX_BYTE_WIDTH: Int = 2
        private const val HASH_PRIME: Int = 31
    }
}

/** Test helper: build material for a minimal app tree (no code signature). */
internal fun helperAppBundleMaterial(
    executable: ByteArray,
    infoPlist: ByteArray,
    pkgInfo: ByteArray = "APPL????".toByteArray(Charsets.US_ASCII),
    iconIcns: ByteArray? = null,
    extraFiles: Map<String, ByteArray> = emptyMap(),
): HelperAppBundleMaterial {
    val files =
        linkedMapOf(
            HelperAppBundle.EXECUTABLE_RELATIVE_PATH to executable,
            "Contents/Info.plist" to infoPlist,
            "Contents/PkgInfo" to pkgInfo,
        )
    if (iconIcns != null) {
        files["Contents/Resources/AppIcon.icns"] = iconIcns
    }
    files.putAll(extraFiles)
    return HelperAppBundleMaterial(files = files)
}
