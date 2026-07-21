package dev.sebastiano.spectre.agent.runtime

import java.lang.instrument.Instrumentation

/**
 * Opens and exports `java.desktop` internal packages so Spectre core can resolve native window
 * handles via AWT peer reflection (`sun.awt.AWTAccessor`, platform peers).
 *
 * Modular JDKs do not export `sun.awt` / `sun.lwawt` / `sun.awt.X11` to unnamed modules. Without
 * these grants, embedded `ComposePanel` surfaces (Compose `windowHandle == 0`) report `nativeHandle
 * = null` and daemon window+crop recording cannot bind to the host frame.
 *
 * Called once from [SpectreAgent] bootstrap. Opens to **every** module that has already loaded a
 * Spectre class (and the target classloader's unnamed module), because each classloader has its own
 * unnamed module and peer reflection runs in the target's core classloader — not the agent's.
 */
internal object AwtPeerModuleOpener {

    fun openFor(targetClassLoader: ClassLoader, instrumentation: Instrumentation) {
        val desktop =
            ModuleLayer.boot().findModule(JAVA_DESKTOP).orElse(null)
                ?: run {
                    System.err.println(
                        "[spectre-agent] java.desktop module not found; skipping AWT peer opens"
                    )
                    return
                }
        val consumers = collectConsumerModules(targetClassLoader, instrumentation)
        if (consumers.isEmpty()) {
            System.err.println("[spectre-agent] no consumer modules for AWT peer opens; skipping")
            return
        }
        // Only request packages that exist on this JDK/OS. Passing missing packages
        // (e.g. sun.awt.windows on macOS) makes redefineModule reject the whole batch.
        val presentPackages = AWT_PEER_PACKAGES.filter { pkg -> desktop.packages.contains(pkg) }
        if (presentPackages.isEmpty()) {
            System.err.println(
                "[spectre-agent] no AWT peer packages present on this JDK; skipping opens"
            )
            return
        }
        val grants = presentPackages.associateWith { consumers }
        try {
            // Export + open: reflective Method.invoke fails with "does not export" unless
            // exported; private peer fields (e.g. CFRetainedResource.ptr) need opens.
            instrumentation.redefineModule(
                desktop,
                /* extraReads = */ emptySet(),
                /* extraExports = */ grants,
                /* extraOpens = */ grants,
                /* extraUses = */ emptySet(),
                /* extraProvides = */ emptyMap(),
            )
            System.err.println(
                "[spectre-agent] exported+opened AWT peer packages to " +
                    "${consumers.joinToString { describeModule(it) }}: " +
                    presentPackages.joinToString()
            )
        } catch (ex: IllegalArgumentException) {
            logOpenFailure(ex)
        } catch (ex: IllegalStateException) {
            logOpenFailure(ex)
        } catch (ex: SecurityException) {
            logOpenFailure(ex)
        }
    }

    private fun logOpenFailure(ex: Throwable) {
        System.err.println(
            "[spectre-agent] failed to export/open AWT peer packages " +
                "(native window handles may be null): ${ex.javaClass.simpleName}: ${ex.message}"
        )
    }

    private fun collectConsumerModules(
        targetClassLoader: ClassLoader,
        instrumentation: Instrumentation,
    ): Set<Module> {
        val consumers = linkedSetOf<Module>()
        // Prefer modules that already loaded Spectre types (same classloaders that run core).
        for (loaded in instrumentation.allLoadedClasses) {
            val name = loaded.name
            if (name.startsWith(SPECTRE_PACKAGE_PREFIX)) {
                consumers += loaded.module
            }
        }
        for (fqn in CONSUMER_CLASS_NAMES) {
            runCatching { Class.forName(fqn, false, targetClassLoader).module }
                .getOrNull()
                ?.let { consumers += it }
        }
        consumers += targetClassLoader.unnamedModule
        ClassLoader.getSystemClassLoader()?.unnamedModule?.let { consumers += it }
        return consumers
    }

    private fun describeModule(module: Module): String =
        if (module.isNamed) module.name else "unnamed(${module.classLoader})"

    private const val JAVA_DESKTOP: String = "java.desktop"
    private const val SPECTRE_PACKAGE_PREFIX: String = "dev.sebastiano.spectre."

    private val CONSUMER_CLASS_NAMES: List<String> =
        listOf(
            "dev.sebastiano.spectre.core.NativeWindowHandle",
            "dev.sebastiano.spectre.core.WindowIdentityResolver",
            "dev.sebastiano.spectre.core.ComposeAutomator",
        )

    /**
     * Packages that host AWT peer accessors and platform-native window ids across JDK builds
     * (Windows HWND, macOS NSWindow*, X11 Window).
     */
    private val AWT_PEER_PACKAGES: List<String> =
        listOf(
            "sun.awt",
            "sun.awt.windows",
            "sun.awt.X11",
            "sun.lwawt",
            "sun.lwawt.macosx",
            "java.awt",
        )
}
