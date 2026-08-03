package dev.sebastiano.spectre.build

import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

/** Asserts the no-core sample plugin zip satisfies [NoCorePluginPackagingContract]. */
abstract class VerifyNoCorePluginZip : DefaultTask() {
    @get:InputFile abstract val noCorePluginZip: RegularFileProperty

    @TaskAction
    fun verify() {
        val zipFile = noCorePluginZip.get().asFile
        check(zipFile.isFile && zipFile.length() > 0L) {
            "no-core plugin zip missing or empty: ${zipFile.absolutePath}"
        }
        ZipFile(zipFile).use { zip ->
            val errors = NoCorePluginPackagingContract.validateNoCorePluginZip(zip)
            check(errors.isEmpty()) {
                "no-core plugin zip failed packaging contract:\n" +
                    errors.joinToString("\n") { "  - $it" }
            }
        }
    }
}
