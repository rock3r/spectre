package dev.sebastiano.spectre.cli

import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/** A key code plus the `InputEvent.*_DOWN_MASK` modifiers that `press-key` sends. */
internal data class KeyChord(val keyCode: Int, val modifiers: Int = 0)

/**
 * Parses a `press-key` key argument.
 *
 * Accepts an integer `java.awt.event.KeyEvent` code, a key name (`Enter`, `Esc`, `A`, `Digit7`,
 * `/`, `PageDown`, `F5`; case-insensitive), or a chord such as `Ctrl+Shift+P` or `Cmd+K`.
 *
 * A bare number stays a raw key code, as it was before key names existed (`9` is Tab), so the digit
 * keys are `Digit0`-`Digit9` on their own. Inside a chord a single digit is the digit key
 * (`Ctrl+1`), and a longer number is a raw key code.
 *
 * @throws IllegalArgumentException when the key or a chord modifier is unknown.
 */
internal fun parseKeyChord(raw: String): KeyChord {
    val trimmed = raw.trim()
    val rawCode = trimmed.toIntOrNull()
    if (rawCode != null) return KeyChord(rawCode)
    require(trimmed.isNotEmpty()) { "key must not be empty. $KEY_FORMS" }
    val parts = if (trimmed.length > 1 && '+' in trimmed) trimmed.split('+') else listOf(trimmed)
    val keyName = parts.last().trim()
    require(keyName.isNotEmpty()) { "missing key after '+' in '$raw'. $KEY_FORMS" }
    require(modifierMask(keyName) == null) {
        "'$keyName' is a modifier, not a key: combine it with a key, for example $keyName+Tab, " +
            "or pass it with --modifiers"
    }
    val modifiers = parts.dropLast(1).fold(0) { mask, name -> mask or requireModifier(name, raw) }
    val keyCode =
        requireNotNull(keyCodeFor(keyName) ?: keyName.toIntOrNull()) {
            "unknown key '$keyName'. $KEY_FORMS"
        }
    return KeyChord(keyCode, modifiers)
}

/**
 * Parses `press-key --modifiers`: an integer `InputEvent` mask, or names separated by commas or `+`
 * (`shift`, `ctrl`/`control`, `alt`/`option`, `meta`/`cmd`/`command`).
 *
 * @throws IllegalArgumentException when a name is unknown.
 */
internal fun parseModifiers(raw: String): Int {
    val trimmed = raw.trim()
    return trimmed.toIntOrNull()
        ?: trimmed
            .split(',', '+', ' ')
            .filter { it.isNotBlank() }
            .fold(0) { mask, name -> mask or requireModifier(name, raw) }
}

private fun requireModifier(name: String, raw: String): Int {
    val trimmed = name.trim()
    return requireNotNull(modifierMask(trimmed)) {
        val source = if (trimmed == raw.trim()) "" else " in '$raw'"
        "unknown modifier '$trimmed'$source. $MODIFIER_FORMS"
    }
}

private fun keyCodeFor(name: String): Int? =
    if (name.length == 1) SINGLE_CHARACTER_KEYS[name.uppercase().single()]
    else NAMED_KEYS[normalizeName(name)]

private fun modifierMask(name: String): Int? = MODIFIERS[normalizeName(name)]

private fun normalizeName(name: String): String =
    name.lowercase().filterNot { it == '-' || it == '_' || it == ' ' }

private const val KEY_FORMS: String =
    "Use a key name (Enter, Tab, Escape, Backspace, Delete, Space, Up, Down, Left, Right, Home, " +
        "End, PageUp, PageDown, Insert, F1-F12, A-Z, Digit0-Digit9, or punctuation such as / . , " +
        "- = [ ] \\ ; ' `), a chord such as Ctrl+Shift+P or Cmd+K, or an integer " +
        "java.awt.event.KeyEvent code (a bare number is always a key code)"

private const val MODIFIER_FORMS: String =
    "Use shift, ctrl/control, alt/option, meta/cmd/command (comma- or '+'-separated), or an " +
        "integer java.awt.event.InputEvent mask"

private val MODIFIERS: Map<String, Int> =
    mapOf(
        "shift" to InputEvent.SHIFT_DOWN_MASK,
        "ctrl" to InputEvent.CTRL_DOWN_MASK,
        "control" to InputEvent.CTRL_DOWN_MASK,
        "alt" to InputEvent.ALT_DOWN_MASK,
        "option" to InputEvent.ALT_DOWN_MASK,
        "opt" to InputEvent.ALT_DOWN_MASK,
        "meta" to InputEvent.META_DOWN_MASK,
        "cmd" to InputEvent.META_DOWN_MASK,
        "command" to InputEvent.META_DOWN_MASK,
    )

