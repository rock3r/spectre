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
                },
                ".NETCoreApp,Version=v8.0/win-arm64": {
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
            {"targets":{
              ".NETCoreApp,Version=v8.0/win-x64":{
                "SpectreWindowCapture/1.0.0":{"runtime":{"SpectreWindowCapture.dll":{}}}
              },
              ".NETCoreApp,Version=v8.0/win-arm64":{
                "SpectreWindowCapture/1.0.0":{"runtime":{"SpectreWindowCapture.dll":{}}}
              }
            }}
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
        val names =
            WindowsGraphicsCaptureHelperPackagingContract.runtimeAssetBaseNames(deps, "x64")
        assertTrue(names.contains("Foo.dll"), names.toString())
        assertTrue(names.contains("Bar.dll"), names.toString())
        assertTrue(!names.contains("Foo.pdb"), names.toString())
    }

    @Test
    fun `runtimeAssetBaseNames prefers RID matching the requested arch`() {
        val deps =
            """
            {
              "targets": {
                ".NETCoreApp,Version=v8.0/win-x64": {
                  "pkg/1": {
                    "native": { "runtimes/win-x64/native/OnlyX64.dll": {} }
                  }
                },
                ".NETCoreApp,Version=v8.0/win-arm64": {
                  "pkg/1": {
                    "native": { "runtimes/win-arm64/native/OnlyArm64.dll": {} }
                  }
                }
              }
            }
            """
                .trimIndent()
        val x64 =
            WindowsGraphicsCaptureHelperPackagingContract.runtimeAssetBaseNames(deps, "x64")
        val arm64 =
            WindowsGraphicsCaptureHelperPackagingContract.runtimeAssetBaseNames(deps, "arm64")
        assertTrue(x64.contains("OnlyX64.dll") && !x64.contains("OnlyArm64.dll"), x64.toString())
        assertTrue(
            arm64.contains("OnlyArm64.dll") && !arm64.contains("OnlyX64.dll"),
            arm64.toString(),
        )
    }

    @Test
    fun `null package body under selected target is rejected`() {
        val deps =
            """
            {
              "targets": {
                ".NETCoreApp,Version=v8.0/win-x64": {
                  "SpectreWindowCapture/1.0.0": null
                }
              }
            }
            """
                .trimIndent()
        val files = completeFixedRequiredFiles()
        val entrySizes = files.mapKeys { "native/windows/x64/${it.key}" }
        val errors =
            WindowsGraphicsCaptureHelperPackagingContract.validateJarEntries(
                entrySizes,
                depsJsonByArch = mapOf("x64" to deps),
                arches = listOf("x64"),
            )
        assertTrue(
            errors.any { it.contains("invalid") && it.contains("package") },
            "expected null package body rejection; errors=$errors",
        )
    }

    @Test
    fun `null runtime asset table is rejected`() {
        val deps =
            """
            {
              "targets": {
                ".NETCoreApp,Version=v8.0/win-x64": {
                  "SpectreWindowCapture/1.0.0": {
                    "runtime": null
                  }
                }
              }
            }
            """
                .trimIndent()
        val files = completeFixedRequiredFiles()
        val entrySizes = files.mapKeys { "native/windows/x64/${it.key}" }
        val errors =
            WindowsGraphicsCaptureHelperPackagingContract.validateJarEntries(
                entrySizes,
                depsJsonByArch = mapOf("x64" to deps),
                arches = listOf("x64"),
            )
        assertTrue(
            errors.any { it.contains("invalid") && it.contains("runtime") },
            "expected null runtime table rejection; errors=$errors",
        )
    }

    @Test
    fun `runtimeTarget name selects the exact target key`() {
        val deps =
            """
            {
              "runtimeTarget": { "name": ".NETCoreApp,Version=v8.0/win-x64" },
              "targets": {
                ".NETCoreApp,Version=v7.0/win-x64": {
                  "pkg/1": { "runtime": { "WrongGeneration.dll": {} } }
                },
                ".NETCoreApp,Version=v8.0/win-x64": {
                  "pkg/1": { "runtime": { "RightGeneration.dll": {} } }
                }
              }
            }
            """
                .trimIndent()
        val names =
            WindowsGraphicsCaptureHelperPackagingContract.runtimeAssetBaseNames(deps, "x64")
        assertTrue(names.contains("RightGeneration.dll"), names.toString())
        assertTrue(!names.contains("WrongGeneration.dll"), names.toString())
    }

    @Test
    fun `runtimeTarget name RID must match the directory arch`() {
        val mismatched =
            """
            {
              "runtimeTarget": { "name": ".NETCoreApp,Version=v8.0/win-x64" },
              "targets": {
                ".NETCoreApp,Version=v8.0/win-arm64": {
                  "pkg/1": {
                    "runtime": { "SpectreWindowCapture.dll": {} }
                  }
                }
              }
            }
            """
                .trimIndent()
        val files = completeFixedRequiredFiles()
        val entrySizes = files.mapKeys { "native/windows/arm64/${it.key}" }
        val errors =
            WindowsGraphicsCaptureHelperPackagingContract.validateJarEntries(
                entrySizes,
                depsJsonByArch = mapOf("arm64" to mismatched),
                arches = listOf("arm64"),
            )
        assertTrue(
            errors.any {
                it.contains("arch-mismatched") && it.contains("runtimeTarget.name")
            },
            "expected runtimeTarget RID mismatch rejection; errors=$errors",
        )
    }

    @Test
    fun `wrong-arch-only deps json is rejected for the directory arch`() {
        val x64OnlyDeps =
            """
            {
              "targets": {
                ".NETCoreApp,Version=v8.0/win-x64": {
                  "pkg/1": {
                    "native": { "runtimes/win-x64/native/OnlyX64.dll": {} }
                  }
                }
              }
            }
            """
                .trimIndent()
        val files = completeFixedRequiredFiles()
        val entrySizes =
            files.mapKeys { "native/windows/arm64/${it.key}" } +
                files.mapKeys { "native/windows/x64/${it.key}" }
        val errors =
            WindowsGraphicsCaptureHelperPackagingContract.validateJarEntries(
                entrySizes,
                // arm64 directory claims a win-x64-only deps.json
                depsJsonByArch = mapOf("arm64" to x64OnlyDeps, "x64" to x64OnlyDeps),
            )
        assertTrue(
            errors.any { it.contains("arch-mismatched") && it.contains("arm64") },
            "expected arch mismatch for arm64; errors=$errors",
        )
    }

    @Test
    fun `runtimeTargets assets are required by the deps closure`() {
        val deps =
            """
            {
              "targets": {
                ".NETCoreApp,Version=v8.0/win-x64": {
                  "pkg/1": {
                    "runtimeTargets": {
                      "runtimes/win-x64/native/FromRuntimeTargets.dll": {}
                    }
                  }
                },
                ".NETCoreApp,Version=v8.0/win-arm64": {
                  "pkg/1": {
                    "runtimeTargets": {
                      "runtimes/win-arm64/native/FromRuntimeTargets.dll": {}
                    }
                  }
                }
              }
            }
            """
                .trimIndent()
        val files = completeFixedRequiredFiles()
        // No FromRuntimeTargets.dll in the tree.
        val entrySizes =
            WindowsGraphicsCaptureHelperPackagingContract.ARCHES.flatMap { arch ->
                    files.map { (base, size) -> "native/windows/$arch/$base" to size }
                }
                .toMap()
        val errors =
            WindowsGraphicsCaptureHelperPackagingContract.validateJarEntries(
                entrySizes,
                depsJsonByArch = mapOf("x64" to deps, "arm64" to deps),
            )
        assertTrue(
            errors.any {
                it.contains("FromRuntimeTargets.dll") && it.contains("required by")
            },
            "expected runtimeTargets companion to be required; errors=$errors",
        )
    }

    @Test
    fun `null or empty selected target body is rejected`() {
        val nullBody =
            """
            {"targets":{".NETCoreApp,Version=v8.0/win-x64":null}}
            """
                .trimIndent()
        val emptyBody =
            """
            {"targets":{".NETCoreApp,Version=v8.0/win-x64":{}}}
            """
                .trimIndent()
        val files = completeFixedRequiredFiles()
        val entrySizes = files.mapKeys { "native/windows/x64/${it.key}" }
        val nullErrors =
            WindowsGraphicsCaptureHelperPackagingContract.validateJarEntries(
                entrySizes,
                depsJsonByArch = mapOf("x64" to nullBody),
                arches = listOf("x64"),
            )
        val emptyErrors =
            WindowsGraphicsCaptureHelperPackagingContract.validateJarEntries(
                entrySizes,
                depsJsonByArch = mapOf("x64" to emptyBody),
                arches = listOf("x64"),
            )
        assertTrue(
            nullErrors.any { it.contains("invalid") },
            "expected null target body to fail; errors=$nullErrors",
        )
        assertTrue(
            emptyErrors.any { it.contains("invalid") },
            "expected empty target body to fail; errors=$emptyErrors",
        )
    }

    @Test
    fun `empty targets map in deps json is rejected`() {
        val files = completeFixedRequiredFiles()
        val entrySizes =
            WindowsGraphicsCaptureHelperPackagingContract.ARCHES.flatMap { arch ->
                    files.map { (base, size) -> "native/windows/$arch/$base" to size }
                }
                .toMap()
        val errors =
            WindowsGraphicsCaptureHelperPackagingContract.validateJarEntries(
                entrySizes,
                depsJsonByArch = mapOf("x64" to "{}", "arm64" to """{"targets":{}}"""),
            )
        assertTrue(
            errors.any { it.contains("invalid") && it.contains("x64") },
            "expected empty/missing targets to fail; errors=$errors",
        )
        assertTrue(
            errors.any { it.contains("invalid") && it.contains("arm64") },
            "expected empty targets to fail for arm64; errors=$errors",
        )
    }

    @Test
    fun `runtimeTargets are filtered by rid metadata`() {
        val deps =
            """
            {
              "targets": {
                ".NETCoreApp,Version=v8.0": {
                  "pkg/1": {
                    "runtimeTargets": {
                      "runtimes/win-x64/native/OnlyX64Rt.dll": {
                        "rid": "win-x64",
                        "assetType": "native"
                      },
                      "runtimes/win-arm64/native/OnlyArm64Rt.dll": {
                        "rid": "win-arm64",
                        "assetType": "native"
                      }
                    }
                  }
                }
              }
            }
            """
                .trimIndent()
        val x64 =
            WindowsGraphicsCaptureHelperPackagingContract.runtimeAssetBaseNames(deps, "x64")
        val arm64 =
            WindowsGraphicsCaptureHelperPackagingContract.runtimeAssetBaseNames(deps, "arm64")
        assertTrue(x64.contains("OnlyX64Rt.dll") && !x64.contains("OnlyArm64Rt.dll"), x64.toString())
        assertTrue(
            arm64.contains("OnlyArm64Rt.dll") && !arm64.contains("OnlyX64Rt.dll"),
            arm64.toString(),
        )
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
