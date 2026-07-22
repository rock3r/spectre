package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import java.io.IOException

/**
 * Structured agent transport failure (#199).
 *
 * Subclasses [IOException] so existing `catch (IOException)` sites keep working, and exposes a
 * stable [category] so clients can distinguish unsupported ops, timeouts, and missing nodes without
 * parsing free-text messages.
 */
@ExperimentalSpectreAgentApi
public class SpectreAgentException(
    public val category: AgentErrorCategory,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
