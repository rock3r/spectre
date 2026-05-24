package dev.sebastiano.spectre.sample

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.sebastiano.spectre.core.ComposeAutomator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val INITIAL_SETTLE_DELAY_MS = 2000L
private const val BETWEEN_ACTION_DELAY_MS = 500L
private const val CLICK_REPETITIONS = 3

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Spectre") { App() }

    if (System.getProperty("spectre.demo") == "true") {
        launchAutomatorDemo()
    }
}

// Demo launcher kept separate so the dispatcher can be injected (Detekt's `InjectDispatcher`
// rule treats default-parameter values as the canonical injection seam). `GlobalScope` is the
// intended lifetime here — the demo coroutine is decoupled from the Compose application and
// finishes on its own once `runAutomatorDemo` returns; tying it to a Compose-owned scope would
// drag in lifecycle ceremony the sample doesn't need.
@OptIn(DelicateCoroutinesApi::class)
private fun launchAutomatorDemo(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    GlobalScope.launch(dispatcher) {
        delay(INITIAL_SETTLE_DELAY_MS)
        runAutomatorDemo()
    }
}

@Suppress("LongMethod")
private suspend fun runAutomatorDemo() {
    val automator = ComposeAutomator.inProcess()
    automator.refreshWindows()

    println("=== Spectre Automator Demo ===")
    println("Tracked windows: ${automator.surfaceIds().size}")
    println()
    print(automator.printTree())
    println()

    val header = automator.findOneByTestTag("header")
    println("Found header: ${header?.text}")

    val button = automator.findOneByTestTag("incrementButton")
    if (button != null) {
        automator.focusWindow(button)
        delay(BETWEEN_ACTION_DELAY_MS)

        println("Clicking button $CLICK_REPETITIONS times...")
        repeat(CLICK_REPETITIONS) {
            automator.click(button)
            delay(BETWEEN_ACTION_DELAY_MS)
        }
        delay(BETWEEN_ACTION_DELAY_MS)
        automator.refreshWindows()
        val allNodes = automator.allNodes()
        allNodes
            .filter { it.text?.startsWith("Count:") == true }
            .forEach { println("  Found count text: ${it.text}") }
    }

    val textInput = automator.findOneByTestTag("textInput")
    if (textInput != null) {
        println("Found text input, clicking and typing...")
        automator.click(textInput)
        delay(BETWEEN_ACTION_DELAY_MS)
        automator.typeText("Hello from Spectre!")
        delay(BETWEEN_ACTION_DELAY_MS)

        automator.refreshWindows()
        val echo = automator.findOneByTestTag("echoText")
        println("Echo text: ${echo?.text}")
    }

    println()
    println("=== Demo complete ===")
}
