@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core.perf

import androidx.compose.runtime.Recomposer
import dev.sebastiano.spectre.core.InternalSpectreApi
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Unit-tests [RecomposerInspector] against a fake host-object hierarchy that mirrors the field
 * names used by Compose Multiplatform Desktop:
 * ```
 * host → composePanel → _composeContainer → mediator → scene → recomposer → recomposer (Recomposer)
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
    fun `returns null when host has no composePanel field`() {
        val found = RecomposerInspector.findRecomposerInHostChain(Any())
        assertNull(found)
    }

    @Test
    fun `returns null when any intermediate field is missing`() {
        // Host stops at composePanel — no _composeContainer field beneath.
        val brokenHost = FakeHostWithDeadEnd()
        val found = RecomposerInspector.findRecomposerInHostChain(brokenHost)
        assertNull(found)
    }

    @Test
    fun `returns null for null host`() {
        val found = RecomposerInspector.findRecomposerInHostChain(null)
        assertNull(found)
    }

    @Test
    fun `returns null when terminal field is not a Recomposer`() {
        // Terminal slot holds a String, not a Recomposer — inspector must not blindly cast.
        val host = FakeComposeWindowWithWrongTerminal()
        val found = RecomposerInspector.findRecomposerInHostChain(host)
        assertNull(found)
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
    @JvmField val scene = FakeScene(recomposer)
}

private class FakeScene(recomposer: Recomposer) {
    @JvmField val recomposer = FakeSceneRecomposer(recomposer)
}

private class FakeSceneRecomposer(@JvmField val recomposer: Recomposer)

private class FakeHostWithDeadEnd {
    @JvmField val composePanel: Any = Any()
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
    @JvmField val scene = FakeSceneWithWrongTerminal()
}

private class FakeSceneWithWrongTerminal {
    @JvmField val recomposer = FakeSceneRecomposerWithWrongTerminal()
}

private class FakeSceneRecomposerWithWrongTerminal {
    @JvmField val recomposer = "not a Recomposer"
}
