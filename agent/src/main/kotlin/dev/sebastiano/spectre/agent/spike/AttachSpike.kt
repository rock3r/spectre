package dev.sebastiano.spectre.agent.spike

import com.sun.tools.attach.VirtualMachine
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Manual diagnostic helper: loads the Spectre agent JAR into a running JVM and exits.
 *
 * Distinct from the public [dev.sebastiano.spectre.agent.AgentAttach] API in two ways:
 * - It doesn't pass a UDS path, so the agent runs its **spike mode**: bootstraps the classloader
 *   path, constructs a `ComposeAutomator`, prints `getWindows().size` to the target's stderr, and
 *   exits the `agentmain` thread. No IPC server starts.
 * - It's invoked via the `:agent:attachSpike` Gradle task rather than from Kotlin code.
 *
 * Use this when you want to **verify Spectre is on a target JVM's classpath** without setting up an
 * IPC client — e.g. when debugging "the agent attached but couldn't find `ComposeAutomator`"
 * failure modes. For real automation, use `AgentAttach.attach(pid)`.
 *
 * Usage (from the worktree root):
 * ```
 * # Terminal A: start a Spectre-instrumented app
 * ./gradlew :sample-desktop:run
 * jps | grep MainKt          # note the PID
 *
 * # Terminal B: run the diagnostic spike against it
 * ./gradlew :agent:attachSpike -Ppid=<pid>
 * # → target's stderr shows `[spectre-agent] ... getWindows().size = N`
 * ```
 */
@ExperimentalSpectreAgentApi
public object AttachSpike {
    @JvmStatic
    public fun main(args: Array<String>) {
        if (args.size != 2) {
            System.err.println("Usage: AttachSpike <pid> <agentJarPath>")
            exitProcess(EXIT_USAGE)
        }
        val pid = args[0]
        val jarPath = Path.of(args[1]).toAbsolutePath()
        require(Files.isRegularFile(jarPath)) { "Agent JAR not found at $jarPath" }

        System.err.println("[attach-spike] attaching to pid=$pid")
        System.err.println("[attach-spike] agent=$jarPath")
        val vm = VirtualMachine.attach(pid)
        try {
            vm.loadAgent(jarPath.toString())
            System.err.println(
                "[attach-spike] loadAgent returned successfully — see the target JVM's " +
                    "stderr for the agent's diagnostic output."
            )
        } finally {
            vm.detach()
        }
    }

    private const val EXIT_USAGE = 64 // sysexits.h EX_USAGE
}
