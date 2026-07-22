package dev.sebastiano.spectre.agent.transport

/**
 * Agent wire-protocol versioning (#199).
 *
 * Both sides exchange [PROTOCOL_VERSION] as the first frames after the UDS connects. While the
 * agent API is experimental, compatibility is **exact-match**: attacher and runtime must speak the
 * same integer. From 1.0 the rule may become additive-compatible (min/max range); that change will
 * bump this constant and update the handshake docs in `docs/guide/agent.md`.
 */
public object ProtocolVersion {
    /**
     * Current protocol revision carried on [AgentRequest.Hello] / [AgentResponse.HelloAck].
     *
     * v1: bare request/response frames after Hello. v2 (#200): operation envelopes with op ids,
     * cancel, and deadline budgets; long ops run off the accept thread.
     */
    public const val CURRENT: Int = 2
}
