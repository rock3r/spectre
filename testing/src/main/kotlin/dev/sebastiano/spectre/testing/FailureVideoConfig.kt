package dev.sebastiano.spectre.testing

import java.awt.Rectangle
import java.nio.file.Path

/** Capture boundary for automatic failure video. Window-only is the privacy-safe default. */
public sealed interface FailureVideoScope {
    public data object Window : FailureVideoScope

    public data class Region(public val bounds: Rectangle) : FailureVideoScope

    public data class Display(public val index: Int = 0) : FailureVideoScope
}

/** Configuration for recording video during a Spectre-driven JUnit test (#206). */
public class FailureVideoConfig
private constructor(
    public val policy: FailureVideoPolicy,
    public val reportsRoot: Path,
    public val attemptIndex: Int?,
    public val invocationId: String?,
    public val scope: FailureVideoScope,
) {
    public constructor() :
        this(
            FailureVideoPolicy.Off,
            FailureArtifactPaths.defaultReportsRoot(),
            null,
            null,
            FailureVideoScope.Window,
        )

    /** Retains the original four-argument constructor and default-argument constructor JVM ABI. */
    public constructor(
        policy: FailureVideoPolicy = FailureVideoPolicy.Off,
        reportsRoot: Path = FailureArtifactPaths.defaultReportsRoot(),
        attemptIndex: Int? = null,
        invocationId: String? = null,
    ) : this(policy, reportsRoot, attemptIndex, invocationId, FailureVideoScope.Window)

    init {
        require(attemptIndex == null || attemptIndex >= 1) {
            "attemptIndex must be null or >= 1 (1-based), was $attemptIndex"
        }
    }

    public operator fun component1(): FailureVideoPolicy = policy

    public operator fun component2(): Path = reportsRoot

    public operator fun component3(): Int? = attemptIndex

    public operator fun component4(): String? = invocationId

    /** Retains the original copy/copy$default ABI while carrying the configured scope forward. */
    public fun copy(
        policy: FailureVideoPolicy = this.policy,
        reportsRoot: Path = this.reportsRoot,
        attemptIndex: Int? = this.attemptIndex,
        invocationId: String? = this.invocationId,
    ): FailureVideoConfig =
        FailureVideoConfig(policy, reportsRoot, attemptIndex, invocationId, scope.copyDefensively())

    public fun withRegion(bounds: Rectangle): FailureVideoConfig =
        FailureVideoConfig(
            policy,
            reportsRoot,
            attemptIndex,
            invocationId,
            FailureVideoScope.Region(Rectangle(bounds)),
        )

    public fun withDisplay(index: Int = 0): FailureVideoConfig {
        require(index >= 0) { "display index must be >= 0, was $index" }
        return FailureVideoConfig(
            policy,
            reportsRoot,
            attemptIndex,
            invocationId,
            FailureVideoScope.Display(index),
        )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is FailureVideoConfig &&
                policy == other.policy &&
                reportsRoot == other.reportsRoot &&
                attemptIndex == other.attemptIndex &&
                invocationId == other.invocationId &&
                scope == other.scope

    override fun hashCode(): Int {
        var result = policy.hashCode()
        result = 31 * result + reportsRoot.hashCode()
        result = 31 * result + (attemptIndex ?: 0)
        result = 31 * result + (invocationId?.hashCode() ?: 0)
        return 31 * result + scope.hashCode()
    }

    override fun toString(): String =
        "FailureVideoConfig(policy=$policy, reportsRoot=$reportsRoot, attemptIndex=$attemptIndex, " +
            "invocationId=$invocationId, scope=$scope)"
}

private fun FailureVideoScope.copyDefensively(): FailureVideoScope =
    when (this) {
        FailureVideoScope.Window -> this
        is FailureVideoScope.Display -> copy()
        is FailureVideoScope.Region -> copy(bounds = Rectangle(bounds))
    }
