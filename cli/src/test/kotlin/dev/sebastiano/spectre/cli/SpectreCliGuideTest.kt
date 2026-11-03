package dev.sebastiano.spectre.cli

import dev.sebastiano.spectre.cli.daemon.DaemonErrorCode
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import dev.sebastiano.spectre.cli.guide.GUIDE_TOPICS
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SpectreCliGuideTest {
    @Test
    fun `guide without a topic prints the CLI workflow and every topic with its summary`() {
        val result = runGuide("guide")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("spectre ps"), result.output)
        assertTrue(result.output.contains("spectre attach <pid>"), result.output)
        assertTrue(result.output.contains("spectre guide --search"), result.output)
        GUIDE_TOPICS.forEach { topic ->
            assertTrue(result.output.contains(topic.name), "missing topic ${topic.name}")
            assertTrue(result.output.contains(topic.summary), "missing summary of ${topic.name}")
        }
    }

    @Test
    fun `every user guide page is bundled as a guide topic`() {
        val guidePages =
            Files.list(GUIDE_DOCS).use { paths ->
                paths
                    .filter { it.extension == "md" && it.nameWithoutExtension != "index" }
                    .map { it.nameWithoutExtension }
                    .toList()
                    .toSet()
            }

        assertEquals(guidePages, GUIDE_TOPICS.map { it.name }.toSet())
        GUIDE_TOPICS.forEach { topic ->
            assertNotNull(
                javaClass.classLoader.getResource("spectre-guide/${topic.name}.md"),
                "spectre-guide/${topic.name}.md is not bundled",
            )
        }
    }

    @Test
    fun `guide topic prints the bundled page without alias comments`() {
        val result = runGuide("guide", "traps")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.startsWith("# Traps and gotchas"), result.output)
        assertTrue(result.output.contains("--no-daemon"))
        assertFalse(result.output.contains("<!--"))
    }

    @Test
    fun `guide gotchas is another name for the traps topic`() {
        assertEquals(runGuide("guide", "traps").output, runGuide("guide", "gotchas").output)
    }

    @Test
    fun `guide topic prints JSON with the topic name and text`() {
        val result = runGuide("guide", "cli", "--json")

        val json = Json.parseToJsonElement(result.output).jsonObject
        assertEquals(1, json.getValue("version").jsonPrimitive.int)
        assertEquals("cli", json.getValue("topic").jsonPrimitive.content)
        assertTrue(json.getValue("text").jsonPrimitive.content.startsWith("# CLI"))
    }

    @Test
    fun `guide search prints the best sections with breadcrumbs`() {
        val result = runGuide("guide", "--search", "xvfb-run hangs after tests")

        assertEquals(0, result.exitCode)
        val firstHeader = result.output.lineSequence().first()
        assertTrue(firstHeader.startsWith("== traps › "), result.output)
        assertTrue(firstHeader.endsWith("(spectre guide traps)"), result.output)
        assertTrue(result.output.contains("--no-daemon"), result.output)
    }

    @Test
    fun `guide -s is the short form of --search and joins the remaining words`() {
        assertEquals(
            runGuide("guide", "--search", "xvfb-run hangs").output,
            runGuide("guide", "-s", "xvfb-run", "hangs").output,
        )
    }

    @Test
    fun `guide search finds the typing focus trap`() {
        val result = runGuide("guide", "-s", "type text does not reach the field", "--json")

        val results = Json.parseToJsonElement(result.output).jsonObject.getValue("results")
        val topics = results.jsonArray.map { it.jsonObject.getValue("topic").jsonPrimitive.content }
        assertTrue("traps" in topics, result.output)
    }

    @Test
    fun `guide search finds the eager scroll bounds trap`() {
        val result = runGuide("guide", "-s", "cannot click in-window bounds are empty", "--json")

        val first =
            Json.parseToJsonElement(result.output)
                .jsonObject
                .getValue("results")
                .jsonArray
                .first()
                .jsonObject
        assertEquals("traps", first.getValue("topic").jsonPrimitive.content, result.output)
    }

    @Test
    fun `guide search finds the capture window title trap`() {
        // A query that starts with "capture" prefers the capture topic by design, so describe
        // the symptom without naming a topic first.
        val result = runGuide("guide", "-s", "blank window title fails", "--json")

        val first =
            Json.parseToJsonElement(result.output)
                .jsonObject
                .getValue("results")
                .jsonArray
                .first()
                .jsonObject
        assertEquals("traps", first.getValue("topic").jsonPrimitive.content, result.output)
    }

    @Test
    fun `guide search JSON is versioned and stays within the byte budget`() {
        val result =
            runGuide("guide", "--search", "screenshot window capture", "-n", "10", "--json")

        assertEquals(0, result.exitCode)
        val json = Json.parseToJsonElement(result.output).jsonObject
        assertEquals(1, json.getValue("version").jsonPrimitive.int)
        assertEquals("screenshot window capture", json.getValue("query").jsonPrimitive.content)
        val results = json.getValue("results").jsonArray
        assertTrue(results.isNotEmpty())
        val first = results.first().jsonObject
        assertTrue(first.getValue("breadcrumb").jsonPrimitive.content.contains(" › "))
        assertTrue(first.getValue("path").jsonArray.isNotEmpty())
        val totalBytes = results.sumOf {
            it.jsonObject.getValue("text").jsonPrimitive.content.toByteArray().size
        }
        assertTrue(totalBytes <= 4_000, "search printed $totalBytes bytes")
    }

    @Test
    fun `guide search without matches says so and lists topics`() {
        val result = runGuide("guide", "--search", "zzqqxx")

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("No guide section matches"), result.output)
        assertTrue(result.output.contains("traps"), result.output)
    }

    @Test
    fun `guide search without words is a usage error`() {
        val result = runGuide("guide", "--search", "  ")

        assertEquals(2, result.exitCode)
        assertTrue(result.errorOutput.contains("spectre guide --search"), result.errorOutput)
    }

    @Test
    fun `unknown guide topic is a usage error that suggests search`() {
        val result = runGuide("guide", "no-such-topic")

        assertEquals(2, result.exitCode)
        assertTrue(result.errorOutput.contains("no-such-topic"), result.errorOutput)
        assertTrue(
            result.errorOutput.contains("spectre guide --search \"no-such-topic\""),
            result.errorOutput,
        )
    }

    @Test
    fun `root help ends with the guide footer`() {
        val result = runGuide("--help")

        assertEquals(0, result.exitCode)
        assertTrue(
            result.output.contains(
                "New to spectre? Run \"spectre guide\" or \"spectre guide --search <topic>\"."
            ),
            result.output,
        )
        assertTrue(result.output.contains("guide"), result.output)
    }

    @Test
    fun `usage errors point to the guide`() {
        val result = runGuide("unknown-command")

        assertEquals(2, result.exitCode)
        assertTrue(result.errorOutput.contains("spectre guide"), result.errorOutput)
    }

    @Test
    fun `usage errors without an unknown command end with the guide hint`() {
        val result = runGuide("guide", "-n", "0")

        assertEquals(2, result.exitCode)
        assertTrue(
            result.errorOutput
                .trimEnd()
                .endsWith(
                    "Run \"spectre guide\" for the CLI workflow, or " +
                        "\"spectre guide --search <words>\" to look up an error."
                ),
            result.errorOutput,
        )
    }

    @Test
    fun `JSON usage errors stay a single JSON object`() {
        val result = runGuide("frobnicate", "--json")

        assertEquals(2, result.exitCode)
        Json.parseToJsonElement(result.errorOutput)
    }

    @Test
    fun `daemon errors point to the matching guide topic`() {
        val errorOutput = StringBuilder()
        val cli =
            SpectreCli(
                request = {
                    DaemonResponse.Error(DaemonErrorCode.AttachFailed, "attach went wrong")
                },
                output = StringBuilder(),
                errorOutput = errorOutput,
            )

        assertEquals(3, cli.run(listOf("attach", "42")))
        assertEquals(
            "Spectre daemon error: attach went wrong\n" +
                "Hint: run \"spectre guide agent\" or \"spectre guide --search <words>\".\n",
            errorOutput.toString(),
        )
    }

    @Test
    fun `daemon transport errors point to the troubleshooting topic`() {
        val errorOutput = StringBuilder()
        val cli =
            SpectreCli(
                request = { throw IOException("Socket unavailable") },
                output = StringBuilder(),
                errorOutput = errorOutput,
            )

        assertEquals(5, cli.run(listOf("ps")))
        assertTrue(
            errorOutput.contains("Hint: run \"spectre guide troubleshooting\""),
            errorOutput.toString(),
        )
    }

    private fun runGuide(vararg arguments: String): GuideRun {
        val output = StringBuilder()
        val errorOutput = StringBuilder()
        val cli =
            SpectreCli(
                request = { error("guide must not contact the daemon") },
                output = output,
                errorOutput = errorOutput,
            )
        val exitCode = cli.run(arguments.toList())
        return GuideRun(exitCode, output.toString(), errorOutput.toString())
    }

    private data class GuideRun(val exitCode: Int, val output: String, val errorOutput: String)

    private companion object {
        val GUIDE_DOCS: Path = Path.of("..", "docs", "guide").toAbsolutePath().normalize()
    }
}
