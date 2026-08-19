package dev.sebastiano.spectre.agent.transport

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Default write budget for a single frame's payload.
 *
 * Screenshots are the bulkiest payload, and the worst case is an incompressible one: PNG can only
 * approach raw bytes-per-pixel, so a 3840x2160 desktop tops out near 25 MB of 24-bit sRGB. 64 MiB
 * clears that with room for a dual-4K desktop, and `SPECTRE_MAX_FRAME_BYTES` raises it for larger
 * multi-monitor HiDPI rigs (see [FrameLimits]).
 *
 * This is a **policy** limit applied when writing. Readers accept anything up to
 * [MAX_FRAME_BYTES_CEILING] so two endpoints on different budgets can never deadlock the wire.
 */
@ExperimentalSpectreAgentApi public const val DEFAULT_MAX_FRAME_BYTES: Int = 64 * 1024 * 1024

/**
 * Hard upper bound on any frame length, whatever the writer's budget.
 *
 * [readFrame] allocates the payload buffer up front from a length it has not yet validated against
 * the sender, so this caps what a corrupt or desynchronised header can make a reader allocate. It
 * is deliberately far above [DEFAULT_MAX_FRAME_BYTES]: keeping it fixed and generous is what lets a
 * reader stay compatible with a writer configured through [FrameLimits], while still refusing the
 * multi-gigabyte lengths a garbled 4-byte header would otherwise produce.
 */
@ExperimentalSpectreAgentApi public const val MAX_FRAME_BYTES_CEILING: Int = 512 * 1024 * 1024

/**
 * Byte-count grammar for [FrameLimits.parseMaxFrameBytes].
 *
 * Lives at file scope on purpose: [FrameLimits] parses the environment in its *own* initializer, so
 * a regex declared inside the object would still be null at that point and only blow up on the
 * machines that actually set the override.
 */
private val FRAME_SIZE_PATTERN = Regex("""(\d+)\s*([A-Za-z]*)""")

/**
 * Process-wide frame write budget (#204).
 *
 * Resolution order: the `SPECTRE_MAX_FRAME_BYTES` environment variable, then
 * [DEFAULT_MAX_FRAME_BYTES]. The CLI's `--max-frame-bytes` and the daemon's matching option both
 * land here through [configure], and the daemon forwards its resolved value to every JVM it injects
 * — an injected agent cannot read the daemon's environment, and it is the process that writes the
 * bulky screenshot frames.
 *
 * Only writers consult this. A reader that receives a frame larger than its own budget still
 * accepts it (up to [MAX_FRAME_BYTES_CEILING]), so raising the budget on one hop never strands a
 * response on another.
 */
@ExperimentalSpectreAgentApi
public object FrameLimits {

    /** Environment variable that overrides [DEFAULT_MAX_FRAME_BYTES]. */
    public const val ENV_VAR: String = "SPECTRE_MAX_FRAME_BYTES"

    @Volatile private var request: Int? = resolveRequest(System::getenv)
    @Volatile private var budget: Int = request ?: DEFAULT_MAX_FRAME_BYTES

    /** Largest payload this process will write into a single frame. */
    public val maxFrameBytes: Int
        get() = budget

    /**
     * The budget this process was explicitly asked for, or `null` when nothing asked.
     *
     * Distinct from [maxFrameBytes] on purpose: an explicit request that happens to equal
     * [DEFAULT_MAX_FRAME_BYTES] is still a request, and callers that propagate or validate the
     * setting must not infer intent from the value. Asking for 64MiB while a daemon runs 128MiB is
     * a real conflict, and a spawned process must be told 64MiB rather than left to re-read an
     * environment that says otherwise.
     */
    public val requestedMaxFrameBytes: Int?
        get() = request

    /**
     * Overrides the write budget for this process, and records it as an explicit request.
     *
     * @throws IllegalArgumentException if [bytes] is not positive or exceeds
     *   [MAX_FRAME_BYTES_CEILING], since a reader would refuse the frames it produced.
     */
    public fun configure(bytes: Int) {
        require(bytes > 0) { "$ENV_VAR must be a positive size, got $bytes" }
        require(bytes <= MAX_FRAME_BYTES_CEILING) {
            "$ENV_VAR=$bytes exceeds the frame ceiling $MAX_FRAME_BYTES_CEILING; " +
                "readers would refuse frames that large"
        }
        budget = bytes
        request = bytes
    }

    /** Drops any [configure] call and re-resolves from the environment, as at process start. */
    public fun resetToEnvironment() {
        request = resolveRequest(System::getenv)
        budget = request ?: DEFAULT_MAX_FRAME_BYTES
    }

