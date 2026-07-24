package dev.sebastiano.spectre.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InjectClasspathStripTest {

    @Test
    fun `detects unix core build outputs`() {
        assertTrue(
            InjectClasspathStrip.isSpectreCoreClasspathEntry(
                "/Users/seb/src/spectre/core/build/libs/core-0.1.0-SNAPSHOT.jar"
            )
        )
        assertTrue(
            InjectClasspathStrip.isSpectreCoreClasspathEntry(
                "/Users/seb/src/spectre/core/build/classes/kotlin/main"
            )
        )
        assertTrue(
            InjectClasspathStrip.isSpectreCoreClasspathEntry(
                "/Users/seb/src/spectre/core/build/resources/main"
            )
        )
    }

    @Test
    fun `detects windows core build outputs with backslashes`() {
        assertTrue(
            InjectClasspathStrip.isSpectreCoreClasspathEntry(
                """C:\Users\rock3r\src\spectre\core\build\libs\core-0.1.0-SNAPSHOT.jar"""
            )
        )
        assertTrue(
            InjectClasspathStrip.isSpectreCoreClasspathEntry(
                """C:\Users\rock3r\src\spectre\core\build\classes\kotlin\main"""
            )
        )
    }

    @Test
    fun `detects windows paths with doubled backslashes from Gradle workers`() {
        // Real java.class.path shape observed on physical Windows (code points 92,92).
        val doubled =
            "C:\\\\Users\\\\rock3r\\\\src\\\\spectre\\\\core\\\\build\\\\libs\\\\core-0.1.0-SNAPSHOT.jar"
        assertTrue(InjectClasspathStrip.isSpectreCoreClasspathEntry(doubled))
        assertEquals(
            "C:/Users/rock3r/src/spectre/core/build/libs/core-0.1.0-SNAPSHOT.jar",
            InjectClasspathStrip.normalizeClasspathEntry(doubled),
        )
    }

    @Test
    fun `does not match kotlinx coroutines core jar`() {
        assertFalse(
            InjectClasspathStrip.isSpectreCoreClasspathEntry(
                "/home/seb/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/" +
                    "kotlinx-coroutines-core-jvm/1.10.2/xxx/kotlinx-coroutines-core-jvm-1.10.2.jar"
            )
        )
        assertFalse(
            InjectClasspathStrip.isSpectreCoreClasspathEntry(
                """C:\Users\rock3r\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlinx\""" +
                    """kotlinx-coroutines-core\1.10.2\xxx\kotlinx-coroutines-core-1.10.2.jar"""
            )
        )
    }

    @Test
    fun `core project jar name rules`() {
        assertTrue(InjectClasspathStrip.isCoreProjectJarName("core-0.1.0-SNAPSHOT.jar"))
        assertTrue(InjectClasspathStrip.isCoreProjectJarName("core.jar"))
        assertFalse(InjectClasspathStrip.isCoreProjectJarName("kotlinx-coroutines-core-1.10.2.jar"))
        assertFalse(
            InjectClasspathStrip.isCoreProjectJarName("agent-test-fixture-0.1.0-SNAPSHOT.jar")
        )
    }

    @Test
    fun `published spectre-core coordinates`() {
        assertTrue(InjectClasspathStrip.isSpectreCoreClasspathEntry("/repo/spectre-core-0.3.0.jar"))
        assertEquals(
            false,
            InjectClasspathStrip.isSpectreCoreClasspathEntry("/repo/spectre-agent-0.3.0.jar"),
        )
    }
}
