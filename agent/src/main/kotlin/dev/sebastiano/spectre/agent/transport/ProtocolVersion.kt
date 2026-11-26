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
     * (`pngBytes`, `captureJsonUtf8`) encode as CBOR byte strings rather than integer arrays. v4:
     * `waitForVisualIdle` fails closed unless screen-region fallback is explicitly requested. v5:
     * scoped top-level AWT window-change observation used by atomic accessibility captures.
     *
     * v3 is a **representation** change and v4 is a behavioral safety change, both invisible to the
     * type system. A library and runtime jar from either side would otherwise pass the exact-match
     * handshake and fail late or silently use the old Robot region policy.
     * `AttachOptions.agentJarPath` and the runtime-jar system property make those pairings
     * reachable, so they must fail at Hello.
     */
    public const val CURRENT: Int = 5
}
