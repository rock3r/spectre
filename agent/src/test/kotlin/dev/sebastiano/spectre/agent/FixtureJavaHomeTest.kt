package dev.sebastiano.spectre.agent

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class FixtureJavaHomeTest {

    @Test
    fun `defaults to java-home when fixture property is unset`() {
        val home = Files.createTempDirectory("fixture-default-home")
        try {
            val resolved =
                FixtureJavaHome.resolve(javaHomeProp = home.toString(), fixtureProp = null)
            assertEquals(home, resolved)
        } finally {
            Files.deleteIfExists(home)
        }
    }

    @Test
    fun `prefers fixture property over java-home`() {
        val attacherHome = Files.createTempDirectory("attacher-home")
        val fixtureHome = Files.createTempDirectory("fixture-home")
        try {
            val resolved =
                FixtureJavaHome.resolve(
                    javaHomeProp = attacherHome.toString(),
                    fixtureProp = fixtureHome.toString(),
                )
            assertEquals(fixtureHome, resolved)
        } finally {
            Files.deleteIfExists(attacherHome)
            Files.deleteIfExists(fixtureHome)
        }
    }

    @Test
    fun `blank fixture property falls back to java-home`() {
        val home = Files.createTempDirectory("fixture-blank-home")
        try {
            val resolved =
                FixtureJavaHome.resolve(javaHomeProp = home.toString(), fixtureProp = "   ")
            assertEquals(home, resolved)
        } finally {
            Files.deleteIfExists(home)
        }
    }

    @Test
    fun `rejects missing java homes`() {
        assertFailsWith<IllegalStateException> {
            FixtureJavaHome.resolve(javaHomeProp = null, fixtureProp = null)
        }
    }

    @Test
    fun `rejects non-directory fixture home`() {
        val missing = Files.createTempDirectory("fixture-missing").resolve("nope")
        assertFailsWith<IllegalArgumentException> {
            FixtureJavaHome.resolve(
                javaHomeProp = Files.createTempDirectory("attacher").toString(),
                fixtureProp = missing.toString(),
            )
        }
    }

    @Test
    fun `javaExecutable points at bin-java under the resolved home`() {
        val home = Files.createTempDirectory("fixture-java-bin")
        try {
            val exe = FixtureJavaHome.javaExecutable(home)
            assertEquals(home.resolve("bin").resolve(FixtureJavaHome.javaFileName()), exe)
            assertTrue(exe.fileName.toString() == FixtureJavaHome.javaFileName())
        } finally {
            Files.deleteIfExists(home)
        }
    }

    @Test
    fun `reads live system property when Gradle forwards SPECTRE_FIXTURE_JAVA_HOME`() {
        // When the matrix sets SPECTRE_FIXTURE_JAVA_HOME / -Pspectre.agent.fixtureJavaHome,
        // :agent:test forwards it as -Ddev.sebastiano.spectre.agent.fixtureJavaHome into this
        // forked JVM. Assert the live property is honoured when present; when absent (default
        // unit-test runs), resolve() still falls back to java.home.
        val live = System.getProperty(FixtureJavaHome.PROPERTY)
        val resolved = FixtureJavaHome.resolve()
        if (live.isNullOrBlank()) {
            assertEquals(
                Paths.get(System.getProperty("java.home")!!).normalize(),
                resolved.normalize(),
            )
        } else {
            assertEquals(Paths.get(live).normalize(), resolved.normalize())
            assertTrue(Files.isDirectory(resolved))
        }
    }

    @Test
    fun `Gradle forwards SPECTRE_FIXTURE_JAVA_HOME env into the fixture system property`() {
        // Matrix mixed-attach cells set SPECTRE_FIXTURE_JAVA_HOME. The forked test JVM inherits
        // the env var, and agent/build.gradle.kts must also inject the matching -D property.
        // Skip when the env is unset so ordinary `./gradlew check` stays green.
        val env = System.getenv("SPECTRE_FIXTURE_JAVA_HOME")?.takeIf { it.isNotBlank() }
        assumeTrue(env != null) {
            "SPECTRE_FIXTURE_JAVA_HOME not set; skip matrix-only forward check"
        }
        val prop = System.getProperty(FixtureJavaHome.PROPERTY)
        assertTrue(!prop.isNullOrBlank(), "expected ${FixtureJavaHome.PROPERTY} to be forwarded")
        assertEquals(Paths.get(env).normalize(), Paths.get(prop).normalize())
    }
}
