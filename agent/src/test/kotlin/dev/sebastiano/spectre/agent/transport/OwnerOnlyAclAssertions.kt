package dev.sebastiano.spectre.agent.transport

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Asserts [path] carries exactly one ACE: an ALLOW full-control entry for the owner, with no
 * inheritance flags (i.e. inherited ACEs were dropped) — the NTFS mirror of POSIX 0700/0600.
 */
internal fun assertOwnerOnlyAcl(path: Path) {
    val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
    val acl = view.acl
    assertEquals(1, acl.size, "expected exactly one ACE (no inherited ACEs), got: $acl")
    val entry = acl.single()
    assertEquals(AclEntryType.ALLOW, entry.type())
    assertEquals(view.owner, entry.principal(), "the sole ACE must be the owner")
    assertEquals(
        AclEntryPermission.values().toSet(),
        entry.permissions(),
        "the owner must have full control",
    )
    assertTrue(
        entry.flags().isEmpty(),
        "the ACE must not carry inheritance flags: ${entry.flags()}",
    )
}
