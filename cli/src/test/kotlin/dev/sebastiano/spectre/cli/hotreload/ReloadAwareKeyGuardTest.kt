package dev.sebastiano.spectre.cli.hotreload

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.RectDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSpectreAgentApi::class)
class ReloadAwareKeyGuardTest {
    @Test
    fun `before any tree unstamped keys resolve for dispatch`() {
        val guard = ReloadAwareKeyGuard()
        assertEquals("any-key", guard.resolveForDispatch("any-key"))
    }

    @Test
    fun `issued keys are stamped and resolve to raw keys`() {
        val guard = ReloadAwareKeyGuard()
        val issued = guard.issueNodes(listOf(sample("a"), sample("b")))
        assertEquals("g0:a", issued[0].key)
        assertEquals("g0:b", issued[1].key)
        assertEquals("a", guard.resolveForDispatch("g0:a"))
        assertNull(guard.resolveForDispatch("g0:c"))
    }

    @Test
    fun `find unions keys within a generation`() {
        val guard = ReloadAwareKeyGuard()
        guard.issueNodes(listOf(sample("a")))
        guard.issueNodes(listOf(sample("b")))
        assertEquals("a", guard.resolveForDispatch("g0:a"))
        assertEquals("b", guard.resolveForDispatch("g0:b"))
    }

    @Test
    fun `reload invalidates prior generation stamps`() {
        val guard = ReloadAwareKeyGuard()
        val pre = guard.issueNodes(listOf(sample("pre"))).single().key
        assertEquals("g0:pre", pre)
        guard.onReload()
        assertTrue(guard.isInvalidated())
        assertNull(guard.resolveForDispatch(pre))
        assertNull(guard.resolveForDispatch("unstamped"))
        val post = guard.issueNodes(listOf(sample("post"))).single().key
        assertEquals("g1:post", post)
        assertEquals("post", guard.resolveForDispatch(post))
        assertNull(guard.resolveForDispatch(pre))
    }

    @Test
    fun `unstamp parses generation stamps`() {
        assertEquals(2L to "main:0:1", ReloadAwareKeyGuard.unstamp("g2:main:0:1"))
        assertNull(ReloadAwareKeyGuard.unstamp("main:0:1"))
    }

    private fun sample(key: String): NodeSnapshotDto =
        NodeSnapshotDto(
            key = key,
            testTag = null,
            texts = emptyList(),
            role = null,
            contentDescription = null,
            isVisible = true,
            bounds = RectDto(0, 0, 1, 1),
        )
}
