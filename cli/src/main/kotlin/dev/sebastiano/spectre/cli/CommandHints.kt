package dev.sebastiano.spectre.cli

/**
 * "Maybe you meant" hints for unknown commands and options (#560).
 *
 * Agents often guess command names and flags from other CLIs (`sessions`, `inspect`, `keys`, `find
 * --text`). A curated table maps those guesses to the Spectre command that does the job. When the
 * table has no entry, the guess is matched against the registered commands by shared words and then
 * by spelling.
 *
 * Command paths are space-separated (`"daemon status"`). [availableCommands] is every registered
 * command path, so a curated hint that names a command this build lacks is skipped rather than
 * printed.
 */
internal object CommandHints {
    /** Suggests a command for [guess], an unknown subcommand of [parentPath]. */
    fun forUnknownCommand(
        parentPath: List<String>,
        guess: String,
        availableCommands: Set<String>,
    ): String? {
        val normalized = guess.lowercase()
        val parent = parentPath.joinToString(" ")
        val curated = COMMAND_HINTS.firstOrNull { hint ->
            hint.parent == parent &&
                normalized in hint.guesses &&
                availableCommands.containsAll(hint.requires)
        }
        return curated?.text
            ?: closestCommand(parentPath, normalized, availableCommands)?.let { command ->
                "maybe you meant 'spectre $command'"
            }
    }

    /** Suggests the right syntax for [option], an unknown option of [commandPath]. */
    fun forUnknownOption(commandPath: List<String>, option: String): String? {
        val command = commandPath.joinToString(" ")
        val hint =
            OPTION_HINTS.firstOrNull { hint ->
                (hint.commands.isEmpty() || command in hint.commands) && option in hint.options
            } ?: return null
        return hint.text.replace(COMMAND_PLACEHOLDER, command.ifEmpty { "<command>" })
    }

    /** Where to read more: `spectre guide --search` when this build has it, else `--help`. */
    fun helpPointer(guess: String, availableCommands: Set<String>): String =
        if (GUIDE_COMMAND in availableCommands) {
            "see 'spectre guide --search $guess' for usage details"
        } else {
            "run 'spectre --help' to list every command"
        }

    /** Where to read about [commandPath]'s options. */
    fun optionHelpPointer(commandPath: List<String>): String {
        val command = listOf("spectre") + commandPath
        return "run '${command.joinToString(" ")} --help' to list its options"
    }

    private fun closestCommand(
        parentPath: List<String>,
        guess: String,
        availableCommands: Set<String>,
    ): String? {
        val prefix = parentPath.joinToString(" ")
        val candidates = availableCommands.filter { command ->
            prefix.isEmpty() || command.startsWith("$prefix ")
        }
        val guessWords = words(guess)
        val byWords =
            candidates
                .map { command -> command to words(command).intersect(guessWords).size }
                .filter { (_, shared) -> shared > 0 }
                .maxWithOrNull(
                    compareBy<Pair<String, Int>> { it.second }.thenBy { -it.first.length }
                )
                ?.first
        return byWords
            ?: candidates
                .filter { guess.length >= MIN_TYPO_LENGTH }
                .map { command -> command to editDistance(guess, command.substringAfterLast(' ')) }
                .filter { (_, distance) -> distance <= MAX_TYPO_DISTANCE }
                .minByOrNull { it.second }
                ?.first
    }

    private fun words(value: String): Set<String> =
        value.lowercase().split('-', '_', ' ').filter { it.length >= MIN_WORD_LENGTH }.toSet()

