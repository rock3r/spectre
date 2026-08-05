package dev.sebastiano.spectre.build

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Produces a target-filtered copy of the multi-platform CLI shadow jar for Construo/Roast
 * packaging. Keeps all non-native entries and only `native/<os>/…` helpers for one Roast OS.
 *
 * The unfiltered shadow jar remains the artifact used by `verifyCliShadowJar` and local
 * `java -jar` workflows; Maven recording modules stay multi-arch for Central.
 */
@CacheableTask
abstract class FilterCliShadowJarNatives : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputJar: RegularFileProperty

    @get:OutputFile abstract val outputJar: RegularFileProperty

    /**
     * OS native prefix to retain, e.g. `native/macos/`. Must match
     * [CliRoastNativePackagingContract.keepNativePrefixForRoastTarget] for the Roast target.
     */
    @get:Input abstract val keepNativePrefix: Property<String>

    @TaskAction
    fun filter() {
        filterJar(
            inputJar = inputJar.get().asFile,
            outputJar = outputJar.get().asFile,
            keepNativePrefix = keepNativePrefix.get(),
        )
    }

    companion object {
        /**
         * Pure filter used by the Gradle task and unit tests. Streams entries without loading the
         * whole archive; preserves STORED entries (with CRC/size) when the source used STORED.
         */
        fun filterJar(
            inputJar: java.io.File,
            outputJar: java.io.File,
            keepNativePrefix: String,
        ) {
            val keep =
                CliRoastNativePackagingContract.requireKnownKeepNativePrefix(keepNativePrefix)
            outputJar.parentFile?.mkdirs()
            JarFile(inputJar).use { source ->
                JarOutputStream(BufferedOutputStream(outputJar.outputStream())).use { dest ->
                    val entries = source.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (
                            !CliRoastNativePackagingContract.shouldIncludeJarEntry(
                                entry.name,
                                keep,
                            )
                        ) {
                            continue
                        }
                        val copy = JarEntry(entry.name)
                        copy.time = entry.time
                        copy.method = entry.method
                        if (entry.method == ZipEntry.STORED) {
                            // ZipOutputStream requires size == compressedSize for STORED. Repair
                            // incomplete source metadata rather than failing mid-filter.
                            val size = entry.size
                            val compressed =
                                if (entry.compressedSize >= 0 && entry.compressedSize == size) {
                                    entry.compressedSize
                                } else {
                                    size
                                }
                            copy.size = size
                            copy.compressedSize = compressed
                            copy.crc = entry.crc
                        }
                        dest.putNextEntry(copy)
                        if (!entry.isDirectory) {
                            BufferedInputStream(source.getInputStream(entry)).use { input ->
                                input.copyTo(dest)
                            }
                        }
                        dest.closeEntry()
                    }
                }
            }
        }
    }
}
