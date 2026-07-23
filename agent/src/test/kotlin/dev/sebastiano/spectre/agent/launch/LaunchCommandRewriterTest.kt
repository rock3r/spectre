@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaunchCommandRewriterTest {

    @Test
    fun `isDirectJvmLaunch recognizes java basenames and absolute paths`() {
        assertTrue(LaunchCommandRewriter.isDirectJvmLaunch(listOf("java", "-version")))
        assertTrue(
            LaunchCommandRewriter.isDirectJvmLaunch(listOf("/usr/bin/java", "-jar", "app.jar"))
        )
        assertTrue(
            LaunchCommandRewriter.isDirectJvmLaunch(
                listOf("""C:\Program Files\Java\bin\java.exe""", "-cp", "a.jar", "Main")
            )
        )
        assertFalse(LaunchCommandRewriter.isDirectJvmLaunch(listOf("./gradlew", ":app:run")))
        assertFalse(LaunchCommandRewriter.isDirectJvmLaunch(listOf("/usr/bin/python3", "app.py")))
        assertFalse(LaunchCommandRewriter.isDirectJvmLaunch(emptyList()))
    }

    @Test
    fun `isGradleishLaunch detects gradlew gradle and hotRun`() {
        assertTrue(LaunchCommandRewriter.isGradleishLaunch(listOf("./gradlew", ":app:run")))
        assertTrue(LaunchCommandRewriter.isGradleishLaunch(listOf("gradlew.bat", "run")))
        assertTrue(LaunchCommandRewriter.isGradleishLaunch(listOf("/opt/gradle/bin/gradle", "run")))
        assertTrue(
            LaunchCommandRewriter.isGradleishLaunch(
                listOf("./gradlew", ":sample-desktop:hotRunJvm")
            )
        )
        assertTrue(
            LaunchCommandRewriter.isGradleishLaunch(listOf("java", "-jar", "wrapper.jar", "hotRun"))
        )
        assertFalse(
            LaunchCommandRewriter.isDirectJvmLaunch(listOf("./gradlew", ":app:run")).let {
                // gradle is not a direct JVM launch
                it
            }
        )
        assertFalse(LaunchCommandRewriter.isGradleishLaunch(listOf("java", "-jar", "app.jar")))
    }

    @Test
    fun `rewriteDirectJvmCommand injects EnableDynamicAgentLoading after java`() {
        val rewritten =
            LaunchCommandRewriter.rewriteDirectJvmCommand(
                listOf("java", "-cp", "app.jar", "Main"),
                extraJvmArgs = emptyList(),
                inject = true,
            )
        assertEquals(
            listOf(
                "java",
                LaunchCommandRewriter.DYNAMIC_AGENT_LOADING_FLAG,
                "-cp",
                "app.jar",
                "Main",
            ),
            rewritten,
        )
    }

    @Test
    fun `rewriteDirectJvmCommand does not double-inject the flag`() {
        val input = listOf("java", "-XX:+EnableDynamicAgentLoading", "-cp", "app.jar", "Main")
        assertEquals(input, LaunchCommandRewriter.rewriteDirectJvmCommand(input, inject = true))
    }

    @Test
    fun `rewriteDirectJvmCommand inserts extraJvmArgs after injected flag`() {
        val rewritten =
            LaunchCommandRewriter.rewriteDirectJvmCommand(
                listOf("java", "-jar", "app.jar"),
                extraJvmArgs = listOf("-Xmx512m", "-Dfoo=bar"),
                inject = true,
            )
        assertEquals(
            listOf(
                "java",
                LaunchCommandRewriter.DYNAMIC_AGENT_LOADING_FLAG,
                "-Xmx512m",
                "-Dfoo=bar",
                "-jar",
                "app.jar",
            ),
            rewritten,
        )
    }

    @Test
    fun `rewriteDirectJvmCommand leaves non-java commands unchanged`() {
        val input = listOf("./gradlew", ":app:run")
        assertEquals(
            input,
            LaunchCommandRewriter.rewriteDirectJvmCommand(
                input,
                extraJvmArgs = listOf("-Xmx1g"),
                inject = true,
            ),
        )
    }

    @Test
    fun `extractClasspath reads -cp and -classpath forms`() {
        assertEquals(
            "a.jar:b.jar",
            LaunchCommandRewriter.extractClasspath(listOf("java", "-cp", "a.jar:b.jar", "Main")),
        )
        assertEquals(
            "a.jar",
            LaunchCommandRewriter.extractClasspath(listOf("java", "-classpath", "a.jar", "Main")),
        )
        assertNull(LaunchCommandRewriter.extractClasspath(listOf("java", "-jar", "app.jar")))
        assertNull(LaunchCommandRewriter.extractClasspath(listOf("./gradlew", "run")))
        // Application args after -jar must not be read as launcher classpath.
        assertNull(
            LaunchCommandRewriter.extractClasspath(
                listOf("java", "-jar", "app.jar", "-cp", "user-value")
            )
        )
    }

    @Test
    fun `classpathContainsSpectreCore matches jar names and in-repo layout`() {
        assertTrue(
            LaunchCommandRewriter.classpathContainsSpectreCore(
                "/repo/spectre-core-0.1.0.jar:/other/lib.jar"
            )
        )
        assertTrue(
            LaunchCommandRewriter.classpathContainsSpectreCore(
                "/Users/seb/src/spectre/core/build/libs/core-0.1.0-SNAPSHOT.jar"
            )
        )
        assertFalse(
            LaunchCommandRewriter.classpathContainsSpectreCore("/only/agent-runtime.jar:/foo.jar")
        )
    }

    @Test
    fun `gradleLaunchWarning names the caveats`() {
        val warning = LaunchCommandRewriter.gradleLaunchWarning()
        assertTrue(warning.contains("Gradle-ish", ignoreCase = true))
        assertTrue(warning.contains("daemon", ignoreCase = true))
        assertTrue(warning.contains("EnableDynamicAgentLoading"))
        assertTrue(warning.contains("prod-like", ignoreCase = true))
    }
}
