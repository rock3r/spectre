package dev.sebastiano.spectre.cli.daemon;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

public final class ForkedLogWriter {
    public static void main(String[] args) throws Exception {
        Path log = Path.of(args[1]);
        if (args[0].equals("launch")) {
            new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
                    "-cp", System.getProperty("java.class.path"), ForkedLogWriter.class.getName(),
                    "write", log.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return;
        }

        try (OutputStream output = Files.newOutputStream(log, StandardOpenOption.APPEND)) {
            for (int rotation = 0; rotation < 2; rotation++) {
                output.write("x".repeat(512).getBytes(StandardCharsets.US_ASCII));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (Files.size(log) != 128) {
                    if (System.nanoTime() >= deadline) {
                        throw new AssertionError("Timed out waiting for log rotation");
                    }
                    Thread.sleep(5);
                }
            }
            output.write("FINAL-RETAINED\n".getBytes(StandardCharsets.US_ASCII));
        }
        Files.createFile(log.resolveSibling("done"));
    }
}