    /**
     * Resolves the budget from [getenv], clamping to [MAX_FRAME_BYTES_CEILING]. An unparseable
     * value falls back to [DEFAULT_MAX_FRAME_BYTES] rather than failing process startup: a bad
     * tuning knob should not stop the daemon from booting.
     */
    public fun resolveBudget(getenv: (String) -> String?): Int =
        resolveRequest(getenv) ?: DEFAULT_MAX_FRAME_BYTES

    /** The explicitly requested budget from [getenv], or `null` when the variable asks nothing. */
    public fun resolveRequest(getenv: (String) -> String?): Int? =
        parseMaxFrameBytes(getenv(ENV_VAR))?.coerceAtMost(MAX_FRAME_BYTES_CEILING)

    /**
     * Parses a byte count with an optional binary suffix (`512`, `128k`, `64M`, `64MiB`, `1G`).
     *
     * Returns `null` for anything that is not a positive size that fits an [Int] — including
     * decimal-suffixed forms like `12MB`, so a value that looks like it means 12 million bytes is
     * never silently read as 12 MiB. Overflow returns `null` instead of wrapping, which would
     * shrink the budget when the caller asked to raise it.
     */
    @Suppress("ReturnCount")
    public fun parseMaxFrameBytes(raw: String?): Int? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val match = FRAME_SIZE_PATTERN.matchEntire(text) ?: return null
        val digits = match.groupValues[1].toLongOrNull() ?: return null
        val multiplier =
            when (match.groupValues[2].lowercase()) {
                "" -> 1L
                "k",
                "kib" -> 1024L
                "m",
                "mib" -> 1024L * 1024L
                "g",
                "gib" -> 1024L * 1024L * 1024L
                else -> return null
            }
        val bytes = digits * multiplier
        if (bytes <= 0L || bytes > Int.MAX_VALUE.toLong()) return null
        return bytes.toInt()
    }
}

/**
 * Length-prefixed binary framing for the agent's IPC wire protocol.
 *
 * Wire format: `[4-byte big-endian payload length][payload bytes]`. The 4-byte header carries a
 * non-negative `int` length: writes are capped at [FrameLimits.maxFrameBytes] and reads at
 * [MAX_FRAME_BYTES_CEILING]. Zero-length payloads are legal (they serialize a `data object` to an
 * empty CBOR map sometimes — the codec layer handles them).
 *
 * Streams are not closed by these functions; that's the caller's responsibility.
 *
 * The functions intentionally use [InputStream] / [OutputStream] rather than NIO channels so the
 * same code drives Unix-domain-socket connections, pipe-pair tests, and any future transport that
 * produces stream-like endpoints.
 */
@ExperimentalSpectreAgentApi
public object Framing {
    /**
     * Writes one frame: the 4-byte big-endian header followed by [payload]. Flushes the stream so
     * the receiver doesn't block waiting for buffered bytes.
     */
    @Throws(java.io.IOException::class)
    public fun writeFrame(output: OutputStream, payload: ByteArray) {
        val budget = FrameLimits.maxFrameBytes
        require(payload.size <= budget) {
            "Frame payload size ${payload.size} exceeds MAX_FRAME_BYTES=$budget"
        }
        val header =
            ByteBuffer.allocate(HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(payload.size)
                .array()
        output.write(header)
        if (payload.isNotEmpty()) output.write(payload)
        output.flush()
    }

    /**
     * Reads one frame from [input]. Returns the payload bytes, or `null` on clean EOF (zero bytes
     * read before any header byte arrived). Throws [EOFException] on a truncated header or payload
     * mid-frame, and [IllegalStateException] on negative or over-cap lengths.
     */
    @Throws(java.io.IOException::class)
    public fun readFrame(input: InputStream): ByteArray? {
        val header = readFullyOrNull(input, HEADER_BYTES) ?: return null
        val length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        check(length in 0..MAX_FRAME_BYTES_CEILING) {
            "Invalid frame length $length (header bytes: ${header.joinToString(",") { it.toInt().toString() }})"
        }
        if (length == 0) return ByteArray(0)
        return readFully(input, length)
    }

    private const val HEADER_BYTES = 4

    private fun readFullyOrNull(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r == -1) {
                return if (read == 0) null
                else throw EOFException("Unexpected EOF after $read of $n header bytes")
            }
            read += r
        }
        return buf
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r == -1) throw EOFException("Unexpected EOF after $read of $n payload bytes")
            read += r
        }
        return buf
    }
}
