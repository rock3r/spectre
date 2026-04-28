package dev.sebastiano.spectre.sample.validation

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.core.SemanticsReader
import dev.sebastiano.spectre.core.WindowTracker
import dev.sebastiano.spectre.core.synthetic
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Spawns the sample-desktop application in a daemon thread inside the test JVM.
 *
 * The fixture exists so validation tests can use a real `ComposeAutomator` to drive the same
 * `App()` composition shipped to users — Spectre is the surface under test, the sample is the
 * workload, and the assertions check what Spectre actually observes.
 *
 * The automator is constructed with [`RobotDriver.synthetic`][synthetic] against the spawned
 * window, so:
 * - the host machine's mouse and keyboard remain free
 * - tests don't need OS-level focus on the spawned window
 * - parallel validation runs don't contend for OS input
 *
 * Skips itself in headless environments — `start()` throws [`IllegalStateException`] from
 * [`requireDisplay`] before spawning anything, so test classes can `Assumptions.assumeFalse(...)`
 * cleanly on CI.
 */
class SampleAppFixture(
    private val title: String = "Spectre validation",
    private val startupTimeout: Duration = 10.seconds,
) {

    private val applicationStarted = CountDownLatch(1)
    @Volatile private var exitFn: (() -> Unit)? = null
    private lateinit var thread: Thread
    private lateinit var _automator: ComposeAutomator

    /** The automator instance bound to the spawned application. Valid only after [start]. */
    val automator: ComposeAutomator
        get() = _automator

    /**
     * Throws if the test environment cannot host an AWT display. Call from `@BeforeAll` so the
     * entire test class is skipped cleanly on headless CI.
     */
    fun requireDisplay() {
        check(!GraphicsEnvironment.isHeadless()) {
            "SampleAppFixture cannot run in a headless environment (java.awt.headless=true)"
        }
    }

    fun start() {
        requireDisplay()
        thread =
            Thread(
                    {
                        application {
                            exitFn = ::exitApplication
                            applicationStarted.countDown()
                            Window(onCloseRequest = ::exitApplication, title = title) {
                                dev.sebastiano.spectre.sample.App()
                            }
                        }
                    },
                    "spectre-sample-fixture",
                )
                .apply { isDaemon = true }
        thread.start()
        check(applicationStarted.await(startupTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            "Sample app did not enter application{} within $startupTimeout"
        }
        // The Window enters the AWT hierarchy a few frames after application{} starts. Poll a
        // bootstrap WindowTracker until it surfaces our window so we can construct the
        // automator with a synthetic driver bound to it. Match by title — `Window.getWindows()`
        // can return multiple top-level windows in unspecified order (other JVM windows, the
        // sample app's previous instance, etc.), so taking `.first()` would risk attaching the
        // synthetic driver to an unrelated surface.
        val bootstrapTracker = WindowTracker()
        val deadline = System.nanoTime() + startupTimeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            bootstrapTracker.refresh()
            val tracked =
                bootstrapTracker.trackedWindows.firstOrNull {
                    runCatching { (it.window as? java.awt.Frame)?.title }.getOrNull() == title
                }
            if (tracked != null) {
                _automator =
                    ComposeAutomator.inProcess(
                        windowTracker = WindowTracker(),
                        semanticsReader = SemanticsReader(),
                        robotDriver = RobotDriver.synthetic(tracked.window),
                    )
                _automator.refreshWindows()
                return
            }
            Thread.sleep(WINDOW_POLL.inWholeMilliseconds)
        }
        error("Main window with title '$title' did not appear within $startupTimeout")
    }

    fun stop() {
        exitFn?.invoke()
        thread.join(SHUTDOWN_TIMEOUT.inWholeMilliseconds)
    }

    private companion object {
        val WINDOW_POLL: Duration = 100.milliseconds
        val SHUTDOWN_TIMEOUT: Duration = 5.seconds
    }
}

/**
 * `true` when the validation Gradle task booted this JVM as a macOS UI element
 * (`apple.awt.UIElement=true`), in which case AppKit suppresses the spawned window's focus-grab /
 * Dock icon but also restricts NSPasteboard access. Tests that rely on the clipboard (currently
 * only `Issue8FidelityValidationTest.typeText`) gate themselves on this so the focus-quiet workflow
 * stays the default while the paste fidelity test still runs when the property is explicitly off.
 */
val sampleFixtureRunsAsUiElement: Boolean
    get() = System.getProperty("spectre.sample.fixture.uiElement", "false").toBoolean()
