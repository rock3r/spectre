package dev.sebastiano.spectre.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisualIdleCapturePolicyTest {
    @Test
    fun `disabled platform capture fails by default`() = assertDefaultFailure(disabledReason())

    @Test
    fun `disabled platform capture permits explicit region fallback`() =
        assertOptInFallback(disabledReason())

    @Test
    fun `missing native bridge fails by default`() = assertDefaultFailure(missingBridgeReason())

    @Test
    fun `missing native bridge permits explicit region fallback`() =
        assertOptInFallback(missingBridgeReason())

    @Test fun `hosted Windows fails by default`() = assertDefaultFailure(hostedWindowsReason())

    @Test
    fun `hosted Windows permits explicit region fallback`() =
        assertOptInFallback(hostedWindowsReason())

    @Test fun `failed platform probe fails by default`() = assertDefaultFailure(failedProbeReason())

    @Test
    fun `failed platform probe permits explicit region fallback`() =
        assertOptInFallback(failedProbeReason())

    private fun assertDefaultFailure(reason: String?) {
        val error =
            assertFailsWith<UnsupportedOperationException> {
                requireVisualIdleNativeCapture(reason, allowScreenRegionFallback = false)
            }
        assertTrue(error.message.orEmpty().contains("allowScreenRegionFallback"))
    }

    private fun assertOptInFallback(reason: String?) {
        assertFalse(requireVisualIdleNativeCapture(reason, allowScreenRegionFallback = true))
    }

    private fun disabledReason(): String? =
        visualIdleNativeCaptureUnavailableReason(allowsPlatformCapture = false)

    private fun missingBridgeReason(): String? =
        visualIdleNativeCaptureUnavailableReason(
            classLoader = object : ClassLoader(null) {},
            allowsPlatformCapture = true,
            osName = "Linux",
        )

    private fun hostedWindowsReason(): String? =
        visualIdleNativeCaptureUnavailableReason(
            allowsPlatformCapture = true,
            osName = "Windows 11",
            getenv = { if (it == "RUNNER_ENVIRONMENT") "github-hosted" else null },
        )

    private fun failedProbeReason(): String? =
        visualIdleNativeCaptureUnavailableReason(
            allowsPlatformCapture = true,
            osName = "Linux",
            getenv = { null },
            platformCaptureUsable = { false },
        )
}
