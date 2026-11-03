@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.ProgressRangeDto
import dev.sebastiano.spectre.agent.transport.RectDto
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Reads the node state that `AutomatorNode` exposes beyond text and flags (#576): toggleable state,
 * progress range, tree links, and both bounds in one EDT read.
 *
 * Every accessor is optional. A runtime-side `AutomatorNode` built before #576, or a partial test
 * stand-in, maps to the wire defaults instead of failing. Lives beside [ReflectiveAutomatorHandler]
 * so the handler stays under the Detekt function-count budget.
 */
internal class NodeStateReflectiveMapper {

    private val getters = ConcurrentHashMap<Pair<Class<*>, String>, Method>()

    /**
     * Compose `ToggleableState` enum name (`On`, `Off`, `Indeterminate`), or `null` when the node
     * is not toggleable. The enum comes from the target app's class loader, so read its name rather
     * than casting to Compose's type.
     */
    fun toggleableState(node: Any): String? =
        when (val raw = invokeGetter(node, "getToggleableState")) {
            null -> null
            is Enum<*> -> raw.name
            else -> raw.toString()
        }

    /**
     * Compose `ProgressBarRangeInfo` as a [ProgressRangeDto], or `null` when the node has no
     * progress semantics. Its `range` is a stdlib `ClosedFloatingPointRange` whose implementation
     * class is not public, so [invokeGetter] reads `start` / `endInclusive` through the public
     * interface.
     */
    fun progress(node: Any): ProgressRangeDto? {
        val info = invokeGetter(node, "getProgressBarRangeInfo") ?: return null
        val current = invokeGetter(info, "getCurrent") as? Number ?: return null
        val range = invokeGetter(info, "getRange") ?: return null
        val min = invokeGetter(range, "getStart") as? Number ?: return null
        val max = invokeGetter(range, "getEndInclusive") as? Number ?: return null
        val steps = invokeGetter(info, "getSteps") as? Number
        return ProgressRangeDto(
            current = current.toFloat(),
            min = min.toFloat(),
            max = max.toFloat(),
            steps = steps?.toInt() ?: 0,
        )
    }

    /**
     * Key of the node's semantics parent, or `null` for a root (or a node without the accessor).
     */
    fun parentKey(node: Any, extractKey: (Any) -> String): String? =
        invokeGetter(node, "getParent")?.let(extractKey)

    /** Keys of the node's semantics children, in tree order. */
    fun childKeys(node: Any, extractKey: (Any) -> String): List<String> =
        (invokeGetter(node, "getChildren") as? List<*>).orEmpty().mapNotNull { it?.let(extractKey) }

    /**
     * Calls `AutomatorNode.bothBounds()` so in-window and on-screen bounds come from one EDT read.
     * Returns `null` when the node does not expose it; callers then fall back to
     * `getBoundsOnScreen()` alone.
     */
    fun bothBounds(node: Any): BothBoundsSnapshot? {
        val both = invokeGetter(node, "bothBounds") ?: return null
        return BothBoundsSnapshot(
            inWindow = invokeGetter(both, "getInWindow")?.let(::composeRectToRectDto),
            onScreen = invokeGetter(both, "getOnScreen"),
        )
    }

    /** Bounds read by [bothBounds]: [onScreen] is the raw `java.awt.Rectangle`. */
    data class BothBoundsSnapshot(val inWindow: RectDto?, val onScreen: Any?)

    private fun invokeGetter(target: Any, name: String): Any? {
        val method =
            getters.computeIfAbsent(target.javaClass to name) { (klass, methodName) ->
                publicGetter(klass, methodName) ?: MISSING_GETTER
            }
        if (method === MISSING_GETTER) return null
        return method.invoke(target)
    }

    /**
     * Compose `Rect` (float left/top/right/bottom, physical pixels) rounded to an integer
     * [RectDto]. Edges are rounded before taking the size, so adjacent nodes stay adjacent.
     */
    private fun composeRectToRectDto(rect: Any): RectDto? {
        val left = (invokeGetter(rect, "getLeft") as? Number)?.toFloat()
        val top = (invokeGetter(rect, "getTop") as? Number)?.toFloat()
        val right = (invokeGetter(rect, "getRight") as? Number)?.toFloat()
        val bottom = (invokeGetter(rect, "getBottom") as? Number)?.toFloat()
        val edges = listOfNotNull(left, top, right, bottom)
        if (edges.size != RECT_EDGE_COUNT || !edges.all { it.isFinite() }) return null
        val x = checkNotNull(left).roundToInt()
        val y = checkNotNull(top).roundToInt()
        return RectDto(
            x = x,
            y = y,
            width = checkNotNull(right).roundToInt() - x,
            height = checkNotNull(bottom).roundToInt() - y,
        )
    }

    private companion object {
        /** Cache sentinel for a getter the target class does not expose. */
        val MISSING_GETTER: Method = Object::class.java.getMethod("hashCode")

        const val RECT_EDGE_COUNT: Int = 4

        /**
         * Finds a no-arg [name] method on [klass], preferring a declaration on a public type so the
         * call works from outside [klass]'s package. Stdlib range implementations, for example, are
         * private classes behind the public `ClosedRange` interface. Falls back to [klass]'s own
         * method when no public type declares it.
         */
        fun publicGetter(klass: Class<*>, name: String): Method? {
            val own =
                klass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?: return null
            if (Modifier.isPublic(own.declaringClass.modifiers)) return own
            return publicTypes(klass).firstNotNullOfOrNull { type ->
                type.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            } ?: own
        }

        fun publicTypes(klass: Class<*>): Sequence<Class<*>> = sequence {
            val seen = mutableSetOf<Class<*>>()
            val pending = ArrayDeque<Class<*>>().apply { add(klass) }
            while (pending.isNotEmpty()) {
                val type = pending.removeFirst()
                if (!seen.add(type)) continue
                if (Modifier.isPublic(type.modifiers)) yield(type)
                type.superclass?.let(pending::add)
                pending.addAll(type.interfaces)
            }
        }
    }
}
