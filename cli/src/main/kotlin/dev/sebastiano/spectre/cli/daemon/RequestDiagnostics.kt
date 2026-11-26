package dev.sebastiano.spectre.cli.daemon

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** Routes daemon stderr to its persistent log and to the session that caused each diagnostic. */
internal object RequestDiagnostics {
    private val destination = InheritableThreadLocal<Collector?>()
    private val sessions = ConcurrentHashMap<String, SessionWarnings>()

    fun install() {
        if (System.err is DiagnosticPrintStream) return
        System.setErr(DiagnosticPrintStream(System.err))
    }

    /**
     * Captures warnings around one atomic response boundary. A concurrent producer is included in
     * this response or retained for the next session request, never lost or duplicated.
     */
    fun <T> capture(sessionId: String?, block: () -> T): Pair<T, List<String>> {
        val session = sessionId?.let { sessions.computeIfAbsent(it) { SessionWarnings() } }
        val collector = Collector(session)
        val previous = destination.get()
        destination.set(collector)
        val delayed = session?.drainPending().orEmpty()
        return try {
            val result = block()
            result to (delayed + collector.closeAndSnapshot())
        } finally {
            destination.set(previous)
            collector.closeAndSnapshot()
        }
    }

    fun <T> capture(block: () -> T): Pair<T, List<String>> = capture(null, block)

    /** Carries current provenance into asynchronous work, including reused executor threads. */
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

    /** Retires the stable lifecycle before removing it, so late helpers cannot recreate a queue. */
    fun forget(sessionId: String) {
        sessions.remove(sessionId)?.retire()
    }

    fun clear() {
        sessions.values.forEach(SessionWarnings::retire)
        sessions.clear()
    }

    internal fun report(warning: String) {
        destination.get()?.add(warning)
    }

    private class DiagnosticPrintStream(private val delegate: PrintStream) :
        PrintStream(delegate, true, StandardCharsets.UTF_8) {
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            val text = String(buffer, offset, length, StandardCharsets.UTF_8)
            text.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { report(it) }
        }
    }

    private class SessionWarnings {
        private var retired = false
        private var pending = WarningBuffer()

        @Synchronized
        fun addAfterBoundary(warning: String) {
            if (!retired) pending.add(warning)
        }

        @Synchronized
        fun drainPending(): List<String> {
            if (retired) return emptyList()
            val detached = pending
            pending = WarningBuffer()
            return detached.snapshot()
        }

        @Synchronized
        fun retire() {
            retired = true
            pending = WarningBuffer()
        }
    }

    private class Collector(private val session: SessionWarnings?) {
        private val lock = Any()
        private var active = true
        private val warnings = WarningBuffer()

        fun add(warning: String) {
            synchronized(lock) {
                if (active) {
                    warnings.add(warning)
                    return
                }
            }
            session?.addAfterBoundary(warning)
        }

        fun closeAndSnapshot(): List<String> =
            synchronized(lock) {
                active = false
                warnings.snapshot()
            }
    }

    private class WarningBuffer {
        private val warnings = mutableListOf<String>()
        private var discardedWarnings = 0
        private var discardedCharacters = 0

        fun add(warning: String) {
            val bounded = warning.take(MAX_LENGTH)
            discardedCharacters += warning.length - bounded.length
            if (warnings.size < MAX_WARNINGS) warnings += bounded else discardedWarnings++
        }

        fun snapshot(): List<String> {
            if (discardedCharacters == 0 && discardedWarnings == 0) return warnings.toList()
            val markerDiscardsWarning = warnings.size == MAX_WARNINGS
            val totalDiscardedWarnings = discardedWarnings + if (markerDiscardsWarning) 1 else 0
            val reason = buildList {
                if (totalDiscardedWarnings > 0) add("$totalDiscardedWarnings warning(s)")
                if (discardedCharacters > 0) add("$discardedCharacters warning character(s)")
            }
            val marker =
                "[spectre-daemon] diagnostics truncated (discarded ${reason.joinToString(" and ")}); " +
                    "recent diagnostics may remain in the retained suffix of the per-socket " +
                    "daemon log"
            return if (markerDiscardsWarning) warnings.dropLast(1) + marker else warnings + marker
        }
    }

    private const val MAX_WARNINGS: Int = 100
    private const val MAX_LENGTH: Int = 4_096
}
