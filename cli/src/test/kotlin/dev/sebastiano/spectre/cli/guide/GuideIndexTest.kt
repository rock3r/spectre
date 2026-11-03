package dev.sebastiano.spectre.cli.guide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuideIndexTest {
    @Test
    fun `stripAliases removes alias comments and the lines that only hold one`() {
        val markdown =
            """
            |# Title
            |<!-- aliases: hang, stuck -->
            |Text stays. <!-- aliases: inline -->
            || A | B | <!-- aliases: row -->
            |"""
                .trimMargin()

        assertEquals("# Title\nText stays.\n| A | B |\n", stripAliases(markdown))
    }

    @Test
    fun `stripAliases does not leave a double blank line where a comment line was`() {
        val markdown = "### Heading\n\n<!-- aliases: hang -->\n\nText.\n"

        assertEquals("### Heading\n\nText.\n", stripAliases(markdown))
    }

    @Test
    fun `split builds heading paths without the page title and ignores fenced headings`() {
        val markdown =
            """
            |# Topic title
            |
            |Intro text.
            |
            |## First
            |
            |First body.
            |
            |```shell
            |# not a heading
            |```
            |
            |### Nested
            |
            |Nested body.
            |"""
                .trimMargin()

        val sections = splitGuide("demo", markdown)

        assertEquals(
            listOf(listOf("Topic title"), listOf("First"), listOf("First", "Nested")),
            sections.map { it.path },
        )
        assertTrue(sections[1].text.contains("# not a heading"))
        assertEquals("demo", sections[1].topic)
    }

    @Test
    fun `split makes each table row its own section with the table header`() {
        val markdown =
            """
            |# Title
            |
            |## Symptoms
            |
            || Symptom | Fix |
            || --- | --- |
            || `xvfb-run` never exits | Pass `--no-daemon` | <!-- aliases: hang -->
            || Click lands on the edge | Scroll first |
            |"""
                .trimMargin()

        val rows = splitGuide("traps", markdown).filter { it.path.size == 2 }

        assertEquals(
            listOf(
                listOf("Symptoms", "xvfb-run never exits"),
                listOf("Symptoms", "Click lands on the edge"),
            ),
            rows.map { it.path },
        )
        assertEquals(
            "| Symptom | Fix |\n| --- | --- |\n| `xvfb-run` never exits | Pass `--no-daemon` |",
            rows[0].text,
        )
        assertEquals(listOf("hang"), rows[0].aliases)
    }

    @Test
    fun `split makes each bold-lead bullet a searchable section of its parent`() {
        val markdown =
            """
            |# Title
            |
            |## Drivers
            |
            |Pick one:
            |
            |- **Synthetic** sends AWT events.
            |  It is the default.
            |- **Real** uses java.awt.Robot.
            |"""
                .trimMargin()

        val sections = splitGuide("interactions", markdown)
        val parent = sections.single { it.path == listOf("Drivers") }
        val bullet = sections.single { it.path == listOf("Drivers", "Synthetic") }

        assertEquals(parent.group, bullet.group)
        assertEquals(
            "## Drivers\n\n- **Synthetic** sends AWT events.\n  It is the default.",
            bullet.text,
        )
    }

    @Test
    fun `search ranks the matching section first and reports a breadcrumb`() {
        val index =
            GuideIndex(
                splitGuide(
                    "traps",
                    """
                    |# Traps
                    |
                    |## Linux xvfb-run never exits
                    |
                    |Pass --no-daemon to Gradle under xvfb-run.
                    |
                    |## Typing needs focus
                    |
                    |Click the field before typing.
                    |"""
                        .trimMargin(),
                )
            )

        val hits = index.search("xvfb gradle", limit = 3)

        assertEquals(listOf("Linux xvfb-run never exits"), hits.first().section.path)
        assertEquals("traps › Linux xvfb-run never exits", hits.first().section.breadcrumb)
    }

    @Test
    fun `search finds a section through its alias comment`() {
        val index =
            GuideIndex(
                splitGuide(
                    "traps",
                    """
                    |# Traps
                    |
                    |## Wrapping Gradle in a virtual display
                    |<!-- aliases: hang, stuck -->
                    |
                    |Pass --no-daemon.
                    |
                    |## Something else
                    |
                    |Unrelated text.
                    |"""
                        .trimMargin(),
                )
            )

        val hits = index.search("stuck", limit = 3)

        assertEquals(listOf("Wrapping Gradle in a virtual display"), hits.single().section.path)
        assertFalse(hits.single().section.text.contains("aliases"))
    }

    @Test
    fun `search expands synonyms so gotcha finds a trap`() {
        val index =
            GuideIndex(
                splitGuide(
                    "traps",
                    "# Traps\n\n## Common traps\n\nEvery trap listed here.\n\n## Other\n\nNothing.\n",
                )
            )

        assertEquals(
            listOf("Common traps"),
            index.search("gotchas", limit = 3).first().section.path,
        )
    }

    @Test
    fun `search does not return a bullet and its parent section together`() {
        val index =
            GuideIndex(
                splitGuide(
                    "interactions",
                    "# Input\n\n## Drivers\n\n- **Synthetic** sends synthetic AWT events.\n" +
                        "- **Real** sends Robot events.\n",
                )
            )

        val hits = index.search("synthetic", limit = 5)

        assertEquals(1, hits.size)
        assertEquals(listOf("Drivers", "Synthetic"), hits.single().section.path)
    }

    @Test
    fun `search returns nothing for a query without words`() {
        val index = GuideIndex(splitGuide("traps", "# Traps\n\n## A\n\nText.\n"))

        assertTrue(index.search("  ?? ", limit = 3).isEmpty())
    }

    @Test
    fun `cap truncates long hits and drops hits past the total budget`() {
        val long = "line of text\n".repeat(200)
        val sections =
            splitGuide("cli", "# CLI\n\n## One\n\n$long\n## Two\n\n$long\n## Three\n\n$long")
        val hits = GuideIndex(sections).search("line text", limit = 3)

        val capped = capHits(hits, perHitBytes = 1_000, totalBytes = 1_500)

        assertEquals(2, capped.hits.size)
        assertEquals(1, capped.omitted)
        assertTrue(capped.hits.all { it.truncated })
        assertTrue(capped.hits.first().text.endsWith("(the rest is in `spectre guide cli`)"))
        assertTrue(capped.hits.sumOf { it.text.toByteArray().size } <= 1_500)
    }
}
