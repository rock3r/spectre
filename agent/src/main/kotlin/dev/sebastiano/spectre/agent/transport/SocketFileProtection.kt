package dev.sebastiano.spectre.agent.transport

import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet

/**
 * Per-filesystem owner-only protection for the agent's UDS directory and socket file.
 *
 * The trust model (see docs/SECURITY.md) is same-user, local-only: the private per-attach directory
 * and the socket inside it must be reachable only by the OS user that created them. That intent
 * maps to POSIX modes `0700`/`0600` on Linux/macOS and to an owner-only ACL on Windows/NTFS (POSIX
 * modes are meaningless on NTFS). This seam keeps [IpcServer] free of the platform branch and
 * gives #195's ACL work a single home.
 *
 * Selected by [forPath] from the filesystem's supported attribute views: `posix` present →
 * [PosixSocketFileProtection]; otherwise [WindowsAclSocketFileProtection].
 */
internal sealed interface SocketFileProtection {
    /**
     * If [udsPath]'s parent directory does not exist, create it (with owner-only protection) and
     * return it so the caller can clean it up. Returns `null` when the parent already exists —
     * Spectre only tightens directories it creates itself.
     */
    @Throws(IOException::class)
    fun createMissingParentDirectory(udsPath: Path): Path? {
        val parent = udsPath.parent ?: return null
        if (Files.exists(parent)) return null
        createProtectedDirectory(parent)
        return parent
    }

    /** Create [dir] (and any missing ancestors) with owner-only protection. */
    @Throws(IOException::class) fun createProtectedDirectory(dir: Path)

    /**
     * Tighten the freshly-bound socket file at [udsPath] to owner-only access. Called immediately
     * after `bind`, since UDS file creation otherwise respects the ambient umask / inherited ACL.
     */
    @Throws(IOException::class) fun protectSocketFile(udsPath: Path)

    companion object {
        /** Select the protection strategy for the filesystem backing [path]. */
        fun forPath(path: Path): SocketFileProtection =
            forViews(path.fileSystem.supportedFileAttributeViews())

        /**
         * Selection seam (exposed for tests): POSIX modes when the filesystem supports them, else
         * Windows ACLs.
         */
        fun forViews(supportedViews: Set<String>): SocketFileProtection =
            if ("posix" in supportedViews) PosixSocketFileProtection
            else WindowsAclSocketFileProtection
    }
}

/** POSIX protection: directory mode `0700`, socket mode `0600`. */
internal object PosixSocketFileProtection : SocketFileProtection {
    override fun createProtectedDirectory(dir: Path) {
        try {
            Files.createDirectories(
                dir,
                PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY_PERMS),
            )
        } catch (ex: UnsupportedOperationException) {
            throw IOException(
                "Filesystem at $dir doesn't support POSIX permissions; refusing to create " +
                    "an agent UDS parent without 0700 access control.",
                ex,
            )
        }
    }

    override fun protectSocketFile(udsPath: Path) {
        // UDS file creation respects the process umask, which on common defaults (022) leaves the
        // socket group/world-readable — broader than the local-only trust model claims. Setting
        // the permissions immediately after bind closes that gap.
        try {
            Files.setPosixFilePermissions(udsPath, OWNER_ONLY_PERMS)
        } catch (ex: UnsupportedOperationException) {
            throw IOException(
                "Filesystem at $udsPath doesn't support POSIX permissions; refusing to expose " +
                    "a UDS without 0600 access control.",
                ex,
            )
        } catch (ex: FileSystemException) {
            throw IOException(
                "Failed to set 0600 permissions on UDS at $udsPath: ${ex.message}",
                ex,
            )
        }
    }
}

/**
 * Windows protection: an owner-only ACL, mirroring POSIX `0700`/`0600` on NTFS.
 *
 * Replacing a path's ACL with a single explicit ALLOW ACE for the owner (full control, no
 * inheritance flags) collapses the DACL to exactly that entry and drops all inherited ACEs —
 * verified on NTFS, where a default `%TEMP%` subdirectory otherwise inherits SYSTEM,
 * Administrators, and group ACEs. `Administrators`/`SYSTEM` retain access at the OS level
 * regardless (they can take ownership of anything); that residual exposure is an accepted risk
 * documented in docs/SECURITY.md.
 */
internal object WindowsAclSocketFileProtection : SocketFileProtection {
    override fun createProtectedDirectory(dir: Path) {
        Files.createDirectories(dir)
        setOwnerOnlyAcl(dir)
    }

    override fun protectSocketFile(udsPath: Path) {
        setOwnerOnlyAcl(udsPath)
    }

    private fun setOwnerOnlyAcl(path: Path) {
        val view =
            Files.getFileAttributeView(path, AclFileAttributeView::class.java)
                ?: throw IOException(
                    "Filesystem at $path doesn't support ACLs; refusing to expose a UDS without " +
                        "an owner-only ACL."
                )
        val ownerOnly =
            AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(view.owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                .build()
        try {
            view.acl = listOf(ownerOnly)
        } catch (ex: IOException) {
            throw IOException("Failed to set an owner-only ACL on $path: ${ex.message}", ex)
        }
    }
}

private val OWNER_ONLY_PERMS =
    PosixFilePermissions.fromString("rw-------").also {
        // Sanity assertion: the parsed set should be exactly OWNER_READ + OWNER_WRITE. Cheap
        // self-check so a typo here surfaces at module-init time rather than only when a peer
        // tries to connect.
        require(it == setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) {
            "Expected OWNER_ONLY_PERMS to be 0600 (rw-------), got $it"
        }
    }

private val OWNER_ONLY_DIRECTORY_PERMS =
    PosixFilePermissions.fromString("rwx------").also {
        require(
            it ==
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                )
        ) {
            "Expected OWNER_ONLY_DIRECTORY_PERMS to be 0700 (rwx------), got $it"
        }
    }
