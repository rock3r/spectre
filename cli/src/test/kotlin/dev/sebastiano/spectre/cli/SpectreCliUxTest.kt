package dev.sebastiano.spectre.cli

import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** CLI usability behaviour from #560: node lines, hints, key names, and version output. */
class SpectreCliUxTest {
    private val richNode =
        NodeSnapshotDto(
            key = "window-0:root-0:node-7",
            testTag = "save-button",
            texts = listOf("Save"),
            editableText = "draft",
            role = "Button",
            contentDescription = "Save the file",
            isFocused = true,
            isDisabled = true,
            isSelected = true,
            isVisible = true,
            bounds = RectDto(10, 20, 120, 40),
        )

    private val richLine =
        "window-0:root-0:node-7 Button tag=\"save-button\" text=\"Save\" editable=\"draft\" " +
            "desc=\"Save the file\" [focused] [disabled] [selected] @ (10,20 120x40)"

    // --- 1. Human-readable node lines -------------------------------------------------------

    @Test
    fun `tree prints rich human-readable node lines`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { DaemonResponse.Nodes("pid-42", listOf(richNode)) },
                output = output,
            )

        assertEquals(0, cli.run(listOf("tree", "pid-42")))
        assertEquals("$richLine\n", output.toString())
    }

    @Test
    fun `find prints rich human-readable node lines`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(DaemonRequest.FindByTestTag("pid-42", "save-button"), request)
                    DaemonResponse.Nodes("pid-42", listOf(richNode))
                },
                output = output,
            )

        assertEquals(0, cli.run(listOf("find", "pid-42", "save-button")))
        assertEquals("$richLine\n", output.toString())
    }

    @Test
    fun `find-text prints rich human-readable node lines`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { DaemonResponse.Nodes("pid-42", listOf(richNode)) },
                output = output,
            )

        assertEquals(0, cli.run(listOf("find-text", "pid-42", "Save")))
        assertEquals("$richLine\n", output.toString())
    }

    @Test
    fun `node lines omit empty metadata and fall back to Node for missing roles`() {
        val node =
            NodeSnapshotDto(
                key = "k",
                testTag = null,
                texts = listOf(""),
                editableText = "",
                role = null,
                contentDescription = "",
                isVisible = true,
                bounds = RectDto(0, 0, 0, 0),
            )

        assertEquals("k Node @ (0,0 0x0)", formatNodeLine(node))
    }

    @Test
    fun `node lines show every text and description, hidden nodes, and escape quotes`() {
        val node =
            NodeSnapshotDto(
                key = "k",
                testTag = "a\"b",
                texts = listOf("one", "two\nlines"),
                role = "Text",
                contentDescription = "first",
                contentDescriptions = listOf("first", "second"),
                isVisible = false,
                bounds = RectDto(-5, 3, 7, 9),
            )

        assertEquals(
            "k Text tag=\"a\\\"b\" text=\"one\" text=\"two\\nlines\" desc=\"first\" " +
                "desc=\"second\" [hidden] @ (-5,3 7x9)",
            formatNodeLine(node),
        )
    }

    // --- 2. "Maybe you meant" hints ---------------------------------------------------------

    @Test
    fun `unknown session-listing commands hint at daemon status and ps`() {
        val errorOutput = StringBuilder()
        val cli = usageOnlyCli(errorOutput = errorOutput)

        assertEquals(2, cli.run(listOf("sessions")))
        assertTrue(
            errorOutput.contains(
                "hint: use 'spectre daemon status' to list active sessions, or 'spectre ps' " +
                    "to discover attachable JVMs"
            ),
            errorOutput.toString(),
        )
    }

    @Test
    fun `unknown daemon subcommands get daemon-scoped hints`() {
        val errorOutput = StringBuilder()
        val cli = usageOnlyCli(errorOutput = errorOutput)

        assertEquals(2, cli.run(listOf("daemon", "stop")))
        assertTrue(
            errorOutput.contains(
                "hint: use 'spectre detach <session>' to close a session or " +
                    "'spectre daemon kill' to stop the daemon"
            ),
            errorOutput.toString(),
        )
    }

    @Test
    fun `unknown key commands hint at press-key`() {
        val errorOutput = StringBuilder()
        val cli = usageOnlyCli(errorOutput = errorOutput)

        assertEquals(2, cli.run(listOf("keys", "pid-42", "Enter")))
        assertTrue(
            errorOutput.contains(
                "hint: use 'spectre press-key <session> <key> [--modifiers <mods>]'"
            ),
            errorOutput.toString(),
        )
    }

    @Test
    fun `unknown options get command-specific hints`() {
        val errorOutput = StringBuilder()
        val cli = usageOnlyCli(errorOutput = errorOutput)

        assertEquals(2, cli.run(listOf("find", "pid-42", "--text", "Save")))
        assertTrue(
            errorOutput.contains("hint: use 'spectre find-text <session> <text>'"),
            errorOutput.toString(),
        )
    }

    @Test
    fun `unknown commands without a curated hint point at the matching registered command`() {
        val errorOutput = StringBuilder()
        val cli = usageOnlyCli(errorOutput = errorOutput)

        assertEquals(2, cli.run(listOf("wait-node", "pid-42")))
        assertTrue(
            errorOutput.contains("hint: maybe you meant 'spectre wait-for-node'"),
            errorOutput.toString(),
        )
    }

    @Test
    fun `hints point at spectre help when there is no guide command`() {
        val errorOutput = StringBuilder()
        val cli = usageOnlyCli(errorOutput = errorOutput)

        assertEquals(2, cli.run(listOf("frobnicate")))
        assertTrue(
            errorOutput.contains("hint: run 'spectre --help' to list every command"),
            errorOutput.toString(),
        )
    }

    @Test
    fun `curated hints that name a missing command are skipped`() {
        assertNull(
            CommandHints.forUnknownCommand(
                parentPath = emptyList(),
                guess = "focus",
                availableCommands = setOf("tree", "click"),
            )
        )
        assertEquals(
            "use 'spectre focus-window <session> [--window <index>]'",
            CommandHints.forUnknownCommand(
                parentPath = emptyList(),
                guess = "focus",
                availableCommands = setOf("tree", "focus-window"),
            ),
        )
    }

    @Test
    fun `guide pointer uses spectre guide search when the guide command exists`() {
        assertEquals(
            "see 'spectre guide --search hierarchy' for usage details",
            CommandHints.helpPointer(guess = "hierarchy", availableCommands = setOf("guide")),
        )
    }

    @Test
    fun `usage errors with --json print a JSON error payload with the hint`() {
        val output = StringBuilder()
        val errorOutput = StringBuilder()
        val cli = usageOnlyCli(output = output, errorOutput = errorOutput)

        assertEquals(2, cli.run(listOf("inspect", "pid-42", "--json")))
        assertEquals("", output.toString())
        val payload = Json.parseToJsonElement(errorOutput.toString().trim()).jsonObject
        assertEquals("1", payload.getValue("version").jsonPrimitive.content)
        assertTrue(
            payload.getValue("error").jsonPrimitive.content.contains("inspect"),
            payload.toString(),
        )
        assertEquals(
            "use 'spectre tree <session>'",
            payload.getValue("hint").jsonPrimitive.content,
        )
    }

    @Test
    fun `usage errors with --json and no hint report a null hint`() {
        val errorOutput = StringBuilder()
        val cli = usageOnlyCli(errorOutput = errorOutput)

        assertEquals(2, cli.run(listOf("tree", "--json")))
        val payload = Json.parseToJsonElement(errorOutput.toString().trim()).jsonObject
        assertEquals(JsonNull, payload.getValue("hint"))
    }

    // --- 3. Human-readable press-key names --------------------------------------------------

    @Test
    fun `press-key accepts named keys`() {
        assertPressKey(listOf("Escape"), KeyEvent.VK_ESCAPE, 0)
        assertPressKey(listOf("enter"), KeyEvent.VK_ENTER, 0)
        assertPressKey(listOf("Return"), KeyEvent.VK_ENTER, 0)
        assertPressKey(listOf("TAB"), KeyEvent.VK_TAB, 0)
        assertPressKey(listOf("a"), KeyEvent.VK_A, 0)
        assertPressKey(listOf("Digit7"), KeyEvent.VK_7, 0)
        assertPressKey(listOf("ArrowDown"), KeyEvent.VK_DOWN, 0)
        assertPressKey(listOf("PgUp"), KeyEvent.VK_PAGE_UP, 0)
        assertPressKey(listOf("F12"), KeyEvent.VK_F12, 0)
        assertPressKey(listOf("/"), KeyEvent.VK_SLASH, 0)
    }

    @Test
    fun `press-key keeps accepting raw integer key codes and modifier masks`() {
        assertPressKey(listOf("83", "--modifiers", "128"), KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK)
    }

    @Test
    fun `press-key keeps bare numbers as raw key codes`() {
        // Before key names existed, `press-key <session> 9` meant VK_TAB; keep that meaning.
        assertPressKey(listOf("9"), KeyEvent.VK_TAB, 0)
        assertPressKey(listOf("10"), KeyEvent.VK_ENTER, 0)
    }

    @Test
    fun `press-key accepts chord shorthand`() {
        assertPressKey(listOf("Cmd+K"), KeyEvent.VK_K, InputEvent.META_DOWN_MASK)
        assertPressKey(
            listOf("Ctrl+Shift+P"),
            KeyEvent.VK_P,
            InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
        )
        assertPressKey(listOf("Ctrl+1"), KeyEvent.VK_1, InputEvent.CTRL_DOWN_MASK)
    }

    @Test
    fun `press-key accepts named modifiers and combines them with the chord`() {
        assertPressKey(
            listOf("Tab", "--modifiers", "shift"),
            KeyEvent.VK_TAB,
            InputEvent.SHIFT_DOWN_MASK,
        )
        assertPressKey(
            listOf("Ctrl+S", "--modifiers", "option,command"),
            KeyEvent.VK_S,
            InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK,
        )
        assertPressKey(
            listOf("S", "--modifiers", "control+alt"),
            KeyEvent.VK_S,
            InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK,
        )
    }

    @Test
    fun `press-key rejects unknown key names with the accepted forms`() {
        val errorOutput = StringBuilder()
        val cli = usageOnlyCli(errorOutput = errorOutput)

        assertEquals(2, cli.run(listOf("press-key", "pid-42", "Hyper")))
        assertTrue(errorOutput.contains("unknown key 'Hyper'"), errorOutput.toString())
        assertTrue(errorOutput.contains("Ctrl+Shift+P"), errorOutput.toString())
    }

    @Test
    fun `press-key rejects a bare modifier as the key`() {
        val error = assertFailsWith<IllegalArgumentException> { parseKeyChord("Shift") }
        assertTrue(error.message!!.contains("modifier"), error.message)
    }

    @Test
    fun `press-key rejects unknown modifier names`() {
        val error = assertFailsWith<IllegalArgumentException> { parseModifiers("shift,hyper") }
        assertTrue(error.message!!.contains("hyper"), error.message)
    }

    // --- 4. Version ------------------------------------------------------------------------

    @Test
    fun `version flags and subcommand print the build version`() {
        for (arguments in listOf(listOf("--version"), listOf("-v"), listOf("version"))) {
            val output = StringBuilder()
            val cli = usageOnlyCli(output = output)

            assertEquals(0, cli.run(arguments), arguments.toString())
            assertEquals("spectre ${SpectreBuildMetadata.version}\n", output.toString())
        }
    }

    @Test
    fun `version flags and subcommand support --json`() {
        for (arguments in
            listOf(
                listOf("--version", "--json"),
                listOf("-v", "--json"),
                listOf("version", "--json"),
            )) {
            val output = StringBuilder()
            val cli = usageOnlyCli(output = output)

            assertEquals(0, cli.run(arguments), arguments.toString())
            assertEquals(
                "{\"version\":1,\"spectreVersion\":\"${SpectreBuildMetadata.version}\"}\n",
                output.toString(),
            )
        }
    }

    private fun assertPressKey(arguments: List<String>, keyCode: Int, modifiers: Int) {
        val output = StringBuilder()
        val errorOutput = StringBuilder()
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(DaemonRequest.PressKey("pid-42", keyCode, modifiers), request)
                    DaemonResponse.Completed("pid-42")
                },
                output = output,
                errorOutput = errorOutput,
            )

        assertEquals(
            0,
            cli.run(listOf("press-key", "pid-42") + arguments),
            "$arguments: $errorOutput",
        )
    }

    private fun usageOnlyCli(
        output: StringBuilder = StringBuilder(),
        errorOutput: StringBuilder = StringBuilder(),
    ): SpectreCli =
        SpectreCli(
            request = { error("Daemon should not be contacted") },
            output = output,
            errorOutput = errorOutput,
        )
}
