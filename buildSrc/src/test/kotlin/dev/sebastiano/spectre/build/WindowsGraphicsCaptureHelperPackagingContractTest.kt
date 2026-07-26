package dev.sebastiano.spectre.build

import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves the shared Windows helper packaging contract rejects incomplete payloads
 * (orphan exe, missing deps.json companions, empty entries) and accepts a complete
 * multi-file tree. Uses the same [WindowsGraphicsCaptureHelperPackagingContract] object
 * as `:recording-windows:verifyRecordingWindowsHelper` and `:verifyMavenLocalPublication`.
 */
class WindowsGraphicsCaptureHelperPackagingContractTest {

    @Test
    fun `orphan exe only payload is rejected for both arches`() {
        val entries =
            mapOf(
                "native/windows/x64/spectre-window-capture.exe" to 1024L,
                "native/windows/arm64/spectre-window-capture.exe" to 1024L,
            )
        val errors = WindowsGraphicsCaptureHelperPackagingContract.validateJarEntries(entries)
        assertTrue(errors.isNotEmpty(), "expected orphan-exe payload to fail")
        assertTrue(
            errors.any { it.contains("SpectreWindowCapture.dll") && it.contains("x64") },
            "expected missing managed assembly for x64; errors=$errors",
        )
        assertTrue(
            errors.any { it.contains("SpectreWindowCapture.dll") && it.contains("arm64") },
            "expected missing managed assembly for arm64; errors=$errors",
        )
    }

    @Test
    fun `missing companion required by deps json is rejected`() {
        val deps =
            """
            {
              "runtimeTarget": { "name": ".NETCoreApp,Version=v8.0/win-x64" },
              "targets": {
                ".NETCoreApp,Version=v8.0/win-x64": {
                  "SpectreWindowCapture/1.0.0": {
                    "runtime": { "SpectreWindowCapture.dll": {} }
                  },
                  "Microsoft.Graphics.Win2D/1.4.0": {
                    "native": { "runtimes/win-x64/native/Microsoft.Graphics.Canvas.dll": {} }
                  }
                }
              }
            }
            """
                .trimIndent()
        val baseFiles = completeFixedRequiredFiles()
        val x64Files = baseFiles - "Microsoft.Graphics.Canvas.dll"
        val entrySizes =
            x64Files.mapKeys { "native/windows/x64/${it.key}" } +
                baseFiles.mapKeys { "native/windows/arm64/${it.key}" }
        val errors =
            WindowsGraphicsCaptureHelperPackagingContract.validateJarEntries(
                entrySizes,
                depsJsonByArch = mapOf("x64" to deps, "arm64" to deps),
            )
        assertTrue(
            errors.any {
                it.contains("Microsoft.Graphics.Canvas.dll") &&
                    it.contains("required by") &&
                    it.contains("x64")
            },
            "expected deps-closure failure for missing Canvas.dll; errors=$errors",
        )
    }

    @Test
    fun `complete fixed required set with deps closure passes`() {
        val deps =
            """
            {
              "targets": {
                ".NETCoreApp,Version=v8.0/win-x64": {
                  "SpectreWindowCapture/1.0.0": {
                    "runtime": { "SpectreWindowCapture.dll": {} }
                  }
                }
              }
            }
            """
                .trimIndent()
        val files = completeFixedRequiredFiles()
        val entrySizes =
            WindowsGraphicsCaptureHelperPackagingContract.ARCHES.flatMap { arch ->
                    files.map { (base, size) -> "native/windows/$arch/$base" to size }
                }
                .toMap()
        val depsByArch =
            WindowsGraphicsCaptureHelperPackagingContract.ARCHES.associateWith { deps }
        val errors =
            WindowsGraphicsCaptureHelperPackagingContract.validateJarEntries(entrySizes, depsByArch)
        assertTrue(errors.isEmpty(), "expected complete payload to pass; errors=$errors")
    }

    @Test
    fun `empty required entry is rejected`() {
        val files = completeFixedRequiredFiles().toMutableMap()
        files["SpectreWindowCapture.dll"] = 0L
        val entrySizes =
            WindowsGraphicsCaptureHelperPackagingContract.ARCHES.flatMap { arch ->
                    files.map { (base, size) -> "native/windows/$arch/$base" to size }
                }
                .toMap()
        val errors = WindowsGraphicsCaptureHelperPackagingContract.validateJarEntries(entrySizes)
        assertTrue(
            errors.any { it.contains("empty") && it.contains("SpectreWindowCapture.dll") },
            "expected empty-entry failure; errors=$errors",
        )
    }

