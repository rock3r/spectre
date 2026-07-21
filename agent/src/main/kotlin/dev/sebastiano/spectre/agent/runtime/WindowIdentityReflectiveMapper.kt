@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.agent.transport.WindowIdentityDto

/**
 * Invokes `ComposeAutomator.windowIdentities()` reflectively and maps results onto
 * [AgentResponse.WindowIdentities].
 *
 * Lives beside [AtomicCaptureReflectiveMapper] so [ReflectiveAutomatorHandler] stays under the
 * Detekt function-count budget.
 */
internal object WindowIdentityReflectiveMapper {

    fun invoke(automator: Any, windowIndex: Int?): AgentResponse {
        val listMethod =
            automator.javaClass.methods.firstOrNull {
                it.name == "windowIdentities" && it.parameterCount == 0
            }
                ?: return AgentResponse.Error(
                    "ComposeAutomator does not expose windowIdentities() on this build"
                )
        val all = listMethod.invoke(automator) as List<*>
        val selected = if (windowIndex == null) all else listOfNotNull(all.getOrNull(windowIndex))
        return AgentResponse.WindowIdentities(selected.mapNotNull { it?.let(::mapWindowIdentity) })
    }

    /**
     * Maps core `WindowIdentitySnapshot` (or any stand-in with matching getters) to the wire DTO.
     *
     * Getter names match `core/.../WindowIdentitySnapshot.kt`.
     */
    private fun mapWindowIdentity(snapshot: Any): WindowIdentityDto {
        val klass = snapshot.javaClass
        return WindowIdentityDto(
            index = invokeInt(klass, snapshot, "getIndex"),
            surfaceId = invokeString(klass, snapshot, "getSurfaceId"),
            title = invokeStringOrNull(klass, snapshot, "getTitle"),
            isPopup = invokeBoolean(klass, snapshot, "isPopup"),
            nativeHandle = invokeLongOrNull(klass, snapshot, "getNativeHandle"),
            cropRequired = invokeBoolean(klass, snapshot, "getCropRequired"),
            windowBoundsOnScreen =
                boundsToRect(invokeAny(klass, snapshot, "getWindowBoundsOnScreen")),
            surfaceBoundsOnScreen =
                boundsToRect(invokeAny(klass, snapshot, "getSurfaceBoundsOnScreen")),
            surfaceBoundsInWindow =
                boundsToRect(invokeAny(klass, snapshot, "getSurfaceBoundsInWindow")),
            scaleX = invokeDouble(klass, snapshot, "getScaleX"),
            scaleY = invokeDouble(klass, snapshot, "getScaleY"),
        )
    }

    private fun invokeInt(klass: Class<*>, target: Any, name: String): Int =
        (klass.getMethod(name).invoke(target) as Number).toInt()

    private fun invokeDouble(klass: Class<*>, target: Any, name: String): Double =
        (klass.getMethod(name).invoke(target) as Number).toDouble()

    private fun invokeBoolean(klass: Class<*>, target: Any, name: String): Boolean =
        klass.getMethod(name).invoke(target) as Boolean

    private fun invokeString(klass: Class<*>, target: Any, name: String): String =
        klass.getMethod(name).invoke(target) as String

    private fun invokeStringOrNull(klass: Class<*>, target: Any, name: String): String? =
        klass.getMethod(name).invoke(target) as? String

    private fun invokeLongOrNull(klass: Class<*>, target: Any, name: String): Long? =
        klass.getMethod(name).invoke(target) as? Long

    private fun invokeAny(klass: Class<*>, target: Any, name: String): Any? =
        klass.getMethod(name).invoke(target)

    private fun boundsToRect(bounds: Any?): RectDto {
        if (bounds == null) return RectDto(0, 0, 0, 0)
        val klass = bounds.javaClass
        return runCatching {
                RectDto(
                    x = (klass.getMethod("getX").invoke(bounds) as Number).toInt(),
                    y = (klass.getMethod("getY").invoke(bounds) as Number).toInt(),
                    width = (klass.getMethod("getWidth").invoke(bounds) as Number).toInt(),
                    height = (klass.getMethod("getHeight").invoke(bounds) as Number).toInt(),
                )
            }
            .getOrDefault(RectDto(0, 0, 0, 0))
    }
}
