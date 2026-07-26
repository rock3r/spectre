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
     * an underscore suffix so `createDirectories` does not fail on Windows.
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
        return if (isReservedWindowsDeviceName(base)) "${base}_" else base
    }

    /**
     * True when [name] is a Windows reserved device name (case-insensitive), including optional
     * extension forms such as `nul.txt` / `COM1.anything`.
     */
    private fun isReservedWindowsDeviceName(name: String): Boolean {
        val stem = name.substringBefore('.').lowercase()
        return stem in RESERVED_WINDOWS_DEVICE_NAMES
    }

    private val RESERVED_WINDOWS_DEVICE_NAMES: Set<String> = buildSet {
        addAll(listOf("con", "prn", "aux", "nul"))
        for (n in 0..9) {
            add("com$n")
            add("lpt$n")
        }
    }
}
