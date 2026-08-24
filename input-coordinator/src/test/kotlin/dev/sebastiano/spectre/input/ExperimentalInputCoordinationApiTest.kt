@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExperimentalInputCoordinationApiTest {

    @Test
    fun `the marker is warning-level and runtime-visible`() {
        val source =
            Path.of(
                    "src/main/kotlin/dev/sebastiano/spectre/input/ExperimentalSpectreInputCoordinationApi.kt"
                )
                .readText()

        assertTrue(source.contains("RequiresOptIn.Level.WARNING"))
        assertTrue(source.contains("AnnotationRetention.RUNTIME"))
    }

    @Test
    fun `the published coordinator surface remains experimental`() {
        PUBLISHED_COORDINATOR_TYPES.forEach { type ->
            assertNotNull(
                type.getAnnotation(ExperimentalSpectreInputCoordinationApi::class.java),
                "${type.name} must remain experimental until the coordination API graduates",
            )
        }
    }

    private companion object {
        val PUBLISHED_COORDINATOR_TYPES: List<Class<*>> =
            listOf(
                CanonicalPathResolver::class.java,
                CoordinatedInputLease::class.java,
                CoordinatorControlResult::class.java,
                CoordinatorEndpoint::class.java,
                CoordinatorEndpointResolver::class.java,
                CoordinatorFrame::class.java,
                CoordinatorFrameCodec::class.java,
                CoordinatorHolderStatus::class.java,
                CoordinatorProtocolError::class.java,
                CoordinatorProtocolException::class.java,
                CoordinatorQuarantineStatus::class.java,
                CoordinatorRevokeResult::class.java,
                CoordinatorStatus::class.java,
                CoordinatorWaiterStatus::class.java,
                CoordinatorWireCodec::class.java,
                CoordinatorWireHolder::class.java,
                CoordinatorWireKind::class.java,
                CoordinatorWireMessage::class.java,
                CoordinatorWireQuarantine::class.java,
                CoordinatorWireStatus::class.java,
                CoordinatorWireWaiter::class.java,
                DesktopIdentityEnvironment::class.java,
                DesktopIdentityResolver::class.java,
                DesktopPlatform::class.java,
                DesktopResourceKey::class.java,
                InputCoordinatorClientFactory::class.java,
                InputCoordinatorClientProvider::class.java,
                InputCoordinatorException::class.java,
                LeaseErrorCode::class.java,
                LeaseOwner::class.java,
                LeaseToken::class.java,
                LocalCoordinatorEnvironment::class.java,
                LocalInputCoordinatorClient::class.java,
                LocalInputCoordinatorControl::class.java,
                OwnerOnlyEndpointProtection::class.java,
            )
    }
}
