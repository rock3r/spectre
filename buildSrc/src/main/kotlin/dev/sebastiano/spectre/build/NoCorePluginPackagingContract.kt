package dev.sebastiano.spectre.build

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes

/**
 * Packaging contract for the **no-core** Spectre sample IntelliJ plugin zip (#353 / #375).
 *
 * The default sample plugin ships `spectre-core` so in-process / instrumented attach works.
 * Stock inject QA needs a Jewel-tagged tool window **without** core on the plugin classpath so
 * attach bootstrap takes the nested inject-runtime path.
 *
 * Rules:
 * - No `core-*.jar` / `spectre-core*.jar` under `lib/`
 * - Plugin jar must not retain `RunSpectreAction` / `SpectreAutoRunStartupActivity` (core deps)
 * - Must retain tool-window factory + content classes and a tool-window-only `plugin.xml`
 * - Content class bytes must embed the proving `ide.counter.*` / `ide.popup.*` tag strings
 */
object NoCorePluginPackagingContract {
    const val PLUGIN_ROOT_DIR: String = "sample-intellij-plugin"
    const val TOOL_WINDOW_ID: String = "Spectre Sample"
    const val TOOL_WINDOW_FACTORY_FQN: String =
        "dev.sebastiano.spectre.intellij.SpectreSampleToolWindowFactory"

    val REQUIRED_PROVING_TAGS: List<String> =
        listOf(
            "ide.counter.button",
            "ide.counter.text",
            "ide.popup.toggleButton",
            "ide.popup.body",
            "ide.popup.text",
            "ide.popup.dismissButton",
        )

    private val STRIP_CLASS_PREFIXES: List<String> =
        listOf(
            "dev/sebastiano/spectre/intellij/RunSpectreAction",
            "dev/sebastiano/spectre/intellij/SpectreAutoRunStartupActivity",
        )

    private val REQUIRED_PLUGIN_CLASS_SUFFIXES: List<String> =
        listOf(
            "dev/sebastiano/spectre/intellij/SpectreSampleToolWindowFactory.class",
            "dev/sebastiano/spectre/intellij/SpectreSampleToolWindowContentKt.class",
        )

    fun isForbiddenLibJar(fileName: String): Boolean {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        if (base == "core.jar") return true
        if (base.startsWith("core-") && base.endsWith(".jar")) return true
        if (base == "spectre-core.jar") return true
        if (base.startsWith("spectre-core-") && base.endsWith(".jar")) return true
        return false
    }

    fun shouldStripPluginClass(entryName: String): Boolean {
        val n = entryName.replace('\\', '/')
        return STRIP_CLASS_PREFIXES.any { prefix -> n == "$prefix.class" || n.startsWith("$prefix$") }
    }

    /**
     * Validates an open no-core plugin distribution zip.
     *
     * @return human-readable errors (empty when the contract is satisfied)
     */
    fun validateNoCorePluginZip(zip: ZipFile): List<String> {
        val errors = mutableListOf<String>()
        val entries = zip.entries().asSequence().map { it.name.replace('\\', '/') }.toList()
        val libJars =
            entries.filter { it.matches(Regex(".*/lib/[^/]+\\.jar$")) }.map { it.substringAfterLast('/') }

        if (libJars.none { it.startsWith("sample-intellij-plugin-") && !it.contains("searchableOptions") }) {
            errors += "missing sample-intellij-plugin-*.jar under lib/"
        }

        for (jar in libJars) {
            if (isForbiddenLibJar(jar)) {
                errors += "forbidden spectre-core jar present: $jar"
            }
        }

        val pluginJarEntry =
            entries.firstOrNull {
                it.matches(Regex(".*/lib/sample-intellij-plugin-[^/]+\\.jar$")) &&
                    !it.contains("searchableOptions")
            }
        if (pluginJarEntry == null) {
            errors += "could not locate primary sample-intellij-plugin jar entry"
            return errors
        }

        val pluginJarBytes = zip.getInputStream(zip.getEntry(pluginJarEntry)).use { it.readBytes() }
        errors += validatePluginJarBytes(pluginJarBytes)
        return errors
    }

