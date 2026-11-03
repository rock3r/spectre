package dev.sebastiano.spectre.cli.bootstrap

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** The bootstrap's child command on JDK 21-24, which run the CLI without the Leyden cache. */
class SpectreBootstrapPreLeydenJdkTest {

    private val javaExecutable = Path.of("/jdk", "bin", "java").toString()
    private val jarFile = Path.of("/opt", "spectre", "cli.jar")

    @Test
    fun `child command on a JDK without the Leyden cache carries no cache-only flags`() {
        // JDK 21-24 run the CLI uncached. The frontend contract exists only to keep the
        // AOT archive layout stable, and -XX:-UseCompactObjectHeaders is unknown on 21
        // and experimental-locked on 24: the child JVM would die before main.
        for (feature in listOf(21, 24)) {
            val plan = AotCache.Plan(AotCache.Mode.UNAVAILABLE, null, null, emptyList())

            val command =
                SpectreBootstrap.childCommand(
                    javaExecutable,
                    plan,
                    jarFile,
                    arrayOf("mcp"),
                    feature,
                    listOf("-Xmx1g"),
                    listOf("-Dexample=1"),
                )

            val expectedNativeAccess =
                if (feature >= 24) listOf("--enable-native-access=ALL-UNNAMED") else emptyList()
            assertEquals(
                listOf(javaExecutable, "-Xmx1g", "-Dexample=1") +
                    expectedNativeAccess +
                    listOf(
                        "-cp",
                        jarFile.toString(),
                        "dev.sebastiano.spectre.cli.SpectreCliKt",
                        "mcp",
                    ),
                command,
                "JDK $feature",
            )
        }
    }
}
