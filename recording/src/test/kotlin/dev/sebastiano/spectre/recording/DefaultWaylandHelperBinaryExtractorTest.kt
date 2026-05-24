package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.portal.DefaultWaylandHelperBinaryExtractor
import dev.sebastiano.spectre.recording.portal.WaylandPortalRecorder
import dev.sebastiano.spectre.recording.portal.WaylandPortalWindowRecorder
import kotlin.test.Test
import kotlin.test.assertSame

class DefaultWaylandHelperBinaryExtractorTest {

    @Test
    fun `Linux helper backed defaults share one extractor`() {
        val extractor = DefaultWaylandHelperBinaryExtractor.instance

        assertSame(extractor, LinuxX11Recorder().helperExtractorForTest())
        assertSame(extractor, LinuxNativeScreenshotter().helperExtractorForTest())
        assertSame(extractor, WaylandPortalRecorder().helperExtractorForTest())
        assertSame(
            extractor,
            WaylandPortalWindowRecorder().delegateForTest().helperExtractorForTest(),
        )
    }
}

private fun Any.helperExtractorForTest(): Any? = declaredField("helperExtractor").get(this)

private fun WaylandPortalWindowRecorder.delegateForTest(): Any = declaredField("delegate").get(this)

private fun Any.declaredField(name: String): java.lang.reflect.Field =
    javaClass.getDeclaredField(name).apply { isAccessible = true }
