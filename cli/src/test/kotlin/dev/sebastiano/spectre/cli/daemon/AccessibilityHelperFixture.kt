package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Cross-platform subprocess fixture: it uses the same JVM and needs no shell or POSIX permissions.
 */
object AccessibilityHelperFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.first()) {
            "sleep" -> TimeUnit.SECONDS.sleep(30)
            "descendant" -> {
                ProcessBuilder(
                        javaExecutable(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        AccessibilityHelperFixture::class.java.name,
                        "ready",
                        args[1],
                    )
                    .start()
                System.`in`.readAllBytes()
                Files.createFile(Path.of(args[1]).resolve("request-received"))
                TimeUnit.SECONDS.sleep(30)
            }
            "ready" -> {
                val directory = Path.of(args[1])
                Files.writeString(
                    directory.resolve("child.pid"),
                    ProcessHandle.current().pid().toString(),
                )
                Files.createFile(directory.resolve("child-ready"))
                TimeUnit.SECONDS.sleep(30)
            }
            else -> error("Unknown fixture mode ${args.first()}")
        }
    }

    private fun javaExecutable(): String =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
            )
            .toString()
}
