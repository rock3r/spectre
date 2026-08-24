package dev.sebastiano.spectre.core

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class ReleasePublicationContractTest {

    @Test
    fun `release publishes both input coordinator artifacts`() {
        val workflow = root.resolve(".github/workflows/release.yml").readText()

        assertTrue(workflow.contains(":input-coordinator:publishToMavenCentral"))
        assertTrue(workflow.contains(":input-coordinator-server:publishToMavenCentral"))
    }

    private companion object {
        val root: Path = Path.of("..").toAbsolutePath().normalize()
    }
}
