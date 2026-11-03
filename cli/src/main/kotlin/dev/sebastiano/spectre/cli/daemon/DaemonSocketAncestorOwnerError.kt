package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Path

/**
 * The error for a daemon socket ancestor owned by someone other than `root` or the current user.
 *
 * An owner shown as `nobody` (uid 65534) is the kernel's overflow uid. Inside a Linux user
 * namespace, such as a rootless container or sandbox, every host user that isn't mapped into the
 * namespace appears that way, host `root` included. Spectre can't tell who really owns such a
 * directory, so the check stays fail-closed and the message explains why.
 */
internal fun socketAncestorOwnerError(directory: Path, owner: String, currentUser: String): String =
    buildString {
        append("Daemon socket ancestor $directory is owned by $owner; ")
        append("it must be owned by root or the current user ($currentUser).")
        if (owner == OVERFLOW_OWNER_NAME || owner == OVERFLOW_OWNER_UID) {
            append(" In a rootless container or other Linux user namespace, an owner of ")
            append("$owner usually means the directory belongs to a host user that is not mapped ")
            append("into the namespace, such as host root. Spectre can't verify who owns it, so ")
            append("it won't place the daemon socket below it.")
        }
        append(SOCKET_DIRECTORY_HINT)
        if (owner == OVERFLOW_OWNER_NAME || owner == OVERFLOW_OWNER_UID) {
            append(" This can't help when every ancestor path runs through a directory owned by ")
            append("$owner, including / itself.")
        }
    }

/** The error for a daemon socket ancestor that is group- or world-writable without a sticky bit. */
internal fun socketAncestorWritableError(directory: Path): String =
    "Daemon socket ancestor $directory must not be group or world writable." + SOCKET_DIRECTORY_HINT

private val SOCKET_DIRECTORY_HINT =
    " To use another directory, set ${DaemonEndpoint.SOCKET_DIRECTORY_ENVIRONMENT_VARIABLE} " +
        "to an absolute directory you own whose ancestors all pass this check, such as " +
        "\$XDG_RUNTIME_DIR or a directory under \$HOME."

private const val OVERFLOW_OWNER_NAME = "nobody"
private const val OVERFLOW_OWNER_UID = "65534"
