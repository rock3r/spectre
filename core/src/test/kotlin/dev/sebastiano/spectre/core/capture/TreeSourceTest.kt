package dev.sebastiano.spectre.core.capture

import dev.sebastiano.spectre.core.InternalSpectreApi
import dev.sebastiano.spectre.core.snapshotPathSegment
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalSpectreApi::class)
class TreeSourceTest {
    @Test
    fun `fake source can supply a snapshot to the capture mapping path`() {
        val source = TreeSource { _ ->
            listOf(
                CaptureNodeSnapshot(
                    key = "w0/root",
                    parentKey = null,
                    id = "root",
                    testTag = "root",
                    text = null,
                    texts = emptyList(),
                    editableText = null,
                    contentDescription = null,
                    role = null,
                    platformRole = null,
                    states = listOf("focusable"),
                    value = null,
                    enabled = true,
                    clickable = false,
                    focused = false,
                    selected = false,
                    boundsScreen = Rectangle(0, 0, 10, 10),
                )
            )
        }

        assertEquals("w0/root", source.snapshot(emptyList()).single().key)
    }

    @Test
    fun `key segment escapes ids and falls back to index for missing or duplicate ids`() {
        assertEquals("a~1b~0c", snapshotPathSegment("a/b~c", listOf("a/b~c"), 0))
        assertEquals("1", snapshotPathSegment("same", listOf("same", "same"), 1))
        assertEquals("2", snapshotPathSegment(null, listOf("id", null, null), 2))
    }
}
