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
                val child =
                    ProcessBuilder(
                            javaExecutable(),
                            "-cp",
                            System.getProperty("java.class.path"),
                            AccessibilityHelperFixture::class.java.name,
                            "sleep",
                        )
                        .start()
                Files.writeString(Path.of(args[1]), child.pid().toString())
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
