package dev.sebastiano.spectre.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the JVM used to spawn the Compose agent-test fixture.
 *
 * Defaults to the attacher JVM (`java.home`). Override with
 * `-Ddev.sebastiano.spectre.agent.fixtureJavaHome=<path>` so the runtime matrix can exercise
 * mixed-runtime attach (e.g. JBR attacher → Temurin target) without rewriting each test.
 *
 * Used only from test code; not part of the public agent API.
 */
internal object FixtureJavaHome {
    const val PROPERTY: String = "dev.sebastiano.spectre.agent.fixtureJavaHome"

    fun resolve(
        javaHomeProp: String? = System.getProperty("java.home"),
        fixtureProp: String? = System.getProperty(PROPERTY),
    ): Path {
        val selected =
            fixtureProp?.takeIf { it.isNotBlank() }
                ?: javaHomeProp?.takeIf { it.isNotBlank() }
                ?: error("java.home is unset and $PROPERTY was not provided")
        val path = Paths.get(selected)
        require(Files.isDirectory(path)) {
            val source = if (fixtureProp?.isNotBlank() == true) PROPERTY else "java.home"
            "Fixture JAVA_HOME is not a directory: $path (from $source)"
        }
        return path
    }

    fun javaExecutable(home: Path = resolve()): Path = home.resolve("bin").resolve(javaFileName())

    fun javaFileName(): String =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            "java.exe"
        } else {
            "java"
        }
}
