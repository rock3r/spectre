package dev.sebastiano.spectre.agent

/** Negotiated facts about the actual runtime selected inside the target JVM. */
@ExperimentalSpectreAgentApi
public data class AttachedSessionInfo(
    public val driverKind: String,
    public val inputLeasePolicy: String,
    public val coreProvenance: String,
    /** Null means the target core did not publish manifest version metadata. */
    public val coreVersion: String?,
    public val warnings: List<String> = emptyList(),
)
