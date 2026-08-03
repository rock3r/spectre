package dev.sebastiano.spectre.build

import java.io.ByteArrayOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit coverage for the #353 / #375 no-core sample plugin packaging contract used by
 * `:sample-intellij-plugin:buildNoCorePlugin` and `verifyNoCorePluginZip`.
 *
 * Proves the real [NoCorePluginPackagingContract] predicates and zip validator — not a
 * re-implementation — reject core jars / core-dependent classes and accept a minimal
 * tags-only plugin zip shape.
 */
class NoCorePluginPackagingContractTest {

    @Test
    fun `core project jars are classified as forbidden`() {
        assertTrue(NoCorePluginPackagingContract.isForbiddenLibJar("core-0.1.0-SNAPSHOT.jar"))
        assertTrue(NoCorePluginPackagingContract.isForbiddenLibJar("spectre-core-0.1.0-SNAPSHOT.jar"))
        assertTrue(NoCorePluginPackagingContract.isForbiddenLibJar("spectre-core.jar"))
        assertTrue(NoCorePluginPackagingContract.isForbiddenLibJar("core.jar"))
    }

    @Test
    fun `plugin jar itself is not a forbidden lib jar`() {
        assertFalse(
            NoCorePluginPackagingContract.isForbiddenLibJar(
                "sample-intellij-plugin-0.0.0-DEV.jar"
            )
        )
        assertFalse(
            NoCorePluginPackagingContract.isForbiddenLibJar(
                "sample-intellij-plugin-0.1.0-SNAPSHOT-searchableOptions.jar"
            )
        )
    }

    @Test
    fun `core-dependent class entries are stripped`() {
        assertTrue(
            NoCorePluginPackagingContract.shouldStripPluginClass(
                "dev/sebastiano/spectre/intellij/RunSpectreAction.class"
            )
        )
        assertTrue(
            NoCorePluginPackagingContract.shouldStripPluginClass(
                "dev/sebastiano/spectre/intellij/RunSpectreAction\$Companion.class"
            )
        )
        assertTrue(
            NoCorePluginPackagingContract.shouldStripPluginClass(
                "dev/sebastiano/spectre/intellij/SpectreAutoRunStartupActivity.class"
            )
        )
        assertTrue(
            NoCorePluginPackagingContract.shouldStripPluginClass(
                "dev/sebastiano/spectre/intellij/SpectreAutoRunStartupActivity\$execute\$1.class"
            )
        )
    }

    @Test
    fun `tool window class entries are kept`() {
        assertFalse(
            NoCorePluginPackagingContract.shouldStripPluginClass(
                "dev/sebastiano/spectre/intellij/SpectreSampleToolWindowFactory.class"
            )
        )
        assertFalse(
            NoCorePluginPackagingContract.shouldStripPluginClass(
                "dev/sebastiano/spectre/intellij/SpectreSampleToolWindowContentKt.class"
            )
        )
        assertFalse(
            NoCorePluginPackagingContract.shouldStripPluginClass("META-INF/plugin.xml")
        )
    }

    @Test
    fun `zip with core jar fails validation`() {
        val zip =
            writeSyntheticPluginZip(
                libJars =
                    mapOf(
                        "core-0.1.0-SNAPSHOT.jar" to byteArrayOf(1),
                        "sample-intellij-plugin-0.0.0-DEV.jar" to
                            pluginJarBytes(
                                classes =
                                    listOf(
                                        "dev/sebastiano/spectre/intellij/SpectreSampleToolWindowFactory.class",
                                        "dev/sebastiano/spectre/intellij/SpectreSampleToolWindowContentKt.class",
                                    ),
                                pluginXml = VALID_NO_CORE_PLUGIN_XML,
                                classPayloadWithTags = true,
                            ),
                    )
            )
        try {
            ZipFile(zip.toFile()).use { zf ->
                val errors = NoCorePluginPackagingContract.validateNoCorePluginZip(zf)
                assertTrue(errors.isNotEmpty(), "expected core jar to fail")
                assertTrue(
                    errors.any { it.contains("core-0.1.0-SNAPSHOT.jar") },
                    "errors=$errors",
                )
            }
        } finally {
            zip.deleteIfExists()
        }
    }

