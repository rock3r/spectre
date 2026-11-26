package dev.sebastiano.spectre.agent

/** Finite attach failure taxonomy decoded from the Windows helper process. */
internal enum class RemoteAttachFailureKind {
    AttachNotSupported,
    AgentLoad,
}

/**
 * Module-independent representation of a known failure reported by the Windows attach helper.
 *
 * The parent runtime may be a custom image without `jdk.attach`, so decoding the helper response
 * must not construct or otherwise link an Attach API exception class.
 */
internal class RemoteAttachFailure(
    val kind: RemoteAttachFailureKind,
    detail: String,
) : RuntimeException(detail)
