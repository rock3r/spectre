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
     * cancel, and deadline budgets; long ops run off the accept thread. v3: bulk payloads
     * (`pngBytes`, `captureJsonUtf8`) encode as CBOR byte strings rather than integer arrays.
     *
     * v3 is a **representation** change, invisible to the type system: a library and a runtime jar
     * from either side of it would otherwise pass the exact-match handshake and fail only when a
     * screenshot came back in an encoding the peer does not speak. `AttachOptions.agentJarPath` and
     * the runtime-jar system property make that pairing reachable, so it has to fail at Hello.
     */
    public const val CURRENT: Int = 3
}
