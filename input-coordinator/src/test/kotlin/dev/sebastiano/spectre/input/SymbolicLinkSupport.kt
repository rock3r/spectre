package dev.sebastiano.spectre.input

import java.nio.file.FileSystemException
import java.nio.file.Files
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Runs [createLink] and reports whether the host can create symbolic links.
 *
 * Two failures mean "this host will not create symbolic links", and both must be treated the same
 * way:
 * - [FileSystemException] — Windows refuses with "A required privilege is not held by the client"
 *   unless Developer Mode is on or the process holds `SeCreateSymbolicLinkPrivilege`.
 * - [UnsupportedOperationException] — the file system has no concept of symbolic links at all.
 *
 * Every other failure means the probe itself is broken, so it propagates instead of being reported
 * as a missing platform feature.
 */
internal fun canCreateSymbolicLinks(createLink: () -> Unit): Boolean =
    try {
        createLink()
        true
    } catch (_: FileSystemException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    }

/**
 * Skips the calling test when the host cannot create symbolic links.
 *
 * Windows without Developer Mode cannot create them, and a test that fails there turns `./gradlew
 * check` — the documented pre-push gate — permanently red for those contributors. The assertions
 * this guards still run on every host that can create links.
 */
internal fun assumeCanCreateSymbolicLinks() {
    val probeDirectory = Files.createTempDirectory("spectre-symlink-probe")
    val link = probeDirectory.resolve("link")
    try {
        val supported = canCreateSymbolicLinks {
            Files.createSymbolicLink(link, probeDirectory.resolve("target-need-not-exist"))
        }
        assumeTrue(
            supported,
            "Host cannot create symbolic links (on Windows, enable Developer Mode or grant " +
                "SeCreateSymbolicLinkPrivilege). Symbolic-link guards stay covered on hosts that " +
                "can create links.",
        )
    } finally {
        Files.deleteIfExists(link)
        Files.deleteIfExists(probeDirectory)
    }
}