    private fun editDistance(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        for (i in 1..left.length) {
            val current = IntArray(right.length + 1)
            current[0] = i
            for (j in 1..right.length) {
                val substitution = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] =
                    minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + substitution)
            }
            previous = current
        }
        return previous[right.length]
    }

    private data class CommandHint(
        val parent: String,
        val guesses: Set<String>,
        val text: String,
        val requires: Set<String>,
    )

    private data class OptionHint(
        val commands: Set<String>,
        val options: Set<String>,
        val text: String,
    )

    private const val GUIDE_COMMAND: String = "guide"
    private const val COMMAND_PLACEHOLDER: String = "{command}"
    private const val MIN_WORD_LENGTH: Int = 2
    private const val MIN_TYPO_LENGTH: Int = 3
    private const val MAX_TYPO_DISTANCE: Int = 2

    private val COMMAND_HINTS: List<CommandHint> =
        listOf(
            CommandHint(
                parent = "",
                guesses = setOf("sessions", "session", "list", "ls", "list-sessions"),
                text =
                    "use 'spectre daemon status' to list active sessions, or 'spectre ps' to " +
                        "discover attachable JVMs",
                requires = setOf("daemon status", "ps"),
            ),
            CommandHint(
                parent = "",
                guesses = setOf("list-jvms", "jvms", "processes", "list-processes", "procs"),
                text = "use 'spectre ps' to discover attachable JVMs",
                requires = setOf("ps"),
            ),
            CommandHint(
                parent = "",
                guesses = setOf("stop", "kill", "quit", "exit", "close", "disconnect"),
                text =
                    "use 'spectre detach <session>' to close a session or 'spectre daemon kill' " +
                        "to stop the daemon",
                requires = setOf("detach", "daemon kill"),
            ),
            CommandHint(
                parent = "",
                guesses = setOf("connect", "open"),
                text = "use 'spectre attach <pid>' (find the pid with 'spectre ps')",
                requires = setOf("attach", "ps"),
            ),
            CommandHint(
                parent = "",
                guesses =
                    setOf("key", "keys", "shortcut", "hotkey", "send-key", "send-keys", "keypress"),
                text = "use 'spectre press-key <session> <key> [--modifiers <mods>]'",
                requires = setOf("press-key"),
            ),
            CommandHint(
                parent = "",
                guesses =
                    setOf("type-text", "enter-text", "input", "write", "send-text", "fill", "text"),
                text = "use 'spectre type <session> <text>' (click/focus the target input first)",
                requires = setOf("type"),
            ),
            CommandHint(
                parent = "",
                guesses = setOf("inspect", "dump", "hierarchy", "nodes", "ui", "semantics"),
                text = "use 'spectre tree <session>'",
                requires = setOf("tree"),
            ),
            CommandHint(
                parent = "",
                guesses = setOf("query", "search", "locate", "get", "select", "find-tag"),
                text = "use 'spectre find <session> <tag>' or 'spectre find-text <session> <text>'",
                requires = setOf("find", "find-text"),
            ),
            CommandHint(
                parent = "",
                guesses = setOf("tap", "click-node"),
                text = "use 'spectre click <session> <node-key>'",
                requires = setOf("click"),
            ),
            CommandHint(
                parent = "",
                guesses = setOf("focus", "activate", "raise", "front", "bring-to-front"),
                text = "use 'spectre focus-window <session> [--window <index>]'",
                requires = setOf("focus-window"),
            ),
            CommandHint(
                parent = "",
                guesses = setOf("status", "info"),
                text = "use 'spectre daemon status' or 'spectre record status <session>'",
                requires = setOf("daemon status", "record status"),
            ),
            CommandHint(
                parent = "",
                guesses = setOf("sleep", "pause", "delay", "idle", "wait-idle"),
                text =
                    "Spectre commands automatically await idle; to inspect state, use " +
                        "'spectre tree <session>'",
                requires = setOf("tree"),
            ),
            CommandHint(
                parent = "",
                guesses = setOf("snapshot", "screencap", "snap", "image"),
                text =
                    "use 'spectre capture <session> [--window <index>]' for a window image plus " +
                        "its tree, or 'spectre screenshot <session> --fullscreen'",
                requires = setOf("capture", "screenshot"),
            ),
            CommandHint(
                parent = "daemon",
                guesses = setOf("stop", "shutdown", "quit", "exit", "down"),
                text =
                    "use 'spectre detach <session>' to close a session or 'spectre daemon kill' " +
                        "to stop the daemon",
                requires = setOf("detach", "daemon kill"),
            ),
            CommandHint(
                parent = "daemon",
                guesses = setOf("list", "ls", "sessions", "ps", "info"),
                text = "use 'spectre daemon status' to list active sessions",
                requires = setOf("daemon status"),
            ),
            CommandHint(
                parent = "daemon",
                guesses = setOf("start", "run", "up"),
                text =
                    "the daemon starts on demand; run any session command, such as " +
                        "'spectre ps' or 'spectre attach <pid>'",
                requires = setOf("ps", "attach"),
            ),
            CommandHint(
                parent = "record",
                guesses = setOf("begin"),
                text = "use 'spectre record start <session>'",
                requires = setOf("record start"),
            ),
            CommandHint(
                parent = "record",
                guesses = setOf("end", "finish", "save"),
                text = "use 'spectre record stop <session>'",
                requires = setOf("record stop"),
            ),
        )

    private val NODE_KEY_COMMANDS: Set<String> =
        setOf("click", "double-click", "long-click", "scroll-wheel")

    private val OPTION_HINTS: List<OptionHint> =
        listOf(
            OptionHint(
                commands = setOf("tree"),
                options = setOf("--window", "-w", "--window-index"),
                text =
                    "'spectre tree' lists every window; use 'spectre windows <session>' for " +
                        "window indices, or 'spectre capture <session> --window <index>' to save " +
                        "one window's tree",
            ),
            OptionHint(
                commands = setOf("tree", "find"),
                options = setOf("--text"),
                text = "use 'spectre find-text <session> <text>'",
            ),
            OptionHint(
                commands = setOf("tree", "find-text"),
                options = setOf("--tag", "--test-tag"),
                text = "use 'spectre find <session> <tag>'",
            ),
            OptionHint(
                commands = setOf("find"),
                options = setOf("--tag", "--test-tag"),
                text = "pass the tag as an argument: 'spectre find <session> <tag>'",
            ),
            OptionHint(
                commands = setOf("find-text"),
                options = setOf("--text"),
                text =
                    "pass the text as an argument: 'spectre find-text <session> <text> " +
                        "[--substring]'",
            ),
            OptionHint(
                commands = setOf("find-text"),
                options = setOf("--exact", "--contains", "--partial"),
                text =
                    "find-text matches exactly by default; add --substring to match part of a " +
                        "text",
            ),
            OptionHint(
                commands = setOf("press-key"),
                options = setOf("--key", "--key-code", "--keycode", "--code", "-k"),
                text =
                    "pass the key as an argument: 'spectre press-key <session> <key> " +
                        "[--modifiers <mods>]', for example Enter, Escape, or Ctrl+S",
            ),
            OptionHint(
                commands = setOf("press-key"),
                options = setOf("--modifier", "--mod", "--mods", "-m"),
                text = "use --modifiers <mods>, for example --modifiers ctrl,shift",
            ),
            OptionHint(
                commands = setOf("type"),
                options = setOf("--text", "-t"),
                text = "pass the text as an argument: 'spectre type <session> <text>'",
            ),
            OptionHint(
                commands = NODE_KEY_COMMANDS,
                options = setOf("--tag", "--text", "--node", "--key", "--node-key"),
                text =
                    "pass a node key as an argument: 'spectre {command} <session> <node-key>'; " +
                        "get keys from 'spectre find' or 'spectre find-text'",
            ),
            OptionHint(
                commands = setOf("attach"),
                options = setOf("--pid", "-p"),
                text = "pass the pid as an argument: 'spectre attach <pid>'",
            ),
            OptionHint(
                commands = setOf("screenshot"),
                options = setOf("--out", "-o", "--path"),
                text = "use --output <path>",
            ),
            OptionHint(
                commands = setOf("capture"),
                options = setOf("--output", "--out", "-o", "--path"),
                text = "use --out-dir <directory>",
            ),
            OptionHint(
                commands = emptySet(),
                options = setOf("--session", "--session-id", "--id"),
                text =
                    "pass the session id as the first argument: 'spectre {command} <session> ...'",
            ),
        )
}
