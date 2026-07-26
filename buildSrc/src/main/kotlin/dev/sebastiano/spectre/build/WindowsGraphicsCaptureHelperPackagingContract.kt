package dev.sebastiano.spectre.build

import groovy.json.JsonSlurper
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Packaging contract for the Windows Graphics Capture helper bundled in
 * `spectre-recording-windows`.
 *
 * The helper is a **framework-dependent multi-file** directory per arch: the runtime
 * extractor copies every regular file under `native/windows/<arch>/` before launch.
 * Verifiers must therefore reject an orphan top-level exe.
 *
 * Contract (each of [ARCHES]):
 * 1. Fixed identity/bootstrap basenames (non-empty).
 * 2. Every runtime/native asset basename named by that arch's
 *    `SpectreWindowCapture.deps.json` must exist as a non-empty sibling.
 * 3. `.pdb` files are not runtime-required.
 */
object WindowsGraphicsCaptureHelperPackagingContract {
    val ARCHES: List<String> = listOf("x64", "arm64")

    const val RESOURCE_ROOT: String = "native/windows"
    const val EXE_NAME: String = "spectre-window-capture.exe"
    const val MANAGED_ASSEMBLY: String = "SpectreWindowCapture.dll"
    const val DEPS_JSON: String = "SpectreWindowCapture.deps.json"
    const val RUNTIME_CONFIG: String = "SpectreWindowCapture.runtimeconfig.json"

    /** Always-required basenames; catches orphan-exe without relying on deps.json alone. */
    val REQUIRED_BASE_NAMES: List<String> =
        listOf(
            EXE_NAME,
            MANAGED_ASSEMBLY,
            DEPS_JSON,
            RUNTIME_CONFIG,
            "Microsoft.WindowsAppRuntime.Bootstrap.dll",
            "Microsoft.WindowsAppRuntime.Bootstrap.Net.dll",
            "WinRT.Runtime.dll",
            "Microsoft.Windows.SDK.NET.dll",
            "Microsoft.Graphics.Canvas.dll",
            "Microsoft.Graphics.Canvas.Interop.dll",
        )

    /** Flat jar entry paths for every fixed required basename on both arches. */
    fun requiredJarEntryPaths(): List<String> =
        ARCHES.flatMap { arch -> REQUIRED_BASE_NAMES.map { base -> jarEntryPath(arch, base) } }

    fun jarEntryPath(arch: String, baseName: String): String = "$RESOURCE_ROOT/$arch/$baseName"

    /**
     * Validates zip/jar packaging of the Windows helper.
     *
     * @param entrySizes map of full jar entry path → uncompressed size
     * @param depsJsonByArch raw deps.json text per arch when available; when provided,
     *   the deps.json runtime/native asset closure is enforced for that arch
     * @return human-readable error messages (empty when the contract is satisfied)
     */
    fun validateJarEntries(
        entrySizes: Map<String, Long>,
        depsJsonByArch: Map<String, String> = emptyMap(),
    ): List<String> {
        val errors = mutableListOf<String>()
        for (arch in ARCHES) {
            errors += validateArch(arch, entrySizes, depsJsonByArch[arch])
        }
        return errors
    }

