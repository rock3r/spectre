package dev.sebastiano.spectre.cli.daemon

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Routes daemon stderr to its persistent log and to the session that caused each diagnostic. */
internal object RequestDiagnostics {
    private val destination = InheritableThreadLocal<Collector?>()
    private val pendingBySession = ConcurrentHashMap<String, WarningBuffer>()

    /** Installs the process-wide tee once. Requests only change thread-local attribution. */
    fun install() {
        if (System.err is DiagnosticPrintStream) return
        System.setErr(DiagnosticPrintStream(System.err))
    }

    /**
     * Captures immediate warnings and drains warnings produced asynchronously by the preceding
     * operation for [sessionId]. A closed collector keeps its provenance: child/helper work that
     * completes later is retained for this session's next operation rather than silently lost.
     */
    fun <T> capture(sessionId: String?, block: () -> T): Pair<T, List<String>> {
        val collector = Collector(sessionId)
        val previous = destination.get()
        destination.set(collector)
        val delayed = sessionId?.let(::drainPending).orEmpty()
        return try {
            val result = block()
            collector.close()
            result to (delayed + collector.snapshot())
        } finally {
            destination.set(previous)
            collector.close()
        }
    }

    /** Captures diagnostics not owned by a live session without retaining delayed output. */
    fun <T> capture(block: () -> T): Pair<T, List<String>> = capture(null, block)

    /** Carries current provenance through executors whose worker threads are reused. */
    fun route(block: () -> Unit): () -> Unit {
        val captured = destination.get()
        return {
            val previous = destination.get()
            destination.set(captured)
            try {
                block()
            } finally {
                destination.set(previous)
            }
        }
    }

    /** Drops delayed diagnostics as soon as the owning session is no longer live. */
    fun forget(sessionId: String) {
        pendingBySession.remove(sessionId)
    }

    fun clear() {
        pendingBySession.clear()
    }

    /** Records a daemon-owned helper diagnostic using the provenance routed to this thread. */
    internal fun report(warning: String) {
        destination.get()?.add(warning)
    }

    private fun drainPending(sessionId: String): List<String> =
        pendingBySession.remove(sessionId)?.snapshot().orEmpty()

    private class DiagnosticPrintStream(private val delegate: PrintStream) :
        PrintStream(delegate, true, StandardCharsets.UTF_8) {
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            val text = String(buffer, offset, length, StandardCharsets.UTF_8)
            text.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
                report(line)
            }
        }
    }

    private class Collector(private val sessionId: String?) {
        private val active = AtomicBoolean(true)
        private val warnings = WarningBuffer()

        fun add(warning: String) {
            if (active.get()) {
                warnings.add(warning)
            } else if (sessionId != null) {
                pendingBySession.computeIfAbsent(sessionId) { WarningBuffer() }.add(warning)
            }
        }

        fun close() {
            active.set(false)
        }

        fun snapshot(): List<String> = warnings.snapshot()
    }

    private class WarningBuffer {
        private val warnings = mutableListOf<String>()
        private var truncatedCount = 0
        private var truncatedLength = false

        @Synchronized
        fun add(warning: String) {
            val bounded = warning.take(MAX_LENGTH)
            if (warning.length > MAX_LENGTH) truncatedLength = true
            if (warnings.size < MAX_WARNINGS) warnings += bounded else truncatedCount++
        }

        @Synchronized
        fun snapshot(): List<String> {
            if (!truncatedLength && truncatedCount == 0) return warnings.toList()
            val reason = buildList {
                if (truncatedCount > 0) add("$truncatedCount additional warning(s)")
                if (truncatedLength) add("warning text beyond $MAX_LENGTH characters")
            }
            val marker =
                "[spectre-daemon] diagnostics truncated (${reason.joinToString()}); " +
                    "complete diagnostics remain in the daemon log"
            return if (warnings.size < MAX_WARNINGS) warnings + marker
            else warnings.dropLast(1) + marker
        }
    }

    private const val MAX_WARNINGS: Int = 100
    private const val MAX_LENGTH: Int = 4_096
}
