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
     * `.`.
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
        val base = cleaned.ifEmpty { "unnamed" }
        return escapeReservedWindowsDeviceName(base)
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

    private val RESERVED_WINDOWS_DEVICE_NAMES: Set<String> = buildSet {
        addAll(listOf("con", "prn", "aux", "nul"))
        for (n in 0..9) {
            add("com$n")
            add("lpt$n")
        }
    }
}
