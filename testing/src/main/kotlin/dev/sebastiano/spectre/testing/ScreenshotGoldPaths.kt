package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.InternalSpectreApi
import dev.sebastiano.spectre.core.isWaylandSession
import java.nio.file.Path
import java.util.Locale

/** Layout for committed gold PNGs and mismatch reports (#388). */
public object ScreenshotGoldPaths {

    public const val DEFAULT_GOLD_ROOT: String = "src/test/resources/spectre-golds"
    public const val DEFAULT_REPORTS_ROOT: String = "build/reports/spectre-screenshots"

    public fun defaultGoldRoot(): Path = Path.of(DEFAULT_GOLD_ROOT).toAbsolutePath().normalize()

    public fun defaultReportsRoot(): Path =
        Path.of(DEFAULT_REPORTS_ROOT).toAbsolutePath().normalize()

    public fun goldFile(
        goldRoot: Path,
        testClassName: String,
        name: String,
        osKey: String,
        scaleKey: String,
    ): Path =
        goldRoot
            .resolve(sanitizeGoldSegment(testClassName))
            .resolve(sanitizeGoldSegment(name))
            .resolve(sanitizeGoldSegment(osKey))
            .resolve(sanitizeGoldSegment(scaleKey))
            .resolve("gold.png")
            .normalize()
            .also { requireInside(goldRoot, it) }

    public fun reportDirectory(
        reportsRoot: Path,
        testClassName: String,
        testMethodName: String,
        name: String,
    ): Path =
        reportsRoot
            .resolve(sanitizeGoldSegment(testClassName))
            .resolve(sanitizeGoldSegment(testMethodName))
            .resolve(sanitizeGoldSegment(name))
            .normalize()
            .also { requireInside(reportsRoot, it) }

    public fun scaleKey(scaleX: Double, scaleY: Double): String =
        "scale-${formatScale(scaleX)}x${formatScale(scaleY)}"

    public fun osKey(
        osName: String = System.getProperty("os.name").orEmpty(),
        display: String? = System.getenv("DISPLAY"),
        waylandDisplay: String? = System.getenv("WAYLAND_DISPLAY"),
        sessionType: String? = System.getenv("XDG_SESSION_TYPE"),
        captureBackend: String? = System.getenv("SPECTRE_CAPTURE_BACKEND"),
    ): String {
        val os = osName.lowercase(Locale.ROOT)
        return when {
            "mac" in os -> "macos"
            "win" in os -> "windows"
            "linux" in os -> linuxOsKey(display, waylandDisplay, sessionType, captureBackend)
            else -> os.replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "unknown" }
        }
    }

    @OptIn(InternalSpectreApi::class)
    private fun linuxOsKey(
        display: String?,
        waylandDisplay: String?,
        sessionType: String?,
        captureBackend: String?,
    ): String {
        val getenv: (String) -> String? = { key ->
            when (key) {
                "DISPLAY" -> display
                "WAYLAND_DISPLAY" -> waylandDisplay
                "XDG_SESSION_TYPE" -> sessionType
                "SPECTRE_CAPTURE_BACKEND" -> captureBackend
                else -> System.getenv(key)
            }
        }
        return if (isWaylandSession(getenv = getenv)) "linux-wayland" else "linux-x11"
    }

    internal fun sanitizeGoldSegment(raw: String): String {
        val replaced =
            raw.map { ch ->
                    when {
                        ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_' -> ch
                        else -> '_'
                    }
                }
                .joinToString("")
                .replace(Regex("_+"), "_")
                .trim('_')
        val core =
            when {
                replaced.isEmpty() -> "unnamed"
                replaced.all { it == '.' } -> if (replaced.length == 1) "dot" else "dotdot"
                else -> replaced.trim('.', ' ').trim('_').ifEmpty { "unnamed" }
            }
        return escapeReservedWindowsDeviceName(core)
    }

    private fun escapeReservedWindowsDeviceName(name: String): String {
        val stem = name.substringBefore('.')
        val reserved =
            setOf("con", "prn", "aux", "nul") + (0..9).flatMap { n -> listOf("com$n", "lpt$n") }
        if (stem.lowercase(Locale.ROOT) !in reserved) return name
        val extension = name.removePrefix(stem)
        return "${stem}_$extension"
    }

    private fun formatScale(value: Double): String {
        val asLong = value.toLong()
        return if (value == asLong.toDouble()) asLong.toString() else value.toString()
    }

    private fun requireInside(root: Path, candidate: Path) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedCandidate = candidate.toAbsolutePath().normalize()
        require(normalizedCandidate.startsWith(normalizedRoot)) {
            "path $normalizedCandidate escapes $normalizedRoot"
        }
    }
}