    @Test
    fun `zip retaining RunSpectreAction fails validation`() {
        val zip =
            writeSyntheticPluginZip(
                libJars =
                    mapOf(
                        "sample-intellij-plugin-0.0.0-DEV.jar" to
                            pluginJarBytes(
                                classes =
                                    listOf(
                                        "dev/sebastiano/spectre/intellij/SpectreSampleToolWindowFactory.class",
                                        "dev/sebastiano/spectre/intellij/SpectreSampleToolWindowContentKt.class",
                                        "dev/sebastiano/spectre/intellij/RunSpectreAction.class",
                                    ),
                                pluginXml = VALID_NO_CORE_PLUGIN_XML,
                                classPayloadWithTags = true,
                            ),
                    )
            )
        try {
            ZipFile(zip.toFile()).use { zf ->
                val errors = NoCorePluginPackagingContract.validateNoCorePluginZip(zf)
                assertTrue(errors.isNotEmpty(), "expected RunSpectreAction retention to fail")
                assertTrue(
                    errors.any { it.contains("RunSpectreAction") },
                    "errors=$errors",
                )
            }
        } finally {
            zip.deleteIfExists()
        }
    }

    @Test
    fun `minimal tags-only zip passes validation`() {
        val zip =
            writeSyntheticPluginZip(
                libJars =
                    mapOf(
                        "sample-intellij-plugin-0.0.0-DEV.jar" to
                            pluginJarBytes(
                                classes =
                                    listOf(
                                        "dev/sebastiano/spectre/intellij/SpectreSampleToolWindowFactory.class",
                                        "dev/sebastiano/spectre/intellij/SpectreSampleToolWindowContentKt.class",
                                    ),
                                pluginXml = VALID_NO_CORE_PLUGIN_XML,
                                classPayloadWithTags = true,
                            ),
                    )
            )
        try {
            ZipFile(zip.toFile()).use { zf ->
                val errors = NoCorePluginPackagingContract.validateNoCorePluginZip(zf)
                assertTrue(errors.isEmpty(), "expected valid no-core zip; errors=$errors")
            }
        } finally {
            zip.deleteIfExists()
        }
    }

    private fun writeSyntheticPluginZip(libJars: Map<String, ByteArray>): java.nio.file.Path {
        val path = createTempFile(prefix = "no-core-plugin-", suffix = ".zip")
        ZipOutputStream(path.outputStream()).use { zos ->
            for ((name, bytes) in libJars) {
                val entryName = "sample-intellij-plugin/lib/$name"
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return path
    }

    private fun pluginJarBytes(
        classes: List<String>,
        pluginXml: String,
        classPayloadWithTags: Boolean,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        JarOutputStream(baos).use { jos ->
            jos.putNextEntry(JarEntry("META-INF/plugin.xml"))
            jos.write(pluginXml.toByteArray(Charsets.UTF_8))
            jos.closeEntry()
            for (className in classes) {
                jos.putNextEntry(JarEntry(className))
                // Embed proving tag UTF-8 constants so the contract can scan for them.
                val payload =
                    if (classPayloadWithTags && className.contains("ToolWindowContent")) {
                        buildString {
                                append("synthetic-class-bytes ")
                                for (tag in NoCorePluginPackagingContract.REQUIRED_PROVING_TAGS) {
                                    append(tag)
                                    append(' ')
                                }
                            }
                            .toByteArray(Charsets.UTF_8)
                    } else {
                        byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
                    }
                jos.write(payload)
                jos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private companion object {
        val VALID_NO_CORE_PLUGIN_XML =
            """
            <idea-plugin>
              <id>dev.sebastiano.spectre.sample</id>
              <name>Spectre IDE Sample</name>
              <depends>com.intellij.modules.platform</depends>
              <depends>com.intellij.modules.compose</depends>
              <extensions defaultExtensionNs="com.intellij">
                <toolWindow id="Spectre Sample"
                            anchor="right"
                            factoryClass="dev.sebastiano.spectre.intellij.SpectreSampleToolWindowFactory"/>
              </extensions>
            </idea-plugin>
            """
                .trimIndent()
    }
}
