@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentRequestHandler
import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * [AgentRequestHandler] that drives a Spectre `ComposeAutomator` instance entirely through
 * reflection.
 *
 * Living in the agent JAR means we have no compile-time dependency on Spectre `core`; everything
 * goes through `Class.getMethod` + `Method.invoke`. The reflective objects ([automator]) live in
 * the **target's** classloader (the one [AgentBootstrap.findSpectreClassLoader] returned), so all
 * Compose `is`-checks inside the automator resolve correctly against the target's own Compose
 * classes.
 *
 * Performance: reflective `Method` lookups are cached on construction so the per-request hot path
 * is just `Method.invoke`. CBOR encoding lives in the `IpcServer` layer above.
 *
 * Failure handling: any exception from the reflective call surfaces as [AgentResponse.Error] with
 * the underlying type name + message. Stack traces stay in the target's stderr (server-side) where
 * developers expect them; we deliberately don't ship them across the wire because that would mean
 * smuggling `Throwable` types we can't safely reconstruct on the client side.
 */
internal class ReflectiveAutomatorHandler(
    private val automator: Any,
    private val isTargetJvmFocused: () -> Boolean = ::targetJvmHasKeyboardFocus,
) : AgentRequestHandler {

    private val automatorClass: Class<*> = automator.javaClass
    private val getWindowsMethod = automatorClass.getMethod("getWindows")
    private val allNodesMethod = automatorClass.getMethod("allNodes")
    private val findByTestTagMethod = automatorClass.getMethod("findByTestTag", String::class.java)
    private val nodeBooleanMethods = ConcurrentHashMap<Pair<Class<*>, String>, Method>()
    private val nodeEditableTextMethods = ConcurrentHashMap<Class<*>, Method>()

    /**
     * `ComposeAutomator.windows` is a Volatile cache populated by `refreshWindows()` (or by any of
     * the side-door methods like `tree()` that call it internally). The plain getter starts empty;
     * reading it before a refresh returns no windows even when the target's UI is up. `allNodes()`
     * / `findByTestTag()` read the same cache via `windows` and so suffer the same staleness. The
     * agent must refresh explicitly before every snapshot read so the wire results reflect the live
     * state.
     *
     * `refreshWindows()` exists on `ComposeAutomator` (public no-op-returning method); we look it
     * up reflectively so the handler still doesn't compile against `core` types.
     */
    private val refreshWindowsMethod = automatorClass.getMethod("refreshWindows")

    /**
     * Suspend `click(node: AutomatorNode)`. JVM signature has 2 params (the node + the trailing
     * `Continuation`).
     */
    private val clickSuspendMethod: java.lang.reflect.Method? =
        automatorClass.methods.firstOrNull {
            it.name == "click" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[1].name == CONTINUATION_FQN
        }

    /** Suspend `typeText(text: String)`. 2 params: text + Continuation. */
    private val typeTextSuspendMethod: java.lang.reflect.Method? =
        automatorClass.methods.firstOrNull {
            it.name == "typeText" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1].name == CONTINUATION_FQN
        }

    private val suspendInvoker = BlockingSuspendInvoker()

    override fun handle(request: AgentRequest): AgentResponse =
        try {
            when (request) {
                AgentRequest.Ping -> AgentResponse.Pong
                AgentRequest.Windows -> handleWindows()
                AgentRequest.AllNodes -> handleAllNodes()
                is AgentRequest.FindByTestTag -> handleFindByTestTag(request.tag)
                is AgentRequest.Click -> handleClick(request.nodeKey)
                is AgentRequest.TypeText -> handleTypeText(request.text)
                AgentRequest.Screenshot -> handleScreenshot()
                is AgentRequest.Capture ->
                    AtomicCaptureReflectiveMapper.invoke(automator, request.windowIndex)
                is AgentRequest.WindowIdentity ->
                    WindowIdentityReflectiveMapper.invoke(automator, request.windowIndex)
                AgentRequest.Detach -> AgentResponse.Detached
            }
        } catch (ex: ReflectiveOperationException) {
            AgentResponse.Error("Reflective call failed: ${ex.targetMessage()}")
        }

    // Note on un-caught exceptions: any non-reflective `RuntimeException` thrown by the
    // automator (NullPointerException from a broken window list, ClassCastException from a
    // method whose JVM signature drifted, etc.) propagates to [IpcServer.handleConnection],
    // which converts it to an [AgentResponse.Error] response. Centralising that catch keeps
    // the per-op handlers readable and avoids a blanket `catch RuntimeException` here.

    private fun handleWindows(): AgentResponse {
        refreshWindowsMethod.invoke(automator)
        val windows = getWindowsMethod.invoke(automator) as List<*>
        return AgentResponse.Windows(windows.mapIndexedNotNull(::mapTrackedWindow))
    }

    private fun handleAllNodes(): AgentResponse {
        refreshWindowsMethod.invoke(automator)
        val nodes = allNodesMethod.invoke(automator) as List<*>
        return AgentResponse.Nodes(nodes.mapNotNull { it?.let(::mapAutomatorNode) })
    }

    private fun handleFindByTestTag(tag: String): AgentResponse {
        refreshWindowsMethod.invoke(automator)
        val nodes = findByTestTagMethod.invoke(automator, tag) as List<*>
        return AgentResponse.Nodes(nodes.mapNotNull { it?.let(::mapAutomatorNode) })
    }

    private fun handleClick(nodeKey: String): AgentResponse {
        // ComposeAutomator's `click(node)` is `suspend`. We look up the node by key from the
        // current `allNodes()` snapshot (after refreshing — `windows` is a cache), then
        // invoke the suspend method via the BlockingSuspendInvoker bridge so the agent's
        // wire protocol stays synchronous request/response.
        refreshWindowsMethod.invoke(automator)
        val allNodes = allNodesMethod.invoke(automator) as List<*>
        val match =
            allNodes.firstOrNull { it != null && extractKey(it) == nodeKey }
                ?: return AgentResponse.Error("No node found with key=$nodeKey")
        val method =
            clickSuspendMethod
                ?: return AgentResponse.Error(
                    "ComposeAutomator does not expose a click(node) method"
                )
        suspendInvoker.invoke(method, automator, match)
        return AgentResponse.Ok
    }

    private fun handleTypeText(text: String): AgentResponse {
        val method =
            typeTextSuspendMethod
                ?: return AgentResponse.Error(
                    "ComposeAutomator does not expose a typeText(text) method"
                )
        refreshWindowsMethod.invoke(automator)
        val allNodes = allNodesMethod.invoke(automator) as List<*>
        val focusedNodes =
            allNodes.filterNotNull().filter { nodeBooleanProperty(it, methodName = "isFocused") }
        if (focusedNodes.isEmpty()) {
            return AgentResponse.Error(
                "Refusing typeText because no focused Spectre node was found in the " +
                    "target JVM. Focus a target node before sending real keyboard events."
            )
        }
        if (focusedNodes.none { extractKey(it).isNotBlank() }) {
            return AgentResponse.Error(
                "Refusing typeText because every focused Spectre node has a blank key."
            )
        }
        if (!isTargetJvmFocused()) {
            return AgentResponse.Error(
                "Refusing typeText because the target JVM does not currently own OS keyboard " +
                    "focus. Activate the target window before sending real keyboard events."
            )
        }
        suspendInvoker.invoke(method, automator, text)
        return AgentResponse.Ok
    }

    private fun handleScreenshot(): AgentResponse {
        // `ComposeAutomator.screenshot(region: Rectangle? = null)` is a single Kotlin method with
        // a default param. Without `@JvmOverloads`, the JVM-level signature is
        // `screenshot(Rectangle)` — there is no zero-arg overload. We pass `null` for full-screen
        // capture.
        val screenshotMethod =
            automatorClass.methods.firstOrNull {
                it.name == "screenshot" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name == AWT_RECTANGLE_FQN
            }
                ?: return AgentResponse.Error(
                    "ComposeAutomator does not expose screenshot(Rectangle?) on this build"
                )
        val image = screenshotMethod.invoke(automator, null) as BufferedImage
        val pngBytes = imageToPng(image)
        return AgentResponse.Screenshot(pngBytes)
    }

    private fun imageToPng(image: BufferedImage): ByteArray {
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        return baos.toByteArray()
    }

    /**
     * Reflectively maps a `TrackedWindow` instance to its wire DTO.
     *
     * Method names match Spectre's actual API (`core/.../TrackedWindow.kt`):
     * - `getSurfaceId()` for `val surfaceId: String`
     * - `isPopup()` for `val isPopup: Boolean` (Kotlin "is" prefix convention)
     * - `getComposeSurfaceBoundsOnScreen()` for `val composeSurfaceBoundsOnScreen: Rectangle`
     * - `getWindow()` for `val window: java.awt.Window` — used to extract `title` (only meaningful
     *   when the underlying window is a `java.awt.Frame`).
     */
    private fun mapTrackedWindow(index: Int, trackedWindow: Any?): WindowSummaryDto? {
        if (trackedWindow == null) return null
        val klass = trackedWindow.javaClass
        val surfaceId = klass.getMethod("getSurfaceId").invoke(trackedWindow) as String
        val isPopup = klass.getMethod("isPopup").invoke(trackedWindow) as Boolean
        val bounds = klass.getMethod("getComposeSurfaceBoundsOnScreen").invoke(trackedWindow)
        val window = klass.getMethod("getWindow").invoke(trackedWindow)
        // Only Frame has getTitle(); Window does not. JFrame extends Frame.
        val title = (window as? java.awt.Frame)?.title
        return WindowSummaryDto(
            index = index,
            surfaceId = surfaceId,
            title = title,
            isPopup = isPopup,
            bounds = boundsToRect(bounds),
        )
    }

    private fun mapAutomatorNode(node: Any): NodeSnapshotDto {
        val klass = node.javaClass
        return NodeSnapshotDto(
            key = extractKey(node),
            testTag =
                klass.methods
                    .firstOrNull { it.name == "getTestTag" && it.parameterCount == 0 }
                    ?.invoke(node) as? String,
            texts =
                (klass.methods
                        .firstOrNull { it.name == "getTexts" && it.parameterCount == 0 }
                        ?.invoke(node) as? List<*>)
                    ?.filterIsInstance<String>()
                    .orEmpty(),
            editableText = nodeEditableText(node),
            role =
                klass.methods
                    .firstOrNull { it.name == "getRole" && it.parameterCount == 0 }
                    ?.invoke(node)
                    ?.toString(),
            contentDescription =
                klass.methods
                    .firstOrNull { it.name == "getContentDescription" && it.parameterCount == 0 }
                    ?.invoke(node) as? String,
            isFocused = nodeBooleanProperty(node, methodName = "isFocused"),
            isVisible = nodeBooleanProperty(node, methodName = "isVisible"),
            bounds =
                boundsToRect(
                    // `AutomatorNode.boundsOnScreen: Rectangle` → getBoundsOnScreen(). The
                    // earlier draft used `getBoundsInScreen` which doesn't exist and silently
                    // produced RectDto(0,0,0,0).
                    klass.methods
                        .firstOrNull { it.name == "getBoundsOnScreen" && it.parameterCount == 0 }
                        ?.invoke(node)
                ),
        )
    }

    private fun extractKey(node: Any): String =
        node.javaClass.methods
            .firstOrNull { it.name == "getKey" && it.parameterCount == 0 }
            ?.invoke(node)
            ?.toString() ?: node.toString()

    private fun nodeBooleanProperty(node: Any, methodName: String): Boolean =
        nodeBooleanMethods
            .computeIfAbsent(node.javaClass to methodName) { (klass, name) ->
                klass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?: error("AutomatorNode API mismatch: ${klass.name} does not expose $name()")
            }
            .invoke(node) as Boolean

    private fun nodeEditableText(node: Any): String? =
        nodeEditableTextMethods
            .computeIfAbsent(node.javaClass) { klass ->
                klass.methods.firstOrNull { it.name == "getEditableText" && it.parameterCount == 0 }
                    ?: error(
                        "AutomatorNode API mismatch: ${klass.name} does not expose " +
                            "getEditableText()"
                    )
            }
            .invoke(node) as? String

    private fun boundsToRect(bounds: Any?): RectDto {
        if (bounds == null) return RectDto(0, 0, 0, 0)
        val klass = bounds.javaClass
        // Try AWT-style getX/getY/getWidth/getHeight first; fall back to Compose Rect's
        // left/top/right/bottom.
        return runCatching {
                RectDto(
                    x = (klass.getMethod("getX").invoke(bounds) as Number).toInt(),
                    y = (klass.getMethod("getY").invoke(bounds) as Number).toInt(),
                    width = (klass.getMethod("getWidth").invoke(bounds) as Number).toInt(),
                    height = (klass.getMethod("getHeight").invoke(bounds) as Number).toInt(),
                )
            }
            .recoverCatching {
                val left = (klass.getMethod("getLeft").invoke(bounds) as Number).toFloat()
                val top = (klass.getMethod("getTop").invoke(bounds) as Number).toFloat()
                val right = (klass.getMethod("getRight").invoke(bounds) as Number).toFloat()
                val bottom = (klass.getMethod("getBottom").invoke(bounds) as Number).toFloat()
                RectDto(left.toInt(), top.toInt(), (right - left).toInt(), (bottom - top).toInt())
            }
            .getOrDefault(RectDto(0, 0, 0, 0))
    }

    private fun ReflectiveOperationException.targetMessage(): String {
        val cause = this.cause ?: return this.message ?: this.javaClass.simpleName
        // The cause is the real failure (`InvocationTargetException` unwraps to the automator's
        // own exception). Both halves of the formatted string must read from `cause` — an earlier
        // draft fell back to `javaClass.simpleName` on the receiver (`this` =
        // ReflectiveOperationException), which produced misleading messages like
        // `"NullPointerException: InvocationTargetException"`. Bugbot caught it.
        return "${cause.javaClass.simpleName}: ${cause.message ?: NO_MESSAGE_PLACEHOLDER}"
    }

    private companion object {
        const val CONTINUATION_FQN: String = "kotlin.coroutines.Continuation"
        const val AWT_RECTANGLE_FQN: String = "java.awt.Rectangle"
        const val NO_MESSAGE_PLACEHOLDER: String = "<no message>"

        fun targetJvmHasKeyboardFocus(): Boolean {
            val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            val focusedWindow = focusManager.focusedWindow
            val activeWindow = focusManager.activeWindow
            return (listOfNotNull(focusedWindow, activeWindow) + Window.getWindows()).any {
                it.isShowing && (it.isFocused || it.isActive)
            }
        }
    }
}
