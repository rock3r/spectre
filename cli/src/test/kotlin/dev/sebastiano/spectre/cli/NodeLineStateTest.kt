package dev.sebastiano.spectre.cli

import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.ProgressRangeDto
import dev.sebastiano.spectre.agent.transport.RectDto
import kotlin.test.Test
import kotlin.test.assertEquals

/** Human-readable node lines for the node state that `NodeSnapshotDto` carries since #576. */
class NodeLineStateTest {

    @Test
    fun `node lines show toggle state, parent key, and clickable`() {
        val node =
            NodeSnapshotDto(
                key = "window-0:0:3",
                testTag = "mute",
                texts = emptyList(),
                role = "Checkbox",
                contentDescription = null,
                isVisible = true,
                bounds = RectDto(1, 2, 3, 4),
                isClickable = true,
                toggleableState = "Indeterminate",
                parentKey = "window-0:0:1",
            )

        assertEquals(
            "window-0:0:3 Checkbox tag=\"mute\" toggle=Indeterminate parent=window-0:0:1 " +
                "[clickable] @ (1,2 3x4)",
            formatNodeLine(node),
        )
    }

    @Test
    fun `node lines show progress value and range, with steps only for a stepped range`() {
        fun line(progress: ProgressRangeDto): String =
            formatNodeLine(
                NodeSnapshotDto(
                    key = "k",
                    testTag = null,
                    texts = emptyList(),
                    role = null,
                    contentDescription = null,
                    isVisible = true,
                    bounds = RectDto(0, 0, 0, 0),
                    progress = progress,
                )
            )

        assertEquals(
            "k Node progress=0.5/0..1 @ (0,0 0x0)",
            line(ProgressRangeDto(current = 0.5f, min = 0f, max = 1f)),
        )
        assertEquals(
            "k Node progress=25/0..100 steps=3 @ (0,0 0x0)",
            line(ProgressRangeDto(current = 25f, min = 0f, max = 100f, steps = 3)),
        )
        assertEquals(
            "k Node progress=0.25/-1.5..2 @ (0,0 0x0)",
            line(ProgressRangeDto(current = 0.25f, min = -1.5f, max = 2f)),
        )
    }
}