    fun validatePluginJarBytes(pluginJarBytes: ByteArray): List<String> {
        val errors = mutableListOf<String>()
        val entryNames = linkedSetOf<String>()
        var pluginXml: String? = null
        var contentClassBytes: ByteArray? = null

        JarInputStream(ByteArrayInputStream(pluginJarBytes)).use { jis ->
            while (true) {
                val entry = jis.nextJarEntry ?: break
                val name = entry.name.replace('\\', '/')
                entryNames += name
                if (name == "META-INF/plugin.xml") {
                    pluginXml = jis.readBytes().toString(Charsets.UTF_8)
                } else if (name.endsWith("SpectreSampleToolWindowContentKt.class")) {
                    contentClassBytes = jis.readBytes()
                } else {
                    jis.skip(Long.MAX_VALUE)
                }
                jis.closeEntry()
            }
        }

        for (name in entryNames) {
            if (shouldStripPluginClass(name)) {
                errors += "core-dependent class retained: $name"
            }
        }

        for (required in REQUIRED_PLUGIN_CLASS_SUFFIXES) {
            if (entryNames.none { it.endsWith(required) || it == required }) {
                errors += "missing required class entry: $required"
            }
        }

        val xml = pluginXml
        if (xml == null) {
            errors += "missing META-INF/plugin.xml"
        } else {
            if (!xml.contains(TOOL_WINDOW_ID)) {
                errors += "plugin.xml missing tool window id '$TOOL_WINDOW_ID'"
            }
            if (!xml.contains(TOOL_WINDOW_FACTORY_FQN)) {
                errors += "plugin.xml missing factory $TOOL_WINDOW_FACTORY_FQN"
            }
            // Match class references only (not prose in XML comments).
            if (Regex("""class\s*=\s*"[^"]*RunSpectreAction"""").containsMatchIn(xml) ||
                Regex("""implementation\s*=\s*"[^"]*SpectreAutoRunStartupActivity"""").containsMatchIn(xml)
            ) {
                errors += "plugin.xml still references core-dependent extensions/actions"
            }
            if (!xml.contains("com.intellij.modules.compose")) {
                errors += "plugin.xml must depend on com.intellij.modules.compose"
            }
        }

        val content = contentClassBytes
        if (content == null) {
            errors += "missing SpectreSampleToolWindowContentKt.class bytes for tag scan"
        } else {
            val haystack = content.toString(Charsets.ISO_8859_1)
            for (tag in REQUIRED_PROVING_TAGS) {
                if (tag !in haystack) {
                    errors += "proving tag '$tag' not found in tool window content class bytes"
                }
            }
        }

        return errors
    }

    /**
     * Transforms a full instrumented sample-plugin zip into a no-core inject-QA zip:
     * strips forbidden core jars, rewrites the plugin jar (drop core-dependent classes,
     * install [noCorePluginXml]), and drops unrelated transitive lib jars so the classpath
     * is tags-only against the IDE's bundled Compose/Jewel modules.
     */
    fun transformInstrumentedZipToNoCore(
        instrumentedZip: java.io.File,
        outputZip: java.io.File,
        noCorePluginXml: String,
    ) {
        val staging = createTempDirectory(prefix = "spectre-no-core-plugin-")
        try {
            ZipFile(instrumentedZip).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val name = entry.name.replace('\\', '/')
                    val relative = name.removePrefix("$PLUGIN_ROOT_DIR/").removePrefix("/")
                    if (!relative.startsWith("lib/")) return@forEach
                    val fileName = relative.substringAfterLast('/')
                    if (isForbiddenLibJar(fileName)) return@forEach
                    // Keep only the primary plugin jar — no searchableOptions, no core transitives.
                    if (!fileName.startsWith("sample-intellij-plugin-") ||
                        fileName.contains("searchableOptions")
                    ) {
                        return@forEach
                    }
                    val target = staging.resolve(relative)
                    target.parent.toFile().mkdirs()
                    zip.getInputStream(entry).use { input -> target.writeBytes(input.readBytes()) }
                }
            }

            val pluginJars =
                staging
                    .toFile()
                    .resolve("lib")
                    .listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".jar") }
                    .orEmpty()
            check(pluginJars.size == 1) {
                "expected exactly one plugin jar after strip, found: ${pluginJars.map { it.name }}"
            }
            val pluginJar = pluginJars.single()
            val rewritten = rewritePluginJar(pluginJar.readBytes(), noCorePluginXml)
            pluginJar.writeBytes(rewritten)

            outputZip.parentFile?.mkdirs()
            if (outputZip.exists()) {
                check(outputZip.delete()) { "could not delete existing ${outputZip.absolutePath}" }
            }
            ZipOutputStream(outputZip.outputStream()).use { zos ->
                staging.toFile().walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative =
                        staging.relativize(file.toPath()).toString().replace('\\', '/')
                    val entryName = "$PLUGIN_ROOT_DIR/$relative"
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    fun rewritePluginJar(originalJarBytes: ByteArray, noCorePluginXml: String): ByteArray {
        val baos = ByteArrayOutputStream()
        JarOutputStream(baos).use { jos ->
            JarInputStream(ByteArrayInputStream(originalJarBytes)).use { jis ->
                while (true) {
                    val entry = jis.nextJarEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    val bytes = jis.readBytes()
                    jis.closeEntry()
                    if (shouldStripPluginClass(name)) continue
                    if (name == "META-INF/plugin.xml") {
                        jos.putNextEntry(JarEntry("META-INF/plugin.xml"))
                        jos.write(noCorePluginXml.toByteArray(Charsets.UTF_8))
                        jos.closeEntry()
                        continue
                    }
                    jos.putNextEntry(JarEntry(name))
                    jos.write(bytes)
                    jos.closeEntry()
                }
            }
        }
        return baos.toByteArray()
    }
}
