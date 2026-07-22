package dev.sebastiano.spectre.testing.contract

/**
 * Transport dimension of the automator capability matrix (epic #197 / issue #198).
 *
 * The three clients intentionally do **not** share a single Kotlin interface: the agent crosses a
 * reflection/classloader boundary with its own DTOs, HTTP has JSON + status-code semantics, and
 * in-process returns live [dev.sebastiano.spectre.core.AutomatorNode] graphs. Parity is enforced by
 * a shared **contract-test corpus** driven against each client.
 */
public enum class AutomatorTransport {
    /** Same-JVM [dev.sebastiano.spectre.core.ComposeAutomator]. */
    InProcess,

    /** [dev.sebastiano.spectre.server.HttpComposeAutomator] over HTTP. */
    Http,

    /** [dev.sebastiano.spectre.agent.AttachedAutomator] over the agent wire. */
    Agent,
}

/**
 * Platform / environment prerequisites for a matrix cell — OS name alone is not enough (headless vs
 * Xvfb vs Wayland vs real desktop diverge for Robot and Compose).
 */
public enum class PlatformPrerequisite {
    /** No display or OS-specific capture required; pure JVM transport. */
    AnyJvm,

    /** Explicitly headless (no AWT display); query ops only. */
    Headless,

    /** Interactive macOS desktop (or CI runner with a real display session). */
    MacOsDesktop,

    /** Linux with Xvfb (or equivalent virtual X11). */
    LinuxXvfb,

    /** Linux Wayland session (portal / compositor-dependent). */
    LinuxWayland,

    /** Interactive Windows desktop. */
    WindowsDesktop,
}

/** Automator operations tracked in the capability matrix. */
public enum class AutomatorOperation {
    Windows,
    AllNodes,
    FindByTestTag,
    Click,
    TypeText,
    Screenshot,
    Capture,
    WindowIdentities,
    WaitForNode,
    WaitForVisualIdle,
    WaitForIdle,
    RegisterIdlingResource,
    WithTracing,
    DoubleClick,
    LongClick,
    Swipe,
    ScrollWheel,
    PressKey,
    FindByText,
    FindByContentDescription,
    FindByRole,
}

/**
 * Multi-state cell — deliberately not binary. Claimed [Supported] without executable evidence fails
 * the matrix evidence check.
 */
public enum class CellState {
    /** Claimed working; [CapabilityCell.evidence] must be non-empty and resolvable. */
    Supported,

    /** Works in some environments; not a 1.0 guarantee. */
    Experimental,

    /** Will not be offered on this transport (live JVM objects, etc.). */
    UnsupportedByDesign,

    /** Intended, but no CI task has executed the cell yet. */
    NotYetCiExecuted,
}

/**
 * Link from a *supported* cell to the test class / workflow that executes it.
 *
 * [sourcePath] is a repo-relative path that must exist (usually a `*Test.kt` file). [workflowPath]
 * when set must also exist under `.github/workflows/`.
 */
public data class CapabilityEvidence(
    public val id: String,
    public val description: String,
    public val sourcePath: String,
    public val workflowPath: String? = null,
    public val gradleTaskHint: String? = null,
)

/** One cell of the ops × transports × platforms matrix. */
public data class CapabilityCell(
    public val operation: AutomatorOperation,
    public val transport: AutomatorTransport,
    public val platform: PlatformPrerequisite,
    public val state: CellState,
    public val evidence: List<CapabilityEvidence> = emptyList(),
    public val rationale: String? = null,
)
