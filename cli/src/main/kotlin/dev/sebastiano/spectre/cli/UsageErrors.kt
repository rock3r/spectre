package dev.sebastiano.spectre.cli

import com.github.ajalt.clikt.core.BaseCliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.MultiUsageError
import com.github.ajalt.clikt.core.NoSuchOption
import com.github.ajalt.clikt.core.NoSuchSubcommand
import com.github.ajalt.clikt.core.UsageError
import kotlinx.serialization.Serializable

/** `--json` body for a usage error, written to stderr. [hint] is `null` when there is none. */
@Serializable
internal data class UsageErrorJson(
    val version: Int = USAGE_ERROR_JSON_VERSION,
    val error: String,
    val hint: String?,
)

/**
 * Hints for the unknown commands and options in [error], most specific first, ending with where to
 * read more. Empty for every other kind of usage error.
 */
internal fun usageHints(error: CliktError, availableCommands: Set<String>): List<String> {
    val unknowns =
        ((error as? MultiUsageError)?.errors ?: listOf(error))
            .filterIsInstance<UsageError>()
            .filter { (it is NoSuchSubcommand || it is NoSuchOption) && it.paramName != null }
    val specific = unknowns.mapNotNull { unknown ->
        val name = checkNotNull(unknown.paramName)
        if (unknown is NoSuchSubcommand) {
            CommandHints.forUnknownCommand(unknown.commandPath(), name, availableCommands)
        } else {
            CommandHints.forUnknownOption(unknown.commandPath(), name)
        }
    }
    val pointer =
        unknowns.firstOrNull()?.let { first ->
            if (first is NoSuchSubcommand) {
                CommandHints.helpPointer(checkNotNull(first.paramName), availableCommands)
            } else {
                CommandHints.optionHelpPointer(first.commandPath())
            }
        }
    return (specific + listOfNotNull(pointer)).distinct()
}

/** The command the error was raised in, without the root name (`["daemon"]`). */
private fun UsageError.commandPath(): List<String> =
    context?.commandNameWithParents().orEmpty().drop(1)

/** The bare error lines of Clikt's formatted usage error, without the usage banner. */
internal fun usageErrorMessage(formattedHelp: String, error: CliktError): String =
    formattedHelp
        .lineSequence()
        .filter { it.startsWith(ERROR_PREFIX) }
        .map { it.removePrefix(ERROR_PREFIX) }
        .joinToString("\n")
        .ifEmpty { error.message ?: formattedHelp.trim() }

/**
 * Index of the first argument that names a command [root] does not have, or `null`.
 *
 * Clikt reports `spectre keys pid-42 Enter` as "unexpected extra arguments" rather than "no such
 * subcommand", which hides the guess. Walking the arguments against the command tree finds the
 * unknown name so the caller can re-parse up to it and get Clikt's own "no such subcommand" error.
 */
internal fun unknownCommandIndex(root: BaseCliktCommand<*>, arguments: List<String>): Int? {
    var current = root
    var index = 0
    while (index < arguments.size) {
        val token = arguments[index]
        val subcommands = current.registeredSubcommands()
        if (subcommands.isEmpty() || token == "--") return null
        if (token.startsWith("-") && token.length > 1) {
            index += if (optionTakesValue(current, token)) 2 else 1
        } else {
            current = subcommands.firstOrNull { it.commandName == token } ?: return index
            index++
        }
    }
    return null
}

private fun optionTakesValue(command: BaseCliktCommand<*>, token: String): Boolean =
    '=' !in token &&
        command.registeredOptions().any { option ->
            token in option.names && option.nvalues.first > 0
        }

/** Every registered command path under [command], space-separated (`"daemon status"`). */
internal fun registeredCommandPaths(command: BaseCliktCommand<*>): Set<String> =
    command
        .registeredSubcommands()
        .flatMap { subcommand ->
            listOf(subcommand.commandName) +
                registeredCommandPaths(subcommand).map { "${subcommand.commandName} $it" }
        }
        .toSet()

private const val ERROR_PREFIX: String = "Error: "
private const val USAGE_ERROR_JSON_VERSION: Int = 1
