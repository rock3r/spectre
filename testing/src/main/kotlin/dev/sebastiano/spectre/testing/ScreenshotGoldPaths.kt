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
    private const val MAX_SEGMENT_BYTES: Int = 255
    private val FINGERPRINT_SUFFIX = Regex("_[0-9a-f]{8}$")

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
        val invocation = invocationKey?.takeIf { it.isNotBlank() }
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
        val invocation = invocationKey?.takeIf { it.isNotBlank() }
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
        // Hash when the segment was rewritten (`foo/bar`, `NUL`), when it is not already
        // in a filesystem-relevant Unicode case fold (`Main` vs `main`, Greek `ς` vs `σ`),
        // or when it already occupies the `_` + 8-hex suffix namespace so a literal
        // `foo_bar_cc5d46bd` cannot collide with hashed `foo/bar`.
        val caseFoldingCollision = escaped != filesystemCaseFold(escaped)
        val occupiesFingerprintNamespace = FINGERPRINT_SUFFIX.containsMatchIn(escaped)
        val fingerprint = stableSegmentFingerprint(raw)
        val candidate =
            if (escaped == raw && !caseFoldingCollision && !occupiesFingerprintNamespace) {
                escaped
            } else {
                "${escaped}_$fingerprint"
            }
        return boundGoldSegment(escaped, candidate, fingerprint)
    }

    private fun boundGoldSegment(
        escaped: String,
        candidate: String,
        fingerprint: String,
    ): String {
        if (candidate.toByteArray(Charsets.UTF_8).size <= MAX_SEGMENT_BYTES) return candidate
        val suffix = "_$fingerprint"
        val budget = MAX_SEGMENT_BYTES - suffix.toByteArray(Charsets.UTF_8).size
        var prefix = escaped
        while (prefix.isNotEmpty() && prefix.toByteArray(Charsets.UTF_8).size > budget) {
            prefix = prefix.dropLast(1)
        }
        return prefix.trimEnd('_') + suffix
    }

    private fun stableSegmentFingerprint(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest, 0, SEGMENT_FINGERPRINT_BYTES)
    }

    /**
     * Case-insensitive volumes compare via full Unicode case fold, not `lowercase` alone. Greek `ς`
     * and `σ` are both lowercase but fold to the same character; `uppercase` then `lowercase` is
     * the filesystem-relevant fold used to decide whether to fingerprint.
     */
    private fun filesystemCaseFold(value: String): String =
        value.uppercase(Locale.ROOT).lowercase(Locale.ROOT)

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
