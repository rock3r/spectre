package dev.sebastiano.spectre.input

import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SymbolicLinkSupportTest {

    @Test
    fun `reports supported when the probe link is created`() {
        assertTrue(canCreateSymbolicLinks {})
    }

    @Test
    fun `reports unsupported when Windows refuses the link without the privilege`() {
        // #467: this is what a Windows host without Developer Mode actually throws. The guard used
        // to catch only UnsupportedOperationException, so this escaped and errored the test.
        assertFalse(
            canCreateSymbolicLinks {
                throw FileSystemException(
                    "link",
                    null,
                    "A required privilege is not held by the client",
                )
            }
        )
    }

    @Test
    fun `reports unsupported when the refusal is an access denial`() {
        assertFalse(canCreateSymbolicLinks { throw AccessDeniedException("link") })
    }

    @Test
    fun `reports unsupported when the file system has no symbolic links`() {
        assertFalse(canCreateSymbolicLinks { throw UnsupportedOperationException() })
    }

    @Test
    fun `lets an unrelated failure propagate instead of skipping silently`() {
        assertFailsWith<IllegalStateException> {
            canCreateSymbolicLinks { error("the probe fixture itself is broken") }
        }
    }
}
