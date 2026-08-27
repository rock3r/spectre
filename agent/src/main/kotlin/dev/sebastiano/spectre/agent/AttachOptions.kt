package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.transport.MAX_FRAME_BYTES_CEILING
import dev.sebastiano.spectre.agent.transport.MIN_MAX_FRAME_BYTES
import dev.sebastiano.spectre.agent.transport.UdsPathLimits
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/**
 * Configuration for [AgentAttach.attach].
 *
 * Lookup order for the agent JAR (when [agentJarPath] isn't explicitly set):
 * 1. The system property `dev.sebastiano.spectre.agent.runtimeJar` (used by Spectre's own
 *    integration tests so they don't have to know the absolute build path).
 * 2. A `spectre-agent-runtime-<version>.jar` or `agent-runtime-<version>.jar` entry on the
 *    attacher's `java.class.path`.
 * 3. `<spectre-checkout>/agent-runtime/build/libs/agent-runtime-<version>.jar` only when the
 *    current working directory is inside a Spectre source checkout (not for published consumers).
 *
 * The fallback throws when none of the candidates exist, with a message pointing the user at
 * `./gradlew :agent-runtime:jar` to produce the JAR.
 *
 * **JEP 451 flag detection** is **not** implemented yet. The previous draft tried to read a
 * `jdk.internal.vm.dynamic.agent.loading` system property that doesn't actually exist;
 * `VirtualMachine.getSystemProperties()` returns system properties, not HotSpot VM flags. Until we
 * wire up a reliable preflight (likely through `HotSpotDiagnosticMXBean` via the Attach API's local
 * management agent), we rely on the JVM's own JEP 451 stderr warning to tell users when the flag is
 * missing.
 *
 * @property agentJarPath loadable agent runtime JAR to pass to `VirtualMachine.loadAgent`.
 * @property udsPath Unix Domain Socket path the agent should bind on (must NOT exist already;
 *   defaults to `<base>/sp-a-<pid>-<8char-uuid>/agent.sock` with a fresh UUID per `attach()` call
 *   so concurrent attaches don't collide — see [defaultUdsPath] for how `<base>` is picked). If you
 *   override this with a path under an existing directory, you own that parent directory's
 *   permissions; Spectre only tightens directories it creates itself. The path must fit the
 *   platform's `sockaddr_un.sun_path` budget (102 bytes on macOS, 106 elsewhere); `attach` rejects
 *   longer paths up front with [UdsPathTooLongException].
 * @property attachTimeoutMs how long to wait for the agent's bootstrap + IPC server to come up.
 * @property maxFrameBytes IPC frame write budget the injected agent should adopt. `null` (default)
 *   forwards this process's own budget, so a daemon started with `SPECTRE_MAX_FRAME_BYTES` or
 *   `--max-frame-bytes` propagates it to every JVM it injects. The target cannot read the
 *   attacher's environment, and it is the side that writes screenshot frames, so this is the only
 *   channel that reaches it.
 * @property inputCoordination whether the attached target coordinates its use of the shared
 *   desktop. `null` (default) resolves [AttachInputCoordination.PROPERTY] on this JVM, which itself
 *   defaults to [AttachInputCoordination.Required] — so leaving this alone coordinates, and so does
 *   every value of that property except the one word that opts out. Setting it explicitly wins over
 *   the property in **both** directions: an integration that pins `Required` cannot be unpinned by
 *   a property left lying around in the environment. Read [AttachInputCoordination] before reaching
 *   for [AttachInputCoordination.Disabled] — it is deliberate, it is announced on stderr, and it
 *   costs you the guarantee that nothing else is driving this desktop.
 */
