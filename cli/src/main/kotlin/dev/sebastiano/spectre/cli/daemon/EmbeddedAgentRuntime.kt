package dev.sebastiano.spectre.cli.daemon

import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.security.MessageDigest
import java.util.Locale

/** Installs the shaded agent runtime at a content-addressed path for daemon use. */
internal object EmbeddedAgentRuntime {
    private const val CACHE_HASH_CHARS: Int = 16

    fun install(
        home: Path = Path.of(System.getProperty("user.home")),
        resource: () -> InputStream? = {
            EmbeddedAgentRuntime::class.java.getResourceAsStream("/spectre/agent-runtime.jar")
        },
    ): Path? {
        val bytes = resource()?.use(InputStream::readBytes) ?: return null
        val directory = home.resolve(".spectre").resolve("runtime")
        // Keep the extracted filename short. HotSpot's Windows attach implementation can fail
        // to open an otherwise valid agent jar when the full path is long; the content hash still
        // makes this immutable and collision-resistant for the local cache.
        val destination = directory.resolve("agent-${bytes.sha256().take(CACHE_HASH_CHARS)}.jar")
        if (Files.isRegularFile(destination)) {
            grantCurrentUserReadAccess(destination)
            requireCurrentUserReadAccess(destination)
            return destination
        }

        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".agent-runtime-", ".jar")
        try {
            Files.write(temporary, bytes)
            moveWithoutReplacing(temporary, destination)
        } finally {
            Files.deleteIfExists(temporary)
        }
        grantCurrentUserReadAccess(destination)
        requireCurrentUserReadAccess(destination)
        return destination
    }

    private fun requireCurrentUserReadAccess(path: Path) {
        check(Files.isRegularFile(path) && Files.isReadable(path)) {
            "Extracted agent runtime is not readable: $path"
        }
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
        val user = currentUser(path)
        check(
            view.acl.any {
                it.type() == AclEntryType.ALLOW &&
                    it.principal() == user &&
                    it.permissions().containsAll(REQUIRED_READ_PERMISSIONS)
            }
        ) {
            "Extracted agent runtime has no persistent read access for $user: $path"
        }
    }

    private fun grantCurrentUserReadAccess(path: Path) {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
        val user = currentUser(path)
        if (
            view.acl.any {
                it.type() == AclEntryType.ALLOW &&
                    it.principal() == user &&
                    it.permissions().containsAll(REQUIRED_READ_PERMISSIONS)
            }
        ) {
            return
        }
        val entry =
            AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(user)
                .setPermissions(REQUIRED_READ_PERMISSIONS)
                .setFlags(emptySet<AclEntryFlag>())
                .build()
        view.acl = listOf(entry) + view.acl
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

    private fun currentUser(path: Path) =
        path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(
            System.getProperty("user.name")
        )

    private val REQUIRED_READ_PERMISSIONS: Set<AclEntryPermission> =
        setOf(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.SYNCHRONIZE,
        )

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") {
            "%02x".format(Locale.ROOT, it)
        }
}
