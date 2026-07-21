package dev.sebastiano.spectre.core

import java.awt.Component
import java.awt.Window
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Best-effort resolution of a platform-native top-level window handle from an AWT [Window].
 *
 * Uses public Compose Desktop `windowHandle` when present and non-zero; otherwise reflects into the
 * AWT peer (HWND / NSWindow* / X11 Window) the same way IntelliJ Platform does. Returns `null` when
 * the peer is missing or the platform path is unknown — callers must treat null as "no handle" and
 * fall back (title+pid window match, or region capture).
 */
internal object NativeWindowHandle {

    fun resolve(window: Window): Long? {
        if (!window.isDisplayable) return null
        composeWindowHandle(window)?.let { handle -> if (handle != 0L) return handle }
        return awtPeerHandle(window)
    }

    /**
     * Compose Desktop exposes `windowHandle` on `ComposeWindow` / `ComposeDialog`. Swing-hosted
     * `ComposePanel` layers report `0` (see Compose's `SwingSkiaLayerComponent`).
     */
    private fun composeWindowHandle(window: Window): Long? =
        runCatching {
                val method =
                    window.javaClass.methods.firstOrNull {
                        it.name == "getWindowHandle" &&
                            it.parameterCount == 0 &&
                            (it.returnType == Long::class.javaPrimitiveType ||
                                it.returnType == Long::class.javaObjectType)
                    } ?: return null
                (method.invoke(window) as Number).toLong()
            }
            .getOrNull()

    private fun awtPeerHandle(window: Window): Long? {
        val peer = peerOf(window) ?: return null
        windowsHwnd(peer)?.let {
            return it
        }
        macOsNsWindow(peer)?.let {
            return it
        }
        x11Window(peer)?.let {
            return it
        }
        return null
    }

    private fun peerOf(window: Window): Any? =
        runCatching {
                val accessorClass = Class.forName("sun.awt.AWTAccessor")
                val componentAccessor = accessorClass.getMethod("getComponentAccessor").invoke(null)
                val getPeer: Method =
                    componentAccessor.javaClass.getMethod("getPeer", Component::class.java)
                getPeer.isAccessible = true
                getPeer.invoke(componentAccessor, window)
            }
            .getOrNull()
            ?: runCatching {
                    val getPeer = Component::class.java.getDeclaredMethod("getPeer")
                    getPeer.isAccessible = true
                    getPeer.invoke(window)
                }
                .getOrNull()

    private fun windowsHwnd(peer: Any): Long? =
        runCatching {
                val method =
                    peer.javaClass.methods.firstOrNull { it.name == "getHWnd" } ?: return null
                (method.invoke(peer) as Number).toLong()
            }
            .getOrNull()

    private fun macOsNsWindow(peer: Any): Long? =
        runCatching {
                val getPlatformWindow =
                    peer.javaClass.methods.firstOrNull {
                        it.name == "getPlatformWindow" && it.parameterCount == 0
                    } ?: return null
                val platformWindow = getPlatformWindow.invoke(peer) ?: return null
                val ptrField: Field =
                    platformWindow.javaClass.superclass?.getDeclaredField("ptr") ?: return null
                ptrField.isAccessible = true
                ptrField.getLong(platformWindow).takeIf { it != 0L }
            }
            .getOrNull()

    private fun x11Window(peer: Any): Long? =
        runCatching {
                // Prefer walking to XBaseWindow.getWindow when peer is an XComponentPeer.
                var current: Any? = peer
                var depth = 0
                while (current != null && depth < MAX_X11_PEER_WALK_DEPTH) {
                    val getWindow =
                        current.javaClass.methods.firstOrNull {
                            it.name == "getWindow" &&
                                it.parameterCount == 0 &&
                                (it.returnType == Long::class.javaPrimitiveType ||
                                    it.returnType == Long::class.javaObjectType)
                        }
                    if (getWindow != null) {
                        val value = (getWindow.invoke(current) as Number).toLong()
                        if (value != 0L) return value
                    }
                    current =
                        current.javaClass.methods
                            .firstOrNull { it.name == "getContentWindow" && it.parameterCount == 0 }
                            ?.invoke(current)
                    depth++
                }
                null
            }
            .getOrNull()

    /** Cap peer-chain walks so a pathological hierarchy cannot spin forever. */
    private const val MAX_X11_PEER_WALK_DEPTH: Int = 6
}