@ExperimentalSpectreAgentApi
public data class AttachOptions(
    public val agentJarPath: Path? = null,
    public val udsPath: Path? = null,
    public val attachTimeoutMs: Long = DEFAULT_ATTACH_TIMEOUT_MS,
    public val maxFrameBytes: Int? = null,
    public val inputCoordination: AttachInputCoordination? = null,
) {
    init {
        // The agent logs and ignores a budget it cannot apply, so an unusable value here would let
        // attach() report success while the target silently kept its own and later rejected
        // captures this caller sized for. Fail at the mistake instead.
        if (maxFrameBytes != null) {
            require(maxFrameBytes >= MIN_MAX_FRAME_BYTES) {
                "maxFrameBytes=$maxFrameBytes is below the $MIN_MAX_FRAME_BYTES-byte minimum; " +
                    "a budget that small cannot carry the protocol's own frames"
            }
            require(maxFrameBytes <= MAX_FRAME_BYTES_CEILING) {
                "maxFrameBytes=$maxFrameBytes exceeds the frame ceiling " +
                    "$MAX_FRAME_BYTES_CEILING; readers would refuse frames that large"
            }
        }
    }

    public companion object {
        public const val DEFAULT_ATTACH_TIMEOUT_MS: Long = 5_000

        /**
         * Default UDS path: `<base>/sp-a-<pid>-<8char-uuid>/agent.sock`.
         *
         * `<base>` is the first entry of [udsBaseDirCandidates] whose resulting path fits the
         * platform's `sockaddr_un.sun_path` budget (~104 bytes on macOS, ~108 on Linux and
         * Windows). Overflowing that budget makes the agent's `bind` fail inside the target JVM,
         * which the attacher only sees as "agent failed to initialize" (#442) — so the choice is
         * made here, where a bad outcome can still be reported clearly.
         *
         * @throws UdsPathTooLongException when no candidate base directory yields a short enough
         *   path. Pass [AttachOptions.udsPath] explicitly to recover.
         */
        public fun defaultUdsPath(targetPid: Long): Path {
            val shortUuid = UUID.randomUUID().toString().take(SHORT_UUID_LENGTH)
            val candidates =
                udsBaseDirCandidates(
                    osName = System.getProperty("os.name").orEmpty(),
                    tmpDir = System.getProperty("java.io.tmpdir").orEmpty(),
                    localAppData = System.getenv(LOCAL_APP_DATA_ENV),
                    userHome = System.getProperty("user.home").orEmpty(),
                )
            return selectUdsPath(candidates, "sp-a-${targetPid}-${shortUuid}")
        }

        /**
         * Ordered base-directory candidates for the default UDS path, most preferred first.
         * Extracted for testing — `java.nio.file.Path` construction is filesystem-specific, so the
         * selection logic is validated as strings here.
         * - **Linux/macOS**: hard-coded `/tmp` (symlinked to `/private/tmp` on macOS), and nothing
         *   else. `java.io.tmpdir` resolves to a much longer `/var/folders/...` on macOS and can
         *   blow past the `sun_path` limit.
         * - **Windows**: [tmpDir] (`%TEMP%`, per-user ACL'd) first, so a harness that points the
         *   JVM at its own scratch directory keeps it. `/tmp` is meaningless on Windows —
         *   `Paths.get("/tmp", …)` yields the drive-relative `\tmp\…`, outside the protected
         *   per-user temp area. Nothing constrains how deep `%TEMP%` is, though: Bazel points it at
         *   a per-test execroot directory deep enough that appending the per-attach directory and
         *   socket name overflows `sun_path` (#442). The fallbacks reach the per-user temp
         *   directory without going through `java.io.tmpdir`, which is what harnesses rewrite.
         *
         * The Windows fallbacks join with a literal `\` rather than the host's separator: the
         * branch only ever runs on Windows, and hard-coding it keeps this function testable
         * everywhere.
         */
        internal fun udsBaseDirCandidates(
            osName: String,
            tmpDir: String,
            localAppData: String?,
            userHome: String,
        ): List<String> {
            if (!osName.startsWith("Windows", ignoreCase = true)) {
                return listOf(POSIX_UDS_BASE_DIR)
            }
            return listOfNotNull(
                    tmpDir.takeIf { it.isNotBlank() },
                    localAppData?.takeIf { it.isNotBlank() }?.let { "$it\\Temp" },
                    userHome.takeIf { it.isNotBlank() }?.let { "$it\\AppData\\Local\\Temp" },
                )
                .distinct()
        }

        /**
         * Builds `<base>/[perAttachDir]/agent.sock` for each of [baseCandidates] in order and
         * returns the first one that fits the platform's `sun_path` budget.
         *
         * Each candidate is resolved to an absolute path *before* it is measured. A relative
         * `java.io.tmpdir` (`-Djava.io.tmpdir=tmp`) is legal and the JVM does not normalise it, so
         * measuring the short relative spelling would accept a candidate the target then binds as
         * `<cwd>/<base>/…` — over the limit, with the good fallback already passed over.
         *
         * @throws UdsPathTooLongException when every candidate overflows the budget.
         */
        internal fun selectUdsPath(baseCandidates: List<String>, perAttachDir: String): Path {
            val paths = baseCandidates.map {
                Paths.get(it, perAttachDir, SOCKET_FILE_NAME).toAbsolutePath()
            }
            return paths.firstOrNull { !UdsPathLimits.exceedsLimit(it) }
                ?: throw UdsPathTooLongException(paths)
        }

        private const val POSIX_UDS_BASE_DIR: String = "/tmp"
        private const val LOCAL_APP_DATA_ENV: String = "LOCALAPPDATA"
        private const val SOCKET_FILE_NAME: String = "agent.sock"
        private const val SHORT_UUID_LENGTH = 8
    }
}
