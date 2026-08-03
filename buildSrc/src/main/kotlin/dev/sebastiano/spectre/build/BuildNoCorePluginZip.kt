package dev.sebastiano.spectre.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Transforms the instrumented sample-plugin zip into the #353 no-core inject-QA distribution.
 */
abstract class BuildNoCorePluginZip : DefaultTask() {
    @get:InputFile abstract val instrumentedPluginZip: RegularFileProperty

    @get:InputFile abstract val noCorePluginXml: RegularFileProperty

    @get:OutputFile abstract val outputZip: RegularFileProperty

    @TaskAction
    fun build() {
        NoCorePluginPackagingContract.transformInstrumentedZipToNoCore(
            instrumentedZip = instrumentedPluginZip.get().asFile,
            outputZip = outputZip.get().asFile,
            noCorePluginXml = noCorePluginXml.get().asFile.readText(),
        )
    }
}
