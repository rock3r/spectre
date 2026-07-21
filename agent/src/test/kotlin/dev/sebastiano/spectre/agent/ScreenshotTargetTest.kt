package dev.sebastiano.spectre.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalSpectreAgentApi::class)
class ScreenshotTargetTest {
    private val windows = listOf(0 to "window:0", 1 to "window:1")

    @Test
    fun `default without options targets window index 0`() {
        val target =
            resolveScreenshotTarget(
                    fullscreen = false,
                    windowIndex = null,
                    surfaceId = null,
                    windows = windows,
                )
                .getOrThrow()
        assertEquals(ScreenshotTarget.Window(0), target)
    }

    @Test
    fun `explicit window index is forwarded`() {
        val target =
            resolveScreenshotTarget(
                    fullscreen = false,
                    windowIndex = 1,
                    surfaceId = null,
                    windows = windows,
                )
                .getOrThrow()
        assertEquals(ScreenshotTarget.Window(1), target)
    }

    @Test
    fun `surface id resolves to the matching window index`() {
        val target =
            resolveScreenshotTarget(
                    fullscreen = false,
                    windowIndex = null,
                    surfaceId = "window:1",
                    windows = windows,
                )
                .getOrThrow()
        assertEquals(ScreenshotTarget.Window(1), target)
    }

    @Test
    fun `fullscreen is explicit opt-in only`() {
        val target =
            resolveScreenshotTarget(
                    fullscreen = true,
                    windowIndex = null,
                    surfaceId = null,
                    windows = windows,
                )
                .getOrThrow()
        assertEquals(ScreenshotTarget.Fullscreen, target)
    }

    @Test
    fun `fullscreen cannot combine with window targeting`() {
        val result =
            resolveScreenshotTarget(
                fullscreen = true,
                windowIndex = 0,
                surfaceId = null,
                windows = windows,
            )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("fullscreen"))
    }

    @Test
    fun `missing windows fail loudly without silent fullscreen fallback`() {
        val result =
            resolveScreenshotTarget(
                fullscreen = false,
                windowIndex = null,
                surfaceId = null,
                windows = emptyList(),
            )
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("No tracked windows"), message)
        assertTrue(message.contains("full-desktop"), message)
        assertIs<IllegalStateException>(result.exceptionOrNull())
    }

    @Test
    fun `unknown surface id fails without fullscreen fallback`() {
        val result =
            resolveScreenshotTarget(
                fullscreen = false,
                windowIndex = null,
                surfaceId = "missing",
                windows = windows,
            )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("surfaceId=missing"))
    }

    @Test
    fun `out of range window index fails without fullscreen fallback`() {
        val result =
            resolveScreenshotTarget(
                fullscreen = false,
                windowIndex = 9,
                surfaceId = null,
                windows = windows,
            )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("index 9"))
    }
}
