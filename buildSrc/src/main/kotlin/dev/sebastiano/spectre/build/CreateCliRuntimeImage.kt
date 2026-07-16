package dev.sebastiano.spectre.build

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/** Creates a compact host JRE for the CLI bundle with dynamic agent attachment support. */
abstract class CreateCliRuntimeImage
    @Inject
    constructor(
        private val execOperations: ExecOperations,
        private val fileSystemOperations: FileSystemOperations,
    ) : DefaultTask() {
        @get:InputFile abstract val jlinkExecutable: RegularFileProperty

        @get:OutputDirectory abstract val runtimeImage: DirectoryProperty

        @TaskAction
        fun create() {
            val output = runtimeImage.get().asFile
            fileSystemOperations.delete { delete(output) }
            execOperations.exec {
                commandLine(
                    jlinkExecutable.get().asFile.path,
                    "--add-modules",
                    "java.se,jdk.attach,jdk.unsupported",
                    "--output",
                    output.path,
                    "--strip-debug",
                    "--no-man-pages",
                    "--no-header-files",
                    "--compress=zip-6",
                )
            }
        }
    }
