package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.InternalSpectreApi
import dev.sebastiano.spectre.core.isWaylandSession
import java.awt.GraphicsConfiguration
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

/** Layout for committed gold PNGs and mismatch reports (#388). */
public object ScreenshotGoldPaths {

    public const val DEFAULT_GOLD_ROOT: String = "src/test/resources/spectre-golds"
    public const val DEFAULT_REPORTS_ROOT: String = "build/reports/spectre-screenshots"
    private const val SEGMENT_FINGERPRINT_BYTES: Int = 4

    public fun defaultGoldRoot(): Path = Path.of(DEFAULT_GOLD_ROOT).toAbsolutePath().normalize()

    public fun defaultReportsRoot(): Path =
        Path.of(DEFAULT_REPORTS_ROOT).toAbsolutePath().normalize()

    public fun goldFile(
        goldRoot: Path,
        testClassName: String,
        testMethodName: String,
        name: String,
        osKey: String,
        scaleKey: String,
        invocationKey: String? = null,
    ): Path {
        val named =
            goldRoot
                .resolve(sanitizeGoldSegment(testClassName))
                .resolve(sanitizeGoldSegment(testMethodName))
                .resolve(sanitizeGoldSegment(name))
        val invocation = invocationKey?.trim()?.takeIf { it.isNotEmpty() }
        val directory =
            if (invocation == null) named else named.resolve(sanitizeGoldSegment(invocation))
        return directory
            .resolve(sanitizeGoldSegment(osKey))
            .resolve(sanitizeGoldSegment(scaleKey))
            .resolve("gold.png")
            .normalize()
            .also { requireInside(goldRoot, it) }
    }

    public fun reportDirectory(
        reportsRoot: Path,
        testClassName: String,
        testMethodName: String,
        name: String,
        invocationKey: String? = null,
    ): Path {
        val named =
            reportsRoot
                .resolve(sanitizeGoldSegment(testClassName))
                .resolve(sanitizeGoldSegment(testMethodName))
                .resolve(sanitizeGoldSegment(name))
        val invocation = invocationKey?.trim()?.takeIf { it.isNotEmpty() }
        val directory =
            if (invocation == null) named else named.resolve(sanitizeGoldSegment(invocation))
        return directory.normalize().also { requireInside(reportsRoot, it) }
    }

    public fun scaleKey(scaleX: Double, scaleY: Double): String =
        "scale-${formatScale(scaleX)}x${formatScale(scaleY)}"

    /** Scale directory name from a window or screen [GraphicsConfiguration]. */
    public fun scaleKey(configuration: GraphicsConfiguration): String {
        val transform = configuration.defaultTransform
        return scaleKey(transform.scaleX, transform.scaleY)
    }

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
        val escaped = escapeReservedWindowsDeviceName(core)
        // Hash when the segment was rewritten (`foo/bar`, `NUL`) or when it is not already
        // lowercase, so `Main` and `main` cannot collide on case-insensitive filesystems.
        val caseFoldingCollision = escaped != escaped.lowercase(Locale.ROOT)
        return if (escaped == raw && !caseFoldingCollision) {
            escaped
        } else {
            "${escaped}_${stableSegmentFingerprint(raw)}"
        }
    }

    private fun stableSegmentFingerprint(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest, 0, SEGMENT_FINGERPRINT_BYTES)
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
