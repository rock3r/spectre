package dev.sebastiano.spectre.testing

import java.nio.file.Path
import kotlin.io.path.absolute

/**
 * Path layout for JUnit failure artifacts under `build/reports/spectre/`.
 *
 * Layout:
 * ```
 * <reportsRoot>/<test-class>/<test-method>[/attempt-N]/window-<i>/{capture.json,screenshot.png}
 * ```
 *
 * Each `window-<i>` directory matches the atomic-capture layout from #181 so `spectre-capture` `jq`
 * recipes work unchanged. Retry attempts use a nested `attempt-N` directory (not a method-name
 * suffix) so they cannot collide with a test literally named `…-attempt-N`.
 */
public object FailureArtifactPaths {

    /** Common filesystem per-component limit (bytes). */
    internal const val MAX_SEGMENT_BYTES: Int = 255

    public fun defaultReportsRoot(): Path =
        Path.of("build", "reports", "spectre").absolute().normalize()

    public fun methodDirectory(
        testClassName: String,
        testMethodName: String,
        config: FailureArtifactsConfig,
    ): Path {
        val classSeg = sanitizePathSegment(testClassName)
        val methodSeg = sanitizePathSegment(testMethodName)
        var methodDir = config.reportsRoot.resolve(classSeg).resolve(methodSeg)
        val invocation = config.invocationId?.takeIf { it.isNotBlank() }
        if (invocation != null) {
            methodDir = methodDir.resolve(sanitizePathSegment(invocation))
        }
        val attempt = config.attemptIndex
        return if (attempt != null && attempt > 1) {
            methodDir.resolve("attempt-$attempt")
        } else {
            methodDir
        }
    }

    public fun windowDirectory(methodDirectory: Path, windowIndex: Int): Path =
        methodDirectory.resolve("window-$windowIndex")

    /**
     * Replaces characters that are hostile on common filesystems or ambiguous in shell globs.
     * Collapses runs of replacements to a single `_` and trims edges so segments stay non-empty
     * when the input had any content. Reserved Windows device names (`CON`, `NUL`, `COM1`, …) get
     * an underscore after the **stem** so `createDirectories` does not fail on Windows — including
     * dotted forms (`nul.txt` → `nul_.txt`), because Windows keys off the stem before the first
     * `.`. Dot-only names (`.` / `..`) become literal `dot` / `dotdot` so they cannot navigate out
     * of the reports root. When sanitization changes the original string (lossy replacements,
     * reserved-name escape, etc.), a stable hash of the original is appended so distinct names that
     * only differ in replaced punctuation do not collide. Overlong segments are truncated with
     * another hash suffix so parameterized display names still fit filesystem limits.
     */
    internal fun sanitizePathSegment(raw: String): String {
        val cleaned =
            raw.map { ch ->
                    when {
                        ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_' -> ch
                        else -> '_'
                    }
                }
                .joinToString("")
                .replace(Regex("_+"), "_")
        val core = cleaned.trim('_')
        val base =
            when {
                core.isEmpty() -> "unnamed"
                // Pure-dot labels (before stripping trailing dots for Windows) map to literals
                // so they never navigate `..` out of the reports root.
                core.all { it == '.' } -> if (core.length == 1) "dot" else "dotdot"
                else ->
                    // Windows rejects/normalizes trailing dots and spaces; strip them so the
                    // segment is a real directory name. Lossy vs raw → hash below.
                    core.trimEnd('.', ' ').trim('_').ifEmpty { "unnamed" }
            }
        val escaped = escapeReservedWindowsDeviceName(base)
        // Always append a stable hash of the original label so distinct raw names that alias after
        // sanitize (punctuation, case folding, Unicode case folds on case-insensitive volumes)
        // never
        // share a directory.
        val unique = "${escaped}_${shortHash(raw)}"
        return boundSegmentLength(unique)
    }

    /**
     * If the stem (text before the first `.`) is a Windows reserved device name, insert `_` after
     * the stem so the resulting stem is no longer reserved (`NUL` → `NUL_`, `nul.txt` →
     * `nul_.txt`).
     */
    private fun escapeReservedWindowsDeviceName(name: String): String {
        val stem = name.substringBefore('.')
        if (stem.lowercase() !in RESERVED_WINDOWS_DEVICE_NAMES) return name
        val extension = name.removePrefix(stem) // empty, or ".something"
        return "${stem}_$extension"
    }

    /**
     * Keeps [segment] within [MAX_SEGMENT_BYTES] UTF-8 bytes. When truncated, appends `_<hex>` of
     * the original segment's hash so distinct long names do not collapse to the same prefix.
     */
    private fun boundSegmentLength(segment: String): String {
        val utf8 = segment.toByteArray(Charsets.UTF_8)
        if (utf8.size <= MAX_SEGMENT_BYTES) return segment
        val hash = shortHash(segment)
        val suffix = "_$hash"
        val suffixBytes = suffix.toByteArray(Charsets.UTF_8)
        val budget = (MAX_SEGMENT_BYTES - suffixBytes.size).coerceAtLeast(1)
        val prefix = utf8Prefix(utf8, budget).toString(Charsets.UTF_8)
        return prefix + suffix
    }

    private fun shortHash(value: String): String =
        Integer.toUnsignedString(value.hashCode(), HASH_RADIX).padStart(HASH_WIDTH, '0')

    /**
     * Longest UTF-8 prefix of [bytes] whose length is at most [maxBytes] and ends on a complete
     * character boundary (never retains a multi-byte lead without its continuation bytes).
     */
    private fun utf8Prefix(bytes: ByteArray, maxBytes: Int): ByteArray {
        var end = 0
        var index = 0
        while (index < bytes.size) {
            val lead = bytes[index].toInt() and 0xFF
            val charLen =
                when {
                    lead and 0x80 == 0 -> 1
                    lead and 0xE0 == 0xC0 -> 2
                    lead and 0xF0 == 0xE0 -> 3
                    lead and 0xF8 == 0xF0 -> 4
                    else -> 1
                }
            val fits = index + charLen <= bytes.size && end + charLen <= maxBytes
            if (!fits) break
            end += charLen
            index += charLen
        }
        return bytes.copyOf(end)
    }

    private val RESERVED_WINDOWS_DEVICE_NAMES: Set<String> = buildSet {
        addAll(listOf("con", "prn", "aux", "nul"))
        for (n in 0..9) {
            add("com$n")
            add("lpt$n")
        }
    }

    private const val HASH_RADIX: Int = 16
    private const val HASH_WIDTH: Int = 8
}
