package dev.sebastiano.spectre.sample

import java.awt.Component
import java.awt.Window
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Whether [frame] is the window keystrokes will hit.
 *
 * Call on the EDT. `Window.isFocused` is false on Windows when the Compose Skia child HWND owns
 * focus; [Window.isActive] and the OS foreground root still describe that fixture.
 */
internal fun fixtureIsInFront(frame: Window?): Boolean {
    if (frame == null) return false
    return fixtureWindowIsInFront(
        awtFocused = frame.isFocused,
        awtActive = frame.isActive,
        osForegroundIsFrameOrChild = windowsOsForegroundBelongsTo(frame),
    )
}

internal fun windowsOsForegroundBelongsTo(frame: Window): Boolean {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return false
    val frameHwnd = awtFrameHwnd(frame) ?: return false
    val foregroundRoot = WindowsUser32.foregroundRootHwnd() ?: return false
    return foregroundBelongsToFrame(frameHwnd, foregroundRoot)
}

private fun awtFrameHwnd(frame: Window): Long? = runCatching {
    val accessorClass = Class.forName("sun.awt.AWTAccessor")
    val componentAccessor = accessorClass.getMethod("getComponentAccessor").invoke(null)
    val getPeer = componentAccessor.javaClass.getMethod("getPeer", Component::class.java)
    getPeer.isAccessible = true
    val peer = getPeer.invoke(componentAccessor, frame) ?: return null
    val getHWnd = peer.javaClass.methods.firstOrNull { it.name == "getHWnd" } ?: return null
    (getHWnd.invoke(peer) as Number).toLong().takeIf { it != 0L }
}
    .getOrNull()

/**
 * `GetForegroundWindow` + `GetAncestor(GA_ROOT)` so a Skia child HWND still matches its frame. Any
 * failure returns null and the gate falls back to the AWT active/focused flags.
 */
private object WindowsUser32 {
    private const val GA_ROOT: Int = 2

    fun foregroundRootHwnd(): Long? = runCatching {
        val foreground = invokeHwnd(foregroundWindow) ?: return null
        val rootHandle = ancestor ?: return foreground
        val segment =
            rootHandle.invoke(MemorySegment.ofAddress(foreground), GA_ROOT) as MemorySegment
        segment.address().takeIf { it != 0L }
    }
        .getOrNull()

    private val foregroundWindow: MethodHandle? by lazy {
        downcall("GetForegroundWindow", FunctionDescriptor.of(ValueLayout.ADDRESS))
    }

    private val ancestor: MethodHandle? by lazy {
        downcall(
            "GetAncestor",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
        )
    }

    private fun invokeHwnd(handle: MethodHandle?): Long? {
        if (handle == null) return null
        val segment = handle.invoke() as MemorySegment
        return segment.address().takeIf { it != 0L }
    }

    private fun downcall(name: String, descriptor: FunctionDescriptor): MethodHandle? =
        runCatching {
            val linker = Linker.nativeLinker()
            val lookup = SymbolLookup.libraryLookup("user32.dll", Arena.global())
            val address = lookup.find(name).orElse(null) ?: return null
            linker.downcallHandle(address, descriptor)
        }
        .getOrNull()
}
