@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.ScreenshotTarget
import dev.sebastiano.spectre.agent.resolveScreenshotTarget
import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentRequestHandler
import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.FrameLimits
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

/** Headroom for the CBOR envelope wrapped around a still's bytes before it is framed. */
private const val STILL_ENVELOPE_HEADROOM_BYTES: Int = 64 * 1024

/**
 * Largest still PNG the handler will put on the wire before dropping back to logical resolution.
 *
 * Derived from the configured frame budget so raising `SPECTRE_MAX_FRAME_BYTES` raises this too;
 * the headroom keeps a still that passes this check from failing the framing guard afterwards.
 */
private fun defaultMaxStillPngBytes(): Int =
    (FrameLimits.maxFrameBytes - STILL_ENVELOPE_HEADROOM_BYTES).coerceAtLeast(1)

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
// Snapshot/input/wait/debug ops: helpers hold most of the surface; this class is the dispatch hub.
@Suppress("TooManyFunctions", "LargeClass")
internal class ReflectiveAutomatorHandler(
    private val automator: Any,
    private val isTargetJvmFocused: () -> Boolean = ::targetJvmHasKeyboardFocus,
    private val maxStillPngBytes: Int = defaultMaxStillPngBytes(),
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
            is AgentRequest.FocusWindow -> inputOps.handleFocusWindow(request.nodeKey)
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
            is AgentRequest.WaitForIdle -> waitOps.handleWaitForIdle(request)
            AgentRequest.PrintTree -> handlePrintTree()
        }

    // Note on un-caught exceptions: any non-reflective `RuntimeException` thrown by the
    // automator (NullPointerException from a broken window list, ClassCastException from a
    // method whose JVM signature drifted, etc.) propagates to [IpcServer.handleConnection],
    // which converts it to an [AgentResponse.Error] response. Centralising that catch keeps
    // the per-op handlers readable and avoids a blanket `catch RuntimeException` here.

    private fun handleWindows(): AgentResponse {
        refreshWindowsMethod.invoke(automator)
        val windows = getWindowsMethod.invoke(automator) as List<*>
        // Per-window resilience: a single bounds/title mapping failure must not drop the whole
        // list or turn a successful discovery into an empty/error response (#362).
        return AgentResponse.Windows(windows.mapIndexedNotNull(::mapTrackedWindowResilient))
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

    private fun handlePrintTree(): AgentResponse {
        val method =
            automatorClass.methods.firstOrNull { it.name == "printTree" && it.parameterCount == 0 }
                ?: return AgentResponse.Error(
                    message = "ComposeAutomator does not expose printTree()",
                    category =
                        dev.sebastiano.spectre.agent.transport.AgentErrorCategory
                            .UnsupportedOperation
                            .wireName,
                )
        val text = (method.invoke(automator) as? String).orEmpty()
        return AgentResponse.TreeDump(text = text)
    }

    private fun handleScreenshot(request: AgentRequest.Screenshot): AgentResponse {
        if (request.nodeKey != null) {
            return handleNodeScreenshot(request)
        }
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
                    windowIndex = defaultScreenshotWindowIndex(request, windows),
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

        // Window/surface targets: fail closed rather than cropping desktop pixels (#359).
        // The attach path has no native window-surface capture; Robot region crops are
        // occlusion-prone and can capture unrelated on-screen content (privacy risk).
        if (target is ScreenshotTarget.Window) {
            return AgentResponse.Error(
                message = WINDOW_SCREENSHOT_UNSUPPORTED_MESSAGE,
                category =
                    dev.sebastiano.spectre.agent.transport.AgentErrorCategory.UnsupportedOperation
                        .wireName,
            )
        }

        val deviceScaleMethod = deviceScaleScreenshotMethodOrError()
        val regionMethod = regionScreenshotMethodOrError()
        if (deviceScaleMethod == null && regionMethod == null) {
            return AgentResponse.Error(
                message = "ComposeAutomator does not expose screenshot(Rectangle?) on this build",
                category =
                    dev.sebastiano.spectre.agent.transport.AgentErrorCategory.UnsupportedOperation
                        .wireName,
            )
        }

        // Only Fullscreen remains (explicit opt-in). null region = full virtual desktop (#289).
        check(target is ScreenshotTarget.Fullscreen)
        return AgentResponse.Screenshot(fullscreenStillPng(deviceScaleMethod, regionMethod))
    }

    /**
     * Encodes the fullscreen still, preferring screen pixels but staying inside the frame budget.
     *
     * Screen-pixel stills carry 4x the pixels of the logical ones on a 2x display, and the whole
     * virtual desktop is the one still whose size no caller bounds — a multi-monitor HiDPI desktop
     * can encode past the frame budget, which would fail the request outright. Falling back to the
     * logical still keeps a command that works today working; callers that need to know which they
     * got can compare the PNG size against the desktop's logical bounds.
     */
    private fun fullscreenStillPng(deviceScaleMethod: Method?, regionMethod: Method?): ByteArray {
        // Older injected cores expose only screenshot(Rectangle?). The caller rejects the request
        // before this point when neither method is present, so one of the two is always non-null.
        if (deviceScaleMethod == null) {
            return encodeFullscreenStill(checkNotNull(regionMethod))
        }
        val deviceScalePng = encodeFullscreenStill(deviceScaleMethod)
        if (regionMethod == null || deviceScalePng.size <= maxStillPngBytes) return deviceScalePng
        return encodeFullscreenStill(regionMethod)
    }

    private fun encodeFullscreenStill(method: Method): ByteArray =
        imageToPng(method.invoke(automator, null) as BufferedImage)

    /**
     * Capture a node's on-screen bounds (#362).
     *
     * Prefers in-process `screenshot(AutomatorNode)` (native window-scoped) when the recording
     * bridge is present and returns a non-degenerate image. Otherwise uses region capture of live
     * `boundsOnScreen` (node-key path only; window/surface attach screenshots fail closed — #359).
     * Degenerate native crops (empty intersection / DPI mishap) also fall back to region so Windows
     * desktops do not return 1×1 / ~90-byte PNGs for real nodes.
     */
    private fun handleNodeScreenshot(request: AgentRequest.Screenshot): AgentResponse {
        val nodeKey =
            request.nodeKey
                ?: return AgentResponse.Error(
                    message = "nodeKey required for node screenshot",
                    category =
                        dev.sebastiano.spectre.agent.transport.AgentErrorCategory.InvalidSelector
                            .wireName,
                )
        if (request.fullscreen || request.windowIndex != null || request.surfaceId != null) {
            return AgentResponse.Error(
                message =
                    "nodeKey screenshot cannot be combined with fullscreen, windowIndex, or surfaceId",
                category =
                    dev.sebastiano.spectre.agent.transport.AgentErrorCategory.InvalidSelector
                        .wireName,
            )
        }
        refreshWindowsMethod.invoke(automator)
        val node =
            (allNodesMethod.invoke(automator) as List<*>).firstOrNull {
                it != null && extractKey(it) == nodeKey
            }
                ?: return AgentResponse.Error(
                    message = "No node found with key=$nodeKey",
                    category =
                        dev.sebastiano.spectre.agent.transport.AgentErrorCategory.NodeNotFound
                            .wireName,
                )
        val bounds =
            extractBoundsOnScreen(node)
                ?: return AgentResponse.Error(
                    message = "Node has no boundsOnScreen for key=$nodeKey",
                    category =
                        dev.sebastiano.spectre.agent.transport.AgentErrorCategory.InternalError
                            .wireName,
                )
        if (bounds.width < 1 || bounds.height < 1) {
            return AgentResponse.Error(
                message =
                    "Node boundsOnScreen are empty for key=$nodeKey " +
                        "(${bounds.width}x${bounds.height}); cannot capture screenshot",
                category =
                    dev.sebastiano.spectre.agent.transport.AgentErrorCategory.InvalidSelector
                        .wireName,
            )
        }
        val image =
            captureNodeScreenshotPreferNative(node, bounds)
                ?: return AgentResponse.Error(
                    message =
                        "ComposeAutomator does not expose screenshot(AutomatorNode) or " +
                            "screenshot(Rectangle?) for nodeKey capture on this build",
                    category =
                        dev.sebastiano.spectre.agent.transport.AgentErrorCategory
                            .UnsupportedOperation
                            .wireName,
                )
        return AgentResponse.Screenshot(imageToPng(image))
    }

    /**
     * Prefer native window-scoped node capture; fall back to region of [bounds] when the recording
     * bridge is absent, the node overload is missing, or native returns a degenerate crop.
     *
     * Region capture uses a **defensive copy** of the AWT rectangle: live `boundsOnScreen` getters
     * return snapshots that must not be mutated by `Robot.createScreenCapture` / AWT. It prefers
     * the device-scale call so a fallback node still keeps the same screen-pixel scale the native
     * path produces; a node rectangle is small enough that the frame budget is never in play.
     */
    private fun captureNodeScreenshotPreferNative(
        node: Any,
        bounds: java.awt.Rectangle,
    ): BufferedImage? {
        val captureBounds = java.awt.Rectangle(bounds)
        val nodeScreenshotMethod = nodeScreenshotMethodOrError()
        if (nodeScreenshotMethod != null) {
            try {
                val image = nodeScreenshotMethod.invoke(automator, node) as BufferedImage
                if (isPlausibleNodeCapture(image, captureBounds)) return image
                // Native crop can be empty after DPI / coordinate mismatch — use region.
            } catch (ex: ReflectiveOperationException) {
                if (!isNativeWindowCaptureUnavailable(ex)) throw ex
            }
        }
        val regionScreenshotMethod =
            deviceScaleScreenshotMethodOrError() ?: regionScreenshotMethodOrError() ?: return null
        val image = regionScreenshotMethod.invoke(automator, captureBounds) as BufferedImage
        check(isPlausibleNodeCapture(image, captureBounds)) {
            "Region node screenshot ${image.width}x${image.height} is implausible for " +
                "boundsOnScreen ${captureBounds.width}x${captureBounds.height} at " +
                "(${captureBounds.x},${captureBounds.y})"
        }
        return image
    }

    private fun extractBoundsOnScreen(node: Any): java.awt.Rectangle? {
        val raw =
            node.javaClass.methods
                .firstOrNull { it.name == "getBoundsOnScreen" && it.parameterCount == 0 }
                ?.invoke(node) ?: return null
        return raw as? java.awt.Rectangle
    }

    /**
     * Reject empty / 1×1 native crops that are clearly not the requested node. Allow DPI scale down
     * to roughly 1/4 of logical bounds (HiDPI edge cases).
     */
    private fun isPlausibleNodeCapture(image: BufferedImage, bounds: java.awt.Rectangle): Boolean {
        if (image.width < 1 || image.height < 1) return false
        val minW = (bounds.width / 4).coerceAtLeast(1).coerceAtMost(bounds.width.coerceAtLeast(1))
        val minH = (bounds.height / 4).coerceAtLeast(1).coerceAtMost(bounds.height.coerceAtLeast(1))
        return image.width >= minW && image.height >= minH
    }

    /**
     * Only the missing recording bridge (inject attach) may fall back to region capture. Other
     * [UnsupportedOperationException]s from native capture (e.g. non-Frame hosts) must surface —
     * silent framebuffer fallback would reintroduce occluded-pixel captures.
     */
    private fun isNativeWindowCaptureUnavailable(ex: ReflectiveOperationException): Boolean {
        var current: Throwable? = ex
        while (current != null) {
            if (current.message.orEmpty().contains(NATIVE_BRIDGE_UNAVAILABLE_SNIPPET)) return true
            current = current.cause
        }
        return false
    }

    private fun regionScreenshotMethodOrError(): Method? =
        automatorClass.methods.firstOrNull {
            it.name == "screenshot" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].name == AWT_RECTANGLE_FQN
        }

    /**
     * `screenshotAtDeviceScale(region: Rectangle?)` — the screen-pixel still counterpart of
     * `screenshot(Rectangle?)`. Absent on targets running a Spectre core older than the
     * device-scale still contract, which is why callers treat it as optional.
     */
    private fun deviceScaleScreenshotMethodOrError(): Method? =
        automatorClass.methods.firstOrNull {
            it.name == "screenshotAtDeviceScale" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].name == AWT_RECTANGLE_FQN
        }

    /**
     * `screenshot(node: AutomatorNode)` — single non-primitive param that is not [Rectangle] /
     * [Int] (windowIndex overload). Agent has no compile-time `core` dependency, so match by
     * exclusion rather than FQN.
     */
    private fun nodeScreenshotMethodOrError(): Method? =
        automatorClass.methods.firstOrNull {
            it.name == "screenshot" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].name != AWT_RECTANGLE_FQN &&
                it.parameterTypes[0] != Int::class.javaPrimitiveType &&
                it.parameterTypes[0] != Int::class.javaObjectType
        }

    /**
     * Default (no windowIndex/surfaceId/nodeKey): first showing surface so delayed-show hosts
     * listed for #362 agreement are not the visual capture target.
     */
    private fun defaultScreenshotWindowIndex(
        request: AgentRequest.Screenshot,
        windows: List<*>,
    ): Int? =
        when {
            request.fullscreen || request.surfaceId != null || request.nodeKey != null ->
                request.windowIndex
            request.windowIndex != null -> request.windowIndex
            else -> firstShowingWindowIndex(windows) ?: 0
        }

    private fun firstShowingWindowIndex(windows: List<*>): Int? =
        windows
            .mapIndexedNotNull { index, tracked ->
                if (tracked != null && extractIsShowing(tracked, tracked.javaClass)) index else null
            }
            .firstOrNull()

    private fun imageToPng(image: BufferedImage): ByteArray {
        // Normalize to a PNG-friendly sRGB type; some platform Robot captures use types that
        // ImageIO encodes poorly or not at all on Windows.
        val normalized =
            if (
                image.type == BufferedImage.TYPE_INT_RGB ||
                    image.type == BufferedImage.TYPE_INT_ARGB
            ) {
                image
            } else {
                BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also { dst ->
                    val g = dst.createGraphics()
                    g.drawImage(image, 0, 0, null)
                    g.dispose()
                }
            }
        val baos = ByteArrayOutputStream()
        check(ImageIO.write(normalized, "png", baos)) {
            "ImageIO failed to encode ${normalized.width}x${normalized.height} screenshot as PNG"
        }
        return baos.toByteArray()
    }

    /**
     * Reflectively maps a `TrackedWindow` instance to its wire DTO.
     *
     * Method names match Spectre's actual API (`core/.../TrackedWindow.kt`):
     * - `getSurfaceId()` for `val surfaceId: String`
     * - `isPopup()` for `val isPopup: Boolean` (Kotlin "is" prefix convention)
     * - `getComposeSurfaceBoundsOnScreen()` for `val composeSurfaceBoundsOnScreen: Rectangle`
     * - `getWindow()` / `getWindowTitle()` for title (Frame and Dialog; bare Window → null)
     *
     * Bounds and title failures fall back rather than nulling the entry — dropping a tracked
     * surface from `windows()` while `allNodes()` still exposes `window:*` keys is the #362 bug.
     */
    private fun mapTrackedWindowResilient(index: Int, trackedWindow: Any?): WindowSummaryDto? {
        if (trackedWindow == null) return null
        // Catch broad failures so one bad surface cannot empty the whole windows() list while
        // allNodes() still exposes its keys (#362). Reflective invoke wraps target exceptions in
        // InvocationTargetException; ClassCastException / IllegalStateException can surface too.
        @Suppress("TooGenericExceptionCaught")
        return try {
            mapTrackedWindow(index, trackedWindow)
        } catch (ex: Exception) {
            System.err.println(
                "[spectre-agent] mapTrackedWindow failed for index=$index: " +
                    "${ex.javaClass.simpleName}: " +
                    ((ex as? ReflectiveOperationException)?.targetMessage() ?: ex.message)
            )
            fallbackWindowSummary(index, trackedWindow)
        }
    }

    private fun mapTrackedWindow(index: Int, trackedWindow: Any): WindowSummaryDto {
        val klass = trackedWindow.javaClass
        val surfaceId = klass.getMethod("getSurfaceId").invoke(trackedWindow) as String
        val isPopup = klass.getMethod("isPopup").invoke(trackedWindow) as Boolean
        val bounds =
            runCatching { klass.getMethod("getComposeSurfaceBoundsOnScreen").invoke(trackedWindow) }
                .getOrNull()
        val title = extractWindowTitle(trackedWindow, klass)
        return WindowSummaryDto(
            index = index,
            surfaceId = surfaceId,
            title = title,
            isPopup = isPopup,
            bounds = boundsToRect(bounds),
            isShowing = extractIsShowing(trackedWindow, klass),
        )
    }

    private fun fallbackWindowSummary(index: Int, trackedWindow: Any): WindowSummaryDto? {
        return try {
            val klass = trackedWindow.javaClass
            val surfaceId =
                klass.getMethod("getSurfaceId").invoke(trackedWindow) as? String ?: return null
            val isPopup =
                runCatching { klass.getMethod("isPopup").invoke(trackedWindow) as Boolean }
                    .getOrDefault(false)
            WindowSummaryDto(
                index = index,
                surfaceId = surfaceId,
                title = extractWindowTitle(trackedWindow, klass),
                isPopup = isPopup,
                bounds = RectDto(0, 0, 0, 0),
                isShowing = extractIsShowing(trackedWindow, klass),
            )
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    /** Reads AWT `Window.isShowing` via `TrackedWindow.getWindow()`. Defaults true if unknown. */
    private fun extractIsShowing(trackedWindow: Any, klass: Class<*>): Boolean {
        val window =
            runCatching { klass.getMethod("getWindow").invoke(trackedWindow) }.getOrNull()
                ?: return true
        return runCatching {
                window.javaClass.methods
                    .firstOrNull { it.name == "isShowing" && it.parameterCount == 0 }
                    ?.invoke(window) as? Boolean
            }
            .getOrNull() ?: true
    }

    /**
     * Prefer `TrackedWindow.windowTitle` (Frame + Dialog). Fall back to Frame/Dialog casts on
     * `getWindow()` for older core builds that lack the property.
     */
    private fun extractWindowTitle(trackedWindow: Any, klass: Class<*>): String? {
        runCatching {
                klass.methods
                    .firstOrNull { it.name == "getWindowTitle" && it.parameterCount == 0 }
                    ?.invoke(trackedWindow) as? String
            }
            .getOrNull()
            ?.let {
                return it
            }
        val window =
            runCatching { klass.getMethod("getWindow").invoke(trackedWindow) }.getOrNull()
                ?: return null
        return when (window) {
            is java.awt.Frame -> window.title
            is java.awt.Dialog -> window.title
            else -> null
        }
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
        /** Matches core ScreenCaptureBackend when NativeWindowCaptureBridge is not loadable. */
        const val NATIVE_BRIDGE_UNAVAILABLE_SNIPPET: String =
            "Native window capture bridge is unavailable"
        /**
         * Fail-closed message for attach/CLI window or surface screenshots (#359). Must mention
         * occlusion/privacy risk and point callers at explicit fullscreen opt-in.
         */
        const val WINDOW_SCREENSHOT_UNSUPPORTED_MESSAGE: String =
            "Window/surface screenshots are not supported on the attach path: they would crop " +
                "occlusion-prone desktop pixels (privacy risk if another window covers the " +
                "target). Use --fullscreen (or fullscreen=true) for an explicit full-desktop " +
                "capture."
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
