@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core.perf

import androidx.compose.runtime.Recomposer
import dev.sebastiano.spectre.core.InternalSpectreApi
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit-tests [RecomposerInspector] against a fake host-object hierarchy that mirrors the field
 * names used by Compose Multiplatform Desktop 1.12:
 * ```
 * host → composePanel → _composeContainer → mediator → frameRecomposer → recomposer (Recomposer)
 * ```
 *
 * The intermediate types are irrelevant to the reflection — only field names matter — so the fakes
 * are plain objects with the expected `val` shape. **Live CMP wiring against a real `ComposeWindow`
 * is not yet covered by automated tests** (planned follow-up under `sample-desktop`); the
 * reflective chain here is verified structurally and any field-rename regression in real CMP will
 * surface as `findRecomposer` returning `null` rather than crashing.
 */
class RecomposerInspectorTest {

    @Test
    fun `walks the host chain and returns the inner Recomposer`() {
        val recomposer = Recomposer(EmptyCoroutineContext)
        val host = FakeComposeWindow(recomposer)

        val found = RecomposerInspector.findRecomposerInHostChain(host)

        assertSame(recomposer, found)
    }

    @Test
    fun `reports an incomplete inspection when host has no composePanel field`() {
        val error =
            assertFailsWith<IllegalStateException> {
                RecomposerInspector.findRecomposerInHostChain(Any())
            }
        assertTrue(error.message.orEmpty().contains("composePanel"))
        assertTrue(error.message.orEmpty().contains("Compose"))
    }

    @Test
    fun `reports an incomplete inspection when an intermediate field is missing`() {
        val error =
            assertFailsWith<IllegalStateException> {
                RecomposerInspector.findRecomposerInHostChain(FakeHostWithDeadEnd())
            }
        assertTrue(error.message.orEmpty().contains("_composeContainer"))
    }

    @Test
    fun `returns null for null host`() {
        val found = RecomposerInspector.findRecomposerInHostChain(null)
        assertNull(found)
    }

    @Test
    fun `reports an incomplete inspection when terminal field is not a Recomposer`() {
        val error =
            assertFailsWith<IllegalStateException> {
                RecomposerInspector.findRecomposerInHostChain(FakeComposeWindowWithWrongTerminal())
            }
        assertTrue(error.message.orEmpty().contains("recomposer"))
    }

    @Test
    fun `temporarily uninitialized valid host remains unresolved`() {
        assertNull(RecomposerInspector.findRecomposerInHostChain(FakeUninitializedHost()))
    }
}

private class FakeComposeWindow(recomposer: Recomposer) {
    @JvmField val composePanel = FakeComposePanel(recomposer)
}

private class FakeComposePanel(recomposer: Recomposer) {
    // Field name must match Compose's private property exactly — the inspector reads by field
    // name, and any rename here would silently stop reflecting the real CMP shape.
    @Suppress("VariableNaming") @JvmField val _composeContainer = FakeComposeContainer(recomposer)
}

private class FakeComposeContainer(recomposer: Recomposer) {
    @JvmField val mediator = FakeMediator(recomposer)
}

private class FakeMediator(recomposer: Recomposer) {
    @JvmField val frameRecomposer = FakeFrameRecomposer(recomposer)
}

private class FakeFrameRecomposer(@JvmField val recomposer: Recomposer)

private class FakeHostWithDeadEnd {
    @JvmField val composePanel: Any = Any()
}

private class FakeUninitializedHost {
    @JvmField val composePanel: Any? = null
}

private class FakeComposeWindowWithWrongTerminal {
    @JvmField val composePanel = FakeComposePanelWithWrongTerminal()
}

private class FakeComposePanelWithWrongTerminal {
    @Suppress("VariableNaming") @JvmField val _composeContainer = FakeContainerWithWrongTerminal()
}

private class FakeContainerWithWrongTerminal {
    @JvmField val mediator = FakeMediatorWithWrongTerminal()
}

private class FakeMediatorWithWrongTerminal {
    @JvmField val frameRecomposer = FakeFrameRecomposerWithWrongTerminal()
}

private class FakeFrameRecomposerWithWrongTerminal {
    @JvmField val recomposer = "not a Recomposer"
}
