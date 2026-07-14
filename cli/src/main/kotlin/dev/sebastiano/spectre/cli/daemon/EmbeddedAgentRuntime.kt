package dev.sebastiano.spectre.cli.daemon

import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.Locale

/** Installs the shaded agent runtime at a content-addressed path for daemon use. */
internal object EmbeddedAgentRuntime {
    fun install(
        home: Path = Path.of(System.getProperty("user.home")),
        resource: () -> InputStream? = {
            EmbeddedAgentRuntime::class.java.getResourceAsStream("/spectre/agent-runtime.jar")
        },
    ): Path? {
        val bytes = resource()?.use(InputStream::readBytes) ?: return null
        val directory = home.resolve(".spectre").resolve("runtime")
        val destination = directory.resolve("agent-runtime-${bytes.sha256()}.jar")
        if (Files.isRegularFile(destination)) return destination

        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".agent-runtime-", ".jar")
        try {
            Files.write(temporary, bytes)
            moveWithoutReplacing(temporary, destination)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return destination
    }

    private fun moveWithoutReplacing(source: Path, destination: Path) {
        try {
            try {
                Files.move(source, destination, ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, destination)
            }
        } catch (_: FileAlreadyExistsException) {
            // Another daemon startup installed the same content while this one was writing it.
        }
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") {
            "%02x".format(Locale.ROOT, it)
        }
}
