@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.ScreenshotTarget
import dev.sebastiano.spectre.agent.resolveScreenshotTarget
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
// Snapshot/input/wait ops each live in helpers where possible; remaining private methods stay
// under detekt's function-count budget.
@Suppress("TooManyFunctions")
internal class ReflectiveAutomatorHandler(
    private val automator: Any,
    private val isTargetJvmFocused: () -> Boolean = ::targetJvmHasKeyboardFocus,
) : AgentRequestHandler {

    private val automatorClass: Class<*> = automator.javaClass
    private val getWindowsMethod = automatorClass.getMethod("getWindows")
    private val allNodesMethod = automatorClass.getMethod("allNodes")
    private val findByTestTagMethod = automatorClass.getMethod("findByTestTag", String::class.java)
    private val findByTextMethod =
        automatorClass.methods.firstOrNull {
            it.name == "findByText" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }
    private val findByContentDescriptionMethod =
        automatorClass.methods.firstOrNull {
            it.name == "findByContentDescription" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
        }
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
    private val waitOps = WaitOpsReflectiveMapper(automator, suspendInvoker, ::mapAutomatorNode)
    private val inputOps =
        InputOpsReflectiveMapper(
            automator = automator,
            suspendInvoker = suspendInvoker,
            refreshWindows = { refreshWindowsMethod.invoke(automator) },
            allNodes = { allNodesMethod.invoke(automator) as List<*> },
            extractKey = ::extractKey,
            isTargetJvmFocused = isTargetJvmFocused,
        )

    override fun handle(request: AgentRequest): AgentResponse =
        try {
            dispatch(request)
        } catch (ex: ReflectiveOperationException) {
            val message = "Reflective call failed: ${ex.targetMessage()}"
            val category =
                if (reflectiveIsInputRejection(ex)) {
                    dev.sebastiano.spectre.agent.transport.AgentErrorCategory.InputRejected
                } else {
                    dev.sebastiano.spectre.agent.transport.AgentErrorCategory.InternalError
                }
            AgentResponse.Error(message = message, category = category.wireName)
        } catch (ex: java.util.concurrent.TimeoutException) {
            AgentResponse.Error(
                message = "${ex.javaClass.simpleName}: ${ex.message ?: "<no message>"}",
                category =
                    dev.sebastiano.spectre.agent.transport.AgentErrorCategory.Timeout.wireName,
            )
        }

    @Suppress("CyclomaticComplexMethod") // Exhaustive wire dispatch table.
    private fun dispatch(request: AgentRequest): AgentResponse =
        when (request) {
            AgentRequest.Ping -> AgentResponse.Pong
            AgentRequest.Windows -> handleWindows()
            AgentRequest.AllNodes -> handleAllNodes()
            is AgentRequest.FindByTestTag -> handleFindByTestTag(request.tag)
            is AgentRequest.FindByText -> handleFindByText(request)
            is AgentRequest.FindByContentDescription ->
                handleFindByContentDescription(request.description)
            is AgentRequest.FindByRole -> handleFindByRole(request.role)
            is AgentRequest.Click -> handleClick(request.nodeKey)
            is AgentRequest.DoubleClick -> inputOps.handleDoubleClick(request.nodeKey)
            is AgentRequest.LongClick -> inputOps.handleLongClick(request)
            is AgentRequest.Swipe -> inputOps.handleSwipe(request)
            is AgentRequest.ScrollWheel -> inputOps.handleScrollWheel(request)
            is AgentRequest.PressKey -> inputOps.handlePressKey(request)
            is AgentRequest.TypeText -> handleTypeText(request.text)
            is AgentRequest.Screenshot -> handleScreenshot(request)
            is AgentRequest.Capture ->
                AtomicCaptureReflectiveMapper.invoke(automator, request.windowIndex)
            is AgentRequest.WindowIdentity ->
                WindowIdentityReflectiveMapper.invoke(automator, request.windowIndex)
            AgentRequest.Detach -> AgentResponse.Detached
            // Handled in IpcServer before the automator handler; keep exhaustive.
            is AgentRequest.Hello ->
                AgentResponse.HelloAck(
                    protocolVersion = dev.sebastiano.spectre.agent.transport.ProtocolVersion.CURRENT
                )
            is AgentRequest.Cancel -> AgentResponse.Ok
            is AgentRequest.WaitForNode -> waitOps.handleWaitForNode(request)
            is AgentRequest.WaitForVisualIdle -> waitOps.handleWaitForVisualIdle(request)
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

    private fun handleFindByText(request: AgentRequest.FindByText): AgentResponse {
        val method =
            findByTextMethod
                ?: return AgentResponse.Error(
                    message = "ComposeAutomator does not expose findByText(text, exact)",
                    category =
                        dev.sebastiano.spectre.agent.transport.AgentErrorCategory
                            .UnsupportedOperation
                            .wireName,
                )
        // Empty text is valid in-process (exact match on empty EditableText). Whitespace-only is
        // almost never intentional and diverges from useful substring semantics — reject it.
        if (request.text.isNotEmpty() && request.text.isBlank()) {
            return AgentResponse.Error(
                message = "text must not be whitespace-only",
                category =
                    dev.sebastiano.spectre.agent.transport.AgentErrorCategory.InvalidSelector
                        .wireName,
            )
        }
        refreshWindowsMethod.invoke(automator)
        val nodes = method.invoke(automator, request.text, request.exact) as List<*>
        return AgentResponse.Nodes(nodes.mapNotNull { it?.let(::mapAutomatorNode) })
    }

    private fun handleFindByContentDescription(description: String): AgentResponse {
        if (description.isBlank()) {
            return AgentResponse.Error(
                message = "description must be non-blank",
                category =
                    dev.sebastiano.spectre.agent.transport.AgentErrorCategory.InvalidSelector
                        .wireName,
            )
        }
        val method =
            findByContentDescriptionMethod
                ?: return AgentResponse.Error(
                    message = "ComposeAutomator does not expose findByContentDescription",
                    category =
                        dev.sebastiano.spectre.agent.transport.AgentErrorCategory
                            .UnsupportedOperation
                            .wireName,
                )
        refreshWindowsMethod.invoke(automator)
        val nodes = method.invoke(automator, description) as List<*>
        return AgentResponse.Nodes(nodes.mapNotNull { it?.let(::mapAutomatorNode) })
    }

    private fun handleFindByRole(roleName: String): AgentResponse {
        if (roleName.isBlank() || roleName !in KNOWN_ROLE_WIRE_NAMES) {
            return AgentResponse.Error(
                message =
                    if (roleName.isBlank()) "role must be non-blank"
                    else "unknown role name: $roleName",
                category =
                    dev.sebastiano.spectre.agent.transport.AgentErrorCategory.InvalidSelector
                        .wireName,
            )
        }
        // Role is a Compose value class; match by role.toString() on the snapshot to avoid
        // packing Role constants reflectively. Names match Role.toString() (ValuePicker →
        // "Picker").
        refreshWindowsMethod.invoke(automator)
        val nodes = allNodesMethod.invoke(automator) as List<*>
        val matches =
            nodes.mapNotNull { it?.let(::mapAutomatorNode) }.filter { it.role == roleName }
        return AgentResponse.Nodes(matches)
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
                ?: return AgentResponse.Error(
                    message = "No node found with key=$nodeKey",
                    category =
                        dev.sebastiano.spectre.agent.transport.AgentErrorCategory.NodeNotFound
                            .wireName,
                )
        val method =
            clickSuspendMethod
                ?: return AgentResponse.Error(
                    message = "ComposeAutomator does not expose a click(node) method",
                    category =
                        dev.sebastiano.spectre.agent.transport.AgentErrorCategory
                            .UnsupportedOperation
                            .wireName,
                )
        suspendInvoker.invoke(method, automator, match)
        return AgentResponse.Ok
    }

    private fun handleTypeText(text: String): AgentResponse {
        val method =
            typeTextSuspendMethod
                ?: return AgentResponse.Error(
                    message = "ComposeAutomator does not expose a typeText(text) method",
                    category =
                        dev.sebastiano.spectre.agent.transport.AgentErrorCategory
                            .UnsupportedOperation
                            .wireName,
                )
        refreshWindowsMethod.invoke(automator)
        val allNodes = allNodesMethod.invoke(automator) as List<*>
        val focusedNodes =
            allNodes.filterNotNull().filter { nodeBooleanProperty(it, methodName = "isFocused") }
        if (focusedNodes.isEmpty()) {
            return AgentResponse.Error(
                message =
                    "Refusing typeText because no focused Spectre node was found in the " +
                        "target JVM. Focus a target node before sending real keyboard events.",
                category =
                    dev.sebastiano.spectre.agent.transport.AgentErrorCategory.InputRejected.wireName,
            )
        }
        if (focusedNodes.none { extractKey(it).isNotBlank() }) {
            return AgentResponse.Error(
                message = "Refusing typeText because every focused Spectre node has a blank key.",
                category =
                    dev.sebastiano.spectre.agent.transport.AgentErrorCategory.InputRejected.wireName,
            )
        }
        if (!isTargetJvmFocused()) {
            return AgentResponse.Error(
                message =
                    "Refusing typeText because the target JVM does not currently own OS keyboard " +
                        "focus. Activate the target window before sending real keyboard events.",
                category =
                    dev.sebastiano.spectre.agent.transport.AgentErrorCategory.InputRejected.wireName,
            )
        }
        suspendInvoker.invoke(method, automator, text)
        return AgentResponse.Ok
    }

    private fun handleScreenshot(request: AgentRequest.Screenshot): AgentResponse {
        refreshWindowsMethod.invoke(automator)
        val windows = getWindowsMethod.invoke(automator) as List<*>
        val windowSummaries = windows.mapIndexedNotNull { index, tracked ->
            val surfaceId =
                tracked?.javaClass?.getMethod("getSurfaceId")?.invoke(tracked) as? String
                    ?: return@mapIndexedNotNull null
            index to surfaceId
        }
        val target =
            resolveScreenshotTarget(
                    fullscreen = request.fullscreen,
                    windowIndex = request.windowIndex,
                    surfaceId = request.surfaceId,
                    windows = windowSummaries,
                )
                .getOrElse {
                    return AgentResponse.Error(
                        message = it.message ?: "Invalid screenshot request",
                        category =
                            dev.sebastiano.spectre.agent.transport.AgentErrorCategory
                                .InvalidSelector
                                .wireName,
                    )
                }

        val regionScreenshotMethod =
            automatorClass.methods.firstOrNull {
                it.name == "screenshot" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name == AWT_RECTANGLE_FQN
            }
                ?: return AgentResponse.Error(
                    message =
                        "ComposeAutomator does not expose screenshot(Rectangle?) on this build",
                    category =
                        dev.sebastiano.spectre.agent.transport.AgentErrorCategory
                            .UnsupportedOperation
                            .wireName,
                )

        val image =
            when (target) {
                is ScreenshotTarget.Fullscreen -> {
                    // null region = full virtual desktop. Explicit opt-in only (#289).
                    regionScreenshotMethod.invoke(automator, null) as BufferedImage
                }
                is ScreenshotTarget.Window -> {
                    // Capture bounds from the window list we just resolved against. Do not call
                    // screenshot(windowIndex): that path refreshes windows again and can bind a
                    // different surface at the same index after a popup open/close (#289 P2).
                    val tracked =
                        windows.getOrNull(target.windowIndex)
                            ?: return AgentResponse.Error(
                                "No tracked window at index ${target.windowIndex} (have ${windows.size})"
                            )
                    val bounds =
                        tracked.javaClass
                            .getMethod("getComposeSurfaceBoundsOnScreen")
                            .invoke(tracked)
                    regionScreenshotMethod.invoke(automator, bounds) as BufferedImage
                }
            }
        return AgentResponse.Screenshot(imageToPng(image))
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
        val descriptions =
            (klass.methods
                    .firstOrNull { it.name == "getContentDescriptions" && it.parameterCount == 0 }
                    ?.invoke(node) as? List<*>)
                ?.filterIsInstance<String>()
                .orEmpty()
        val singularDescription =
            descriptions.firstOrNull()
                ?: klass.methods
                    .firstOrNull { it.name == "getContentDescription" && it.parameterCount == 0 }
                    ?.invoke(node) as? String
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
            role = mapRoleWireName(node),
            contentDescription = singularDescription,
            contentDescriptions = descriptions.ifEmpty { listOfNotNull(singularDescription) },
            isFocused = nodeBooleanProperty(node, methodName = "isFocused", default = false),
            isDisabled = nodeBooleanProperty(node, methodName = "isDisabled", default = false),
            isSelected = nodeBooleanProperty(node, methodName = "isSelected", default = false),
            // isVisible is part of the long-standing AutomatorNode contract — fail loudly if
            // absent.
            isVisible = requireNodeBoolean(node, methodName = "isVisible"),
            bounds =
                boundsToRect(
                    // `AutomatorNode.boundsOnScreen: Rectangle` → getBoundsOnScreen().
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

    /**
     * Maps Compose `Role` to its wire name. Kotlin mangles the getter for the value class
     * (`getRole- RLKlGQI`), and reflective invoke may return a boxed Role or a raw Int ordinal.
     */
    private fun mapRoleWireName(node: Any): String? {
        val method =
            node.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (it.name == "getRole" || it.name.startsWith("getRole-"))
            } ?: return null
        val raw = method.invoke(node) ?: return null
        if (raw is Number) {
            return ROLE_ORDINAL_WIRE_NAMES[raw.toInt()]
        }
        val asString = raw.toString()
        if (asString in KNOWN_ROLE_WIRE_NAMES) return asString
        return asString.takeUnless { it == "null" || it.all { ch -> ch.isDigit() } }
            ?: (raw as? Number)?.toInt()?.let { ROLE_ORDINAL_WIRE_NAMES[it] }
    }

    /**
     * Soft boolean lookup: missing accessors return [default]. Used for optional snapshot fields
     * (`isDisabled` / `isSelected` / `isFocused`) so older fakes and partial test doubles still map
     * cleanly.
     */
    private fun nodeBooleanProperty(
        node: Any,
        methodName: String,
        default: Boolean = false,
    ): Boolean {
        val method = resolveBooleanMethod(node, methodName)
        if (method === MISSING_BOOLEAN_METHOD) return default
        return method.invoke(node) as Boolean
    }

    /** Hard boolean lookup: missing accessor is an AutomatorNode API mismatch. */
    private fun requireNodeBoolean(node: Any, methodName: String): Boolean {
        val method = resolveBooleanMethod(node, methodName)
        if (method === MISSING_BOOLEAN_METHOD) {
            error(
                "AutomatorNode API mismatch: ${node.javaClass.name} does not expose $methodName()"
            )
        }
        return method.invoke(node) as Boolean
    }

    private fun resolveBooleanMethod(node: Any, methodName: String): Method =
        nodeBooleanMethods.computeIfAbsent(node.javaClass to methodName) { (klass, name) ->
            klass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                ?: MISSING_BOOLEAN_METHOD
        }

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
        /** Sentinel when a fake/partial AutomatorNode lacks an optional boolean getter. */
        val MISSING_BOOLEAN_METHOD: Method = Object::class.java.getMethod("hashCode")

        /**
         * Compose [androidx.compose.ui.semantics.Role] wire names — the strings [Role.toString]
         * returns. Agent has no compile-time Compose dependency, so the set is duplicated here.
         * Note [Role.ValuePicker] stringifies as `"Picker"`, not `"ValuePicker"`.
         */
        val KNOWN_ROLE_WIRE_NAMES: Set<String> =
            setOf(
                "Button",
                "Checkbox",
                "Switch",
                "RadioButton",
                "Tab",
                "Image",
                "DropdownList",
                "Picker",
                "Carousel",
            )

        /** Compose Role constructor values → Role.toString() names (ValuePicker → Picker). */
        val ROLE_ORDINAL_WIRE_NAMES: Map<Int, String> =
            mapOf(
                0 to "Button",
                1 to "Checkbox",
                2 to "Switch",
                3 to "RadioButton",
                4 to "Tab",
                5 to "Image",
                6 to "DropdownList",
                7 to "Picker",
                8 to "Carousel",
            )

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

/** TCC / Accessibility / permission refusals from Robot — taxonomy inputRejected (#199). */
private fun reflectiveIsInputRejection(ex: ReflectiveOperationException): Boolean {
    val root = ex.cause ?: ex
    if (root !is IllegalStateException) return false
    val msg = root.message.orEmpty().lowercase()
    return "accessibility" in msg ||
        "tcc" in msg ||
        "permission" in msg ||
        "screen recording" in msg
}