private val SINGLE_CHARACTER_KEYS: Map<Char, Int> =
    ('A'..'Z').associateWith { KeyEvent.VK_A + (it - 'A') } +
        ('0'..'9').associateWith { KeyEvent.VK_0 + (it - '0') } +
        mapOf(
            '/' to KeyEvent.VK_SLASH,
            '.' to KeyEvent.VK_PERIOD,
            ',' to KeyEvent.VK_COMMA,
            '-' to KeyEvent.VK_MINUS,
            '=' to KeyEvent.VK_EQUALS,
            '[' to KeyEvent.VK_OPEN_BRACKET,
            ']' to KeyEvent.VK_CLOSE_BRACKET,
            '\\' to KeyEvent.VK_BACK_SLASH,
            ';' to KeyEvent.VK_SEMICOLON,
            '\'' to KeyEvent.VK_QUOTE,
            '`' to KeyEvent.VK_BACK_QUOTE,
            ' ' to KeyEvent.VK_SPACE,
        )

private val FUNCTION_KEYS: List<Int> =
    listOf(
        KeyEvent.VK_F1,
        KeyEvent.VK_F2,
        KeyEvent.VK_F3,
        KeyEvent.VK_F4,
        KeyEvent.VK_F5,
        KeyEvent.VK_F6,
        KeyEvent.VK_F7,
        KeyEvent.VK_F8,
        KeyEvent.VK_F9,
        KeyEvent.VK_F10,
        KeyEvent.VK_F11,
        KeyEvent.VK_F12,
    )

private val NAMED_KEYS: Map<String, Int> =
    mapOf(
        "enter" to KeyEvent.VK_ENTER,
        "return" to KeyEvent.VK_ENTER,
        "tab" to KeyEvent.VK_TAB,
        "escape" to KeyEvent.VK_ESCAPE,
        "esc" to KeyEvent.VK_ESCAPE,
        "backspace" to KeyEvent.VK_BACK_SPACE,
        "delete" to KeyEvent.VK_DELETE,
        "del" to KeyEvent.VK_DELETE,
        "space" to KeyEvent.VK_SPACE,
        "spacebar" to KeyEvent.VK_SPACE,
        "up" to KeyEvent.VK_UP,
        "arrowup" to KeyEvent.VK_UP,
        "down" to KeyEvent.VK_DOWN,
        "arrowdown" to KeyEvent.VK_DOWN,
        "left" to KeyEvent.VK_LEFT,
        "arrowleft" to KeyEvent.VK_LEFT,
        "right" to KeyEvent.VK_RIGHT,
        "arrowright" to KeyEvent.VK_RIGHT,
        "home" to KeyEvent.VK_HOME,
        "end" to KeyEvent.VK_END,
        "pageup" to KeyEvent.VK_PAGE_UP,
        "pgup" to KeyEvent.VK_PAGE_UP,
        "pagedown" to KeyEvent.VK_PAGE_DOWN,
        "pgdn" to KeyEvent.VK_PAGE_DOWN,
        "insert" to KeyEvent.VK_INSERT,
        "ins" to KeyEvent.VK_INSERT,
        "slash" to KeyEvent.VK_SLASH,
        "period" to KeyEvent.VK_PERIOD,
        "dot" to KeyEvent.VK_PERIOD,
        "comma" to KeyEvent.VK_COMMA,
        "minus" to KeyEvent.VK_MINUS,
        "equals" to KeyEvent.VK_EQUALS,
        "openbracket" to KeyEvent.VK_OPEN_BRACKET,
        "closebracket" to KeyEvent.VK_CLOSE_BRACKET,
        "backslash" to KeyEvent.VK_BACK_SLASH,
        "semicolon" to KeyEvent.VK_SEMICOLON,
        "quote" to KeyEvent.VK_QUOTE,
        "backquote" to KeyEvent.VK_BACK_QUOTE,
        "backtick" to KeyEvent.VK_BACK_QUOTE,
    ) +
        FUNCTION_KEYS.withIndex().associate { (index, keyCode) -> "f${index + 1}" to keyCode } +
        ('0'..'9').associate { digit -> "digit$digit" to KeyEvent.VK_0 + (digit - '0') }
