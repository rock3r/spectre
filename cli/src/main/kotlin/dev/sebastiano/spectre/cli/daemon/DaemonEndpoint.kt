package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Path

/** Canonical per-user endpoint shared by daemon clients and launchers. */
public object DaemonEndpoint {
    /** Returns the daemon socket path below the supplied user home directory. */
    public fun defaultSocketPath(userHome: Path = Path.of(System.getProperty("user.home"))): Path =
        userHome.resolve(".spectre").resolve("daemon").resolve("daemon.sock")
}