    @Test
    fun `validateZip rejects incomplete jar and accepts complete jar`() {
        val incomplete =
            zipWithEntries(mapOf("native/windows/x64/spectre-window-capture.exe" to "exe"))
        ZipFile(incomplete.toFile()).use { zip ->
            val errors = WindowsGraphicsCaptureHelperPackagingContract.validateZip(zip)
            assertTrue(errors.isNotEmpty(), "incomplete zip must fail")
        }
        incomplete.deleteIfExists()

        val files = completeFixedRequiredFiles()
        val completeEntries =
            WindowsGraphicsCaptureHelperPackagingContract.ARCHES.flatMap { arch ->
                    files.keys.map { base -> "native/windows/$arch/$base" to "payload-$base" }
                }
                .toMap()
        val depsBody =
            """
            {"targets":{".NETCoreApp,Version=v8.0/win-x64":{
              "SpectreWindowCapture/1.0.0":{"runtime":{"SpectreWindowCapture.dll":{}}}
            }}}
            """
                .trimIndent()
        val withDeps =
            completeEntries +
                mapOf(
                    "native/windows/x64/SpectreWindowCapture.deps.json" to depsBody,
                    "native/windows/arm64/SpectreWindowCapture.deps.json" to depsBody,
                )
        val complete = zipWithEntries(withDeps)
        ZipFile(complete.toFile()).use { zip ->
            val errors = WindowsGraphicsCaptureHelperPackagingContract.validateZip(zip)
            assertTrue(errors.isEmpty(), "complete zip must pass; errors=$errors")
        }
        complete.deleteIfExists()
    }

    @Test
    fun `validatePrebuiltHelpersDir rejects orphan exe tree`() {
        val root = createTempDirectory("windows-helpers-prebuilt")
        try {
            for (arch in WindowsGraphicsCaptureHelperPackagingContract.ARCHES) {
                val archDir = root.resolve(arch).toFile()
                archDir.mkdirs()
                archDir.resolve("spectre-window-capture.exe").writeBytes(ByteArray(64) { 1 })
            }
            val errors =
                WindowsGraphicsCaptureHelperPackagingContract.validatePrebuiltHelpersDir(
                    root.toFile()
                )
            assertTrue(errors.isNotEmpty(), "orphan exe prebuilt dir must fail")
            assertTrue(
                errors.any { it.contains("SpectreWindowCapture.dll") },
                "expected missing companion in prebuilt dir; errors=$errors",
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runtimeAssetBaseNames extracts basenames and skips pdb`() {
        val deps =
            """
            {
              "targets": {
                ".NETCoreApp,Version=v8.0/win-x64": {
                  "pkg/1": {
                    "runtime": {
                      "lib/net8.0/Foo.dll": {},
                      "lib/net8.0/Foo.pdb": {}
                    },
                    "native": {
                      "runtimes/win-x64/native/Bar.dll": {}
                    }
                  }
                }
              }
            }
            """
                .trimIndent()
        val names = WindowsGraphicsCaptureHelperPackagingContract.runtimeAssetBaseNames(deps)
        assertTrue(names.contains("Foo.dll"), names.toString())
        assertTrue(names.contains("Bar.dll"), names.toString())
        assertTrue(!names.contains("Foo.pdb"), names.toString())
    }

    @Test
    fun `requiredJarEntryPaths covers both arches and core basenames`() {
        val paths = WindowsGraphicsCaptureHelperPackagingContract.requiredJarEntryPaths()
        assertTrue(paths.contains("native/windows/x64/spectre-window-capture.exe"))
        assertTrue(paths.contains("native/windows/arm64/spectre-window-capture.exe"))
        assertTrue(paths.contains("native/windows/x64/SpectreWindowCapture.dll"))
        assertTrue(paths.contains("native/windows/arm64/Microsoft.WindowsAppRuntime.Bootstrap.dll"))
        assertTrue(
            paths.size ==
                WindowsGraphicsCaptureHelperPackagingContract.ARCHES.size *
                    WindowsGraphicsCaptureHelperPackagingContract.REQUIRED_BASE_NAMES.size
        )
    }

    private fun completeFixedRequiredFiles(): Map<String, Long> =
        WindowsGraphicsCaptureHelperPackagingContract.REQUIRED_BASE_NAMES.associateWith { 64L }

    private fun zipWithEntries(entries: Map<String, String>): java.nio.file.Path {
        val path = createTempFile("windows-helper-contract-", ".jar")
        ZipOutputStream(path.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return path
    }
}