    /** Validates an open [ZipFile] against the full contract, including deps.json closure. */
    fun validateZip(zip: ZipFile): List<String> {
        val entrySizes = linkedMapOf<String, Long>()
        val depsJsonByArch = linkedMapOf<String, String>()
        zip.entries().asSequence().forEach { entry ->
            if (!entry.isDirectory) {
                entrySizes[entry.name] = entry.size
            }
        }
        for (arch in ARCHES) {
            val depsPath = jarEntryPath(arch, DEPS_JSON)
            val entry: ZipEntry? = zip.getEntry(depsPath)
            if (entry != null && !entry.isDirectory) {
                depsJsonByArch[arch] =
                    zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        }
        return validateJarEntries(entrySizes, depsJsonByArch)
    }

    /**
     * Validates a staged prebuilt helpers directory shaped as
     * `<root>/<arch>/<files>` (the `prebuiltWindowsHelpersDir` layout).
     */
    fun validatePrebuiltHelpersDir(root: File): List<String> {
        if (!root.isDirectory) {
            return listOf("prebuilt Windows helpers root is not a directory: $root")
        }
        val errors = mutableListOf<String>()
        for (arch in ARCHES) {
            val archDir = root.resolve(arch)
            if (!archDir.isDirectory) {
                errors += "missing prebuilt Windows helper arch directory: $arch"
                continue
            }
            val files =
                archDir.listFiles()?.filter { it.isFile }?.associate { it.name to it.length() }
                    .orEmpty()
            val depsText = archDir.resolve(DEPS_JSON).takeIf { it.isFile }?.readText()
            errors += validateArchFiles(arch, files, depsText, pathPrefix = "$arch/")
        }
        return errors
    }

    private fun validateArch(
        arch: String,
        entrySizes: Map<String, Long>,
        depsJson: String?,
    ): List<String> {
        val prefix = "$RESOURCE_ROOT/$arch/"
        val files =
            entrySizes
                .filterKeys { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
                .mapKeys { it.key.removePrefix(prefix) }
        return validateArchFiles(arch, files, depsJson, pathPrefix = prefix)
    }

    private fun validateArchFiles(
        arch: String,
        files: Map<String, Long>,
        depsJson: String?,
        pathPrefix: String,
    ): List<String> {
        val errors = mutableListOf<String>()
        for (base in REQUIRED_BASE_NAMES) {
            val size = files[base]
            when {
                size == null ->
                    errors += "missing Windows helper entry `$pathPrefix$base` (arch $arch)"
                size <= 0L ->
                    errors += "empty Windows helper entry `$pathPrefix$base` (arch $arch)"
            }
        }
        if (depsJson != null) {
            val requiredFromDeps =
                try {
                    runtimeAssetBaseNames(depsJson, arch)
                } catch (e: Exception) {
                    errors +=
                        "invalid $pathPrefix$DEPS_JSON for arch $arch: " +
                            (e.message ?: e::class.java.simpleName)
                    emptySet()
                }
            for (base in requiredFromDeps) {
                val size = files[base]
                when {
                    size == null ->
                        errors +=
                            "missing Windows helper companion `$pathPrefix$base` " +
                                "required by $DEPS_JSON (arch $arch)"
                    size <= 0L ->
                        errors +=
                            "empty Windows helper companion `$pathPrefix$base` " +
                                "required by $DEPS_JSON (arch $arch)"
                }
            }
        }
        return errors
    }

    /**
     * Collects basenames of runtime and native assets from a .NET deps.json document
     * for the Windows RID target matching [arch] (`x64` → `win-x64`, `arm64` → `win-arm64`).
     */
    @Suppress("UNCHECKED_CAST")
    fun runtimeAssetBaseNames(depsJson: String, arch: String = "x64"): Set<String> {
        val root = JsonSlurper().parseText(depsJson) as Map<String, Any?>
        val targets = root["targets"] as? Map<String, Any?> ?: return emptySet()
        val assetNames = linkedSetOf<String>()
        val targetKeys = targets.keys.toList()
        val ridToken =
            when (arch.lowercase()) {
                "x64",
                "amd64",
                "x86_64" -> "/win-x64"
                "arm64",
                "aarch64" -> "/win-arm64"
                else -> "/win-"
            }
        val preferred =
            targetKeys.filter { it.contains(ridToken, ignoreCase = true) }.ifEmpty {
                // Fall back to any win RID, then to all targets (framework-only deps).
                targetKeys.filter { it.contains("/win-", ignoreCase = true) }.ifEmpty { targetKeys }
            }
        for (targetKey in preferred) {
            val packages = targets[targetKey] as? Map<String, Any?> ?: continue
            for ((_, metaAny) in packages) {
                val meta = metaAny as? Map<String, Any?> ?: continue
                collectAssetBasenames(meta["runtime"] as? Map<String, Any?>, assetNames)
                collectAssetBasenames(meta["native"] as? Map<String, Any?>, assetNames)
            }
        }
        return assetNames
    }

    private fun collectAssetBasenames(assets: Map<String, Any?>?, into: MutableSet<String>) {
        if (assets == null) return
        for (path in assets.keys) {
            val base = path.substringAfterLast('/').substringAfterLast('\\')
            if (base.isNotEmpty() && !base.endsWith(".pdb", ignoreCase = true)) {
                into += base
            }
        }
    }
}
