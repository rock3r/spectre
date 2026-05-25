package dev.sebastiano.spectre.agent.spike;

import com.sun.tools.attach.VirtualMachine;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manual diagnostic helper: loads the Spectre agent runtime JAR into a running JVM and exits.
 *
 * <p>This source set is intentionally separate from {@code main}: the helper backs the
 * {@code :agent:attachSpike} Gradle task, but it is not packaged in the published
 * {@code spectre-agent} artifact.
 */
public final class AttachSpike {
    private static final int EXIT_USAGE = 64; // sysexits.h EX_USAGE

    private AttachSpike() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: AttachSpike <pid> <agentJarPath>");
            System.exit(EXIT_USAGE);
        }
        String pid = args[0];
        Path jarPath = Path.of(args[1]).toAbsolutePath();
        if (!Files.isRegularFile(jarPath)) {
            throw new IllegalArgumentException("Agent JAR not found at " + jarPath);
        }

        System.err.println("[attach-spike] attaching to pid=" + pid);
        System.err.println("[attach-spike] agent=" + jarPath);
        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            vm.loadAgent(jarPath.toString());
            System.err.println(
                    "[attach-spike] loadAgent returned successfully -- see the target JVM's "
                            + "stderr for the agent's diagnostic output.");
        } finally {
            vm.detach();
        }
    }
}
