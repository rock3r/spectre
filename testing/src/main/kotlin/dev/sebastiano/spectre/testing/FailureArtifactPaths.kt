package dev.sebastiano.spectre.testing

import java.nio.file.Path
import kotlin.io.path.absolute

/**
 * Path layout for JUnit failure artifacts under `build/reports/spectre/`.
 *
 * Layout:
 * ```
 * <reportsRoot>/<test-class>/<test-method>[-attempt-N]/window-<i>/{capture.json,screenshot.png}
 * ```
 *
 * Each `window-<i>` directory matches the atomic-capture layout from #181 so `spectre-capture` `jq`
 * recipes work unchanged.
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
        val methodSeg =
            sanitizePathSegment(testMethodName).let { base ->
                val attempt = config.attemptIndex
                if (attempt != null && attempt > 1) "$base-attempt-$attempt" else base
            }
        return config.reportsRoot.resolve(classSeg).resolve(methodSeg)
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
                .trim('_')
        val base =
            when {
                cleaned.isEmpty() -> "unnamed"
                cleaned == "." -> "dot"
                cleaned == ".." -> "dotdot"
                else -> cleaned
            }
        val escaped = escapeReservedWindowsDeviceName(base)
        // Preserve uniqueness when the sanitize path was lossy relative to the original label.
        val unique =
            if (escaped == raw) {
                escaped
            } else {
                "${escaped}_${shortHash(raw)}"
            }
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
     * Longest UTF-8 prefix of [bytes] whose length is at most [maxBytes] and ends on a char
     * boundary.
     */
    private fun utf8Prefix(bytes: ByteArray, maxBytes: Int): ByteArray {
        var end = minOf(maxBytes, bytes.size)
        // Walk back over UTF-8 continuation bytes (10xxxxxx).
        while (
            end > 0 &&
                (bytes[end - 1].toInt() and UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION_VALUE
        ) {
            end--
        }
        // If we stopped on a multi-byte lead that no longer has its full sequence, drop the lead.
        if (end > 0) {
            val last = bytes[end - 1].toInt() and 0xFF
            val need =
                when {
                    last and 0x80 == 0 -> 1
                    last and 0xE0 == 0xC0 -> 2
                    last and 0xF0 == 0xE0 -> 3
                    last and 0xF8 == 0xF0 -> 4
                    else -> 1
                }
            if (end - 1 + need > maxBytes) end--
        }
        return bytes.copyOf(end.coerceAtLeast(0))
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
    private const val UTF8_CONTINUATION_MASK: Int = 0xC0
    private const val UTF8_CONTINUATION_VALUE: Int = 0x80
}
