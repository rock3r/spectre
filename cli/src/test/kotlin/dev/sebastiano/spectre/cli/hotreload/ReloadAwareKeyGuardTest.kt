package dev.sebastiano.spectre.cli.hotreload

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReloadAwareKeyGuardTest {
    @Test
    fun `before any tree keys pass through`() {
        val guard = ReloadAwareKeyGuard()
        assertTrue(guard.accepts("any-key"))
    }

    @Test
    fun `after tree only issued keys are accepted and finds union`() {
        val guard = ReloadAwareKeyGuard()
        guard.rememberIssuedKeys(listOf("a", "b"))
        guard.rememberIssuedKeys(listOf("c"))
        assertTrue(guard.accepts("a"))
        assertTrue(guard.accepts("b"))
        assertTrue(guard.accepts("c"))
        assertFalse(guard.accepts("d"))
    }

    @Test
    fun `reload invalidates until a new tree is issued`() {
        val guard = ReloadAwareKeyGuard()
        guard.rememberIssuedKeys(listOf("pre-reload"))
        guard.onReload()
        assertTrue(guard.isInvalidated())
        assertFalse(guard.accepts("pre-reload"))
        assertFalse(guard.accepts("anything"))
        guard.rememberIssuedKeys(listOf("post-reload"))
        assertFalse(guard.isInvalidated())
        assertTrue(guard.accepts("post-reload"))
        assertFalse(guard.accepts("pre-reload"))
    }
}
