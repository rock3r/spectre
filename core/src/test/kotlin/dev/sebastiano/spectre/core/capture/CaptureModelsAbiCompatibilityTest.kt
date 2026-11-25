package dev.sebastiano.spectre.core.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Locks the schema-v1 Kotlin/JVM surface published by 0.9 (commit af64ac6b). */
class CaptureModelsAbiCompatibilityTest {
    @Test
    fun `schema v2 additions do not move historical destructuring components`() {
        val document = historicalDocument()
        assertEquals<Int>(1, document.component1())
        assertEquals<String>("historical", document.component2())
        assertEquals<CaptureWindow>(document.window, document.component3())
        assertEquals<List<CaptureNode>>(document.nodes, document.component4())
        assertEquals<CaptureSummary>(document.summary, document.component5())

        val window = document.window
        assertEquals<Int>(0, window.component1())
        assertEquals<String>("surface", window.component2())
        assertEquals<String?>("title", window.component3())
        assertEquals<Boolean>(false, window.component4())
        assertEquals<CaptureRect>(RECT, window.component5())
        assertEquals<Double>(1.0, window.component6())
        assertEquals<Double>(1.0, window.component7())
        assertEquals<Int>(10, window.component8())
        assertEquals<Int>(10, window.component9())

        val node = document.nodes.single()
        assertEquals<String>("key", node.component1())
        assertEquals<String?>("tag", node.component2())
        assertEquals<String?>("text", node.component3())
        assertEquals<List<String>>(listOf("text"), node.component4())
        assertEquals<String?>(null, node.component5())
        assertEquals<String?>(null, node.component6())
        assertEquals<String?>("Button", node.component7())
        assertEquals<Boolean>(true, node.component8())
        assertEquals<Boolean>(true, node.component9())
        assertEquals<Boolean>(false, node.component10())
        assertEquals<Boolean>(false, node.component11())
        assertEquals<CaptureRect>(RECT, node.component12())
        assertEquals<CaptureRect>(RECT, node.component13())
    }

    @Test
    fun `schema-v1 copy descriptors and default bridges remain callable`() {
        val document = historicalDocument()
        assertEquals("updated", document.copyV1(capturedAt = "updated").capturedAt)
        assertEquals("updated", document.window.copyV1(title = "updated").title)
        assertEquals("updated", document.nodes.single().copyV1(text = "updated").text)

        assertHistoricalCopyMethods(CaptureDocument::class.java, 5)
        assertHistoricalCopyMethods(CaptureWindow::class.java, 9)
        assertHistoricalCopyMethods(CaptureNode::class.java, 13)
    }

    @Test
    fun `historical schema-v1 construction serializes with required schema-v2 fields`() {
        val json = CaptureJson.encode(historicalDocument())
        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("\"source\": \"compose-semantics\""))
        assertTrue(json.contains("\"boundsSpace\": \"screen\""))
        assertTrue(json.contains("\"parentKey\": null"))
        assertTrue(json.contains("\"id\": \"tag\""))
    }

    private fun assertHistoricalCopyMethods(type: Class<*>, parameterCount: Int) {
        assertNotNull(
            type.declaredMethods.singleOrNull {
                it.name == "copy" && it.parameterCount == parameterCount
            }
        )
        assertNotNull(
            type.declaredMethods.singleOrNull {
                it.name == "copy\$default" && it.parameterCount == parameterCount + 3
            }
        )
    }

    private fun historicalDocument(): CaptureDocument {
        val node =
            CaptureNode(
                "key",
                "tag",
                "text",
                listOf("text"),
                null,
                null,
                "Button",
                true,
                true,
                false,
                false,
                RECT,
                RECT,
            )
        val window = CaptureWindow(0, "surface", "title", false, RECT, 1.0, 1.0, 10, 10)
        return CaptureDocument(
            1,
            "historical",
            window,
            listOf(node),
            CaptureSummary(1, 1, 1, 10, 10, 1),
        )
    }

    private companion object {
        val RECT = CaptureRect(0, 0, 10, 10)
    }
}
