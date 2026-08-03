@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.sample.validation

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.InternalSpectreApi
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.core.WindowTracker
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
    private val startupTimeout: Duration = DEFAULT_STARTUP_TIMEOUT,
) {

    private val applicationStarted = CountDownLatch(1)
    private val startupError = AtomicReference<Throwable?>(null)
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
        // Cold Windows CI workers spend a noticeable chunk of the startup budget on first-time
        // AWT Toolkit / peer init. Warm the toolkit on the test thread *before* arming the latch
        // clock so that budget is spent waiting for Compose Desktop's application{} entry, not
        // for AWT itself.
        Toolkit.getDefaultToolkit()
        thread =
            Thread(
                    {
                        try {
                            application {
                                exitFn = ::exitApplication
                                applicationStarted.countDown()
                                Window(onCloseRequest = ::exitApplication, title = title) {
                                    dev.sebastiano.spectre.sample.App()
                                }
                            }
                        } catch (t: Throwable) {
                            startupError.compareAndSet(null, t)
                            // Unblock the waiter even when application{} never entered so the
                            // failure path can report the real exception instead of only a timeout.
                            applicationStarted.countDown()
                            throw t
                        }
                    },
                    "spectre-sample-fixture",
                )
                .apply {
                    isDaemon = true
                    uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                        startupError.compareAndSet(null, error)
                    }
                }
        thread.start()
        val entered =
            applicationStarted.await(startupTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        throwIfStartupFailed(enteredApplication = entered)
        // The Window enters the AWT hierarchy a few frames after application{} starts. Poll a
        // bootstrap WindowTracker until it surfaces our window so we can construct the
        // automator with a synthetic driver bound to it. Match by title — `Window.getWindows()`
        // can return multiple top-level windows in unspecified order (other JVM windows, the
        // sample app's previous instance, etc.), so taking `.first()` would risk attaching the
        // synthetic driver to an unrelated surface.
        //
        // Re-check [startupError] each poll: Window/composition can still throw *after*
        // applicationStarted counted down; without this, that failure only surfaces 30s later
        // as a generic "window did not appear" with no cause (Codex review on #373).
        val bootstrapTracker = WindowTracker()
        val deadline = System.nanoTime() + startupTimeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            throwIfStartupFailed(enteredApplication = true)
            bootstrapTracker.refresh()
            val tracked =
                bootstrapTracker.trackedWindows.value.firstOrNull {
                    runCatching { (it.window as? java.awt.Frame)?.title }.getOrNull() == title
                }
            if (tracked != null) {
                _automator =
                    ComposeAutomator.inProcess(robotDriver = RobotDriver.synthetic(tracked.window))
                _automator.refreshWindows()
                return
            }
            Thread.sleep(WINDOW_POLL.inWholeMilliseconds)
        }
        throwIfStartupFailed(enteredApplication = true)
        error("Main window with title '$title' did not appear within $startupTimeout")
    }

    private fun throwIfStartupFailed(enteredApplication: Boolean) {
        val error = startupError.get()
        if (enteredApplication && error == null) return
        throw IllegalStateException(
            describeApplicationStartupFailure(
                timeout = startupTimeout,
                enteredApplication = enteredApplication,
                threadAlive = ::thread.isInitialized && thread.isAlive,
                startupError = error,
            ),
            error,
        )
    }

    fun stop() {
        // Robust against `start()` never having been called: a JUnit `assumeFalse` in @BeforeAll
        // skips the suite but @AfterAll still runs, so this method may be invoked even though
        // `thread` was never initialized. Without the guard, that lifecycle path tripped on the
        // `lateinit` access and surfaced as a confusing `initializationError` instead of the
        // intended skip outcome.
        exitFn?.invoke()
        if (::thread.isInitialized) {
            thread.join(SHUTDOWN_TIMEOUT.inWholeMilliseconds)
        }
    }

    companion object {
        /**
         * Default wait for Compose Desktop `application {}` entry and subsequent window discovery.
         *
         * 10s proved tight on cold Windows GHA workers (`AtomicCaptureValidationTest` — "Sample app
         * did not enter application{} within 10s"). Each validation class forks a fresh JVM
         * (`forkEvery = 1`), so first AWT + Compose Desktop init regularly burns several seconds
         * before the application lambda runs.
         */
        val DEFAULT_STARTUP_TIMEOUT: Duration = 30.seconds

        private val WINDOW_POLL: Duration = 100.milliseconds
        private val SHUTDOWN_TIMEOUT: Duration = 5.seconds
    }
}

/**
 * Human-readable startup failure text for [SampleAppFixture.start]. Pure so diagnostics can be
 * unit-tested without spawning AWT / Compose Desktop.
 */
internal fun describeApplicationStartupFailure(
    timeout: Duration,
    enteredApplication: Boolean,
    threadAlive: Boolean,
    startupError: Throwable?,
): String {
    val parts = mutableListOf<String>()
    if (!enteredApplication) {
        parts += "Sample app did not enter application{} within $timeout"
    } else {
        parts += "Sample app application{} failed during startup"
    }
    parts +=
        if (threadAlive) {
            "fixture thread still alive"
        } else {
            "fixture thread already exited"
        }
    if (startupError != null) {
        parts += "cause=${startupError::class.java.name}: ${startupError.message ?: "(no message)"}"
    }
    return parts.joinToString("; ")
}

/**
 * `true` when this JVM was booted as a macOS UI element (`apple.awt.UIElement=true`), in which case
 * AppKit suppresses the spawned window's focus-grab / Dock icon but also restricts NSPasteboard
 * access. Tests that rely on the clipboard (currently only the paste-text fidelity validation) gate
 * themselves on this so the focus-quiet workflow stays the default while the paste fidelity test
 * still runs when the property is explicitly off.
 *
 * We check `apple.awt.UIElement` directly (the actual AWT switch) in addition to the
 * `spectre.sample.fixture.uiElement` mirror flag the validation Gradle tasks set. Either being
 * truthy is enough to skip — that way running validation from an IDE / CI with just the AWT
 * property still skips the test instead of attempting a paste that AppKit will silently drop.
 */
val sampleFixtureRunsAsUiElement: Boolean
    get() =
        System.getProperty("apple.awt.UIElement", "false").toBoolean() ||
            System.getProperty("spectre.sample.fixture.uiElement", "false").toBoolean()
