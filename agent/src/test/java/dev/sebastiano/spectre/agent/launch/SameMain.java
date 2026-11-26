package dev.sebastiano.spectre.agent.launch;

import java.nio.file.Files;
import java.nio.file.Path;

public final class SameMain {
    public static void main(String[] args) throws Exception {
        Path ready = Path.of(args[0]);
        Path release = ready.resolveSibling("fixture.release");
        String marker = System.getProperty(args[2], "");
        Path temporary = ready.resolveSibling("fixture.ready.tmp");
        Files.writeString(temporary, ProcessHandle.current().pid() + "\n" + marker + "\n");
        Files.move(temporary, ready);
        ProcessHandle owner = ProcessHandle.of(Long.parseLong(args[1])).orElse(null);
        while (owner != null && owner.isAlive() && !Files.exists(release)) {
            Thread.sleep(100);
        }
    }
}
