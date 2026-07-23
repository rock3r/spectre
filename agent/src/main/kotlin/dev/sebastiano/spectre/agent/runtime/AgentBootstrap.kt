package dev.sebastiano.spectre.agent.runtime

import java.lang.instrument.Instrumentation
import java.nio.file.Path

internal const val COMPOSE_AUTOMATOR_FQN = "dev.sebastiano.spectre.core.ComposeAutomator"
internal const val PLUGIN_CLASS_LOADER_FQN = "com.intellij.ide.plugins.cl.PluginClassLoader"

/**
 * Public Compose Desktop markers used to locate a host classloader when Spectre is not installed.
 */
internal val COMPOSE_HOST_MARKER_FQNS: List<String> =
    listOf(
        "androidx.compose.ui.awt.ComposeWindow",
        "androidx.compose.ui.awt.ComposePanel",
        "androidx.compose.ui.semantics.SemanticsOwner",
    )

/**
 * Locates the Spectre `core` library on the target JVM's classpath and returns the [ClassLoader]
 * that owns it.
 *
 * Implements the disambiguation rule from D-14 in the issue #153 workshop plan: if multiple loaders
 * have each loaded a `ComposeAutomator` class (e.g. one on the IntelliJ system classpath and one
 * inside a plugin's [PLUGIN_CLASS_LOADER_FQN]), the plugin's loader wins. If no clear winner
 * exists, fail loudly with [AmbiguousSpectreClasspathException] rather than silently picking the
 * wrong one.
 *
 * **Injection (#209):** when `ComposeAutomator` is absent, [findSpectreClassLoader] falls back to
 * loading the nested inject-runtime jar (relocated kotlinx + core, no Compose) against a Compose
 * host classloader discovered via [COMPOSE_HOST_MARKER_FQNS] / D-14.
 *
 * Designed as `internal object` so callers go through the agent entry points ([SpectreAgent])
 * rather than reaching directly into the bootstrap machinery.
 */
internal object AgentBootstrap {
    /**
     * Walks the target JVM's loaded classes via [Instrumentation.getAllLoadedClasses], filters to
     * those named [COMPOSE_AUTOMATOR_FQN], and applies the [selectClassLoader] rule.
     *
     * If no candidates surface from the loaded-class scan, falls back to
     * [forceLoadFromSystemLoader] to handle the common case where `ComposeAutomator` is on the
     * target's classpath as a Gradle dependency but hasn't been touched by the application code (a
     * hands-off UI app that doesn't itself call `ComposeAutomator.inProcess()`). Sample-desktop is
     * the canonical example.
     *
     * If that also fails, attempts **injection**: extract the nested inject-runtime jar and load
     * Spectre core via [SpectreInjectClassLoader] parented on a Compose host loader.
     *
     * @throws SpectreNotOnClasspathException when neither preinstalled core nor inject payload can
     *   supply `ComposeAutomator` (e.g. inject resource missing).
     * @throws ComposeNotOnClasspathException when inject is attempted but the target has no
     *   Compose.
     * @throws AmbiguousSpectreClasspathException when multiple candidates exist and no winner
     *   emerges from the selection rule.
     */
    fun findSpectreClassLoader(instrumentation: Instrumentation): ClassLoader {
        val initialCandidates = collectCandidateClassLoaders(instrumentation, COMPOSE_AUTOMATOR_FQN)
        if (initialCandidates.isNotEmpty()) return selectClassLoader(initialCandidates)

        forceLoadFromSystemLoader()?.let {
            return it
        }

        return injectSpectreClassLoader(instrumentation) ?: throw SpectreNotOnClasspathException()
    }

    /**
     * Builds an inject [ClassLoader] that owns Spectre core from [injectJar] while resolving
     * Compose against [composeHost]. Exposed for unit tests.
     */
    fun openInjectClassLoader(injectJar: Path, composeHost: ClassLoader): ClassLoader {
        val urls = arrayOf(injectJar.toUri().toURL())
        return SpectreInjectClassLoader(urls, composeHost)
    }

    /**
     * Locates a Compose-capable host classloader for injection (D-14 when multiple).
     *
     * @throws ComposeNotOnClasspathException when no Compose marker class is loadable.
     * @throws AmbiguousSpectreClasspathException when multiple hosts match and D-14 cannot pick
     *   one.
     */
    fun findComposeHostClassLoader(instrumentation: Instrumentation): ClassLoader {
        val candidates =
            COMPOSE_HOST_MARKER_FQNS.flatMap { fqn ->
                    collectCandidateClassLoaders(instrumentation, fqn)
                }
                .distinct()
        if (candidates.isNotEmpty()) {
            return selectClassLoader(candidates) { throw ComposeNotOnClasspathException() }
        }
        forceLoadComposeHostFromSystemLoader()?.let {
            return it
        }
        throw ComposeNotOnClasspathException()
    }

    private fun injectSpectreClassLoader(instrumentation: Instrumentation): ClassLoader? {
        val injectJar = InjectRuntimeLocator.extractInjectJar() ?: return null
        System.err.println(
            "[spectre-agent] spectre-core not on target classpath; injecting from $injectJar"
        )
        val composeHost = findComposeHostClassLoader(instrumentation)
        System.err.println("[spectre-agent] Compose host classloader: $composeHost")
        val injectLoader = openInjectClassLoader(injectJar, composeHost)
        // Prove ComposeAutomator resolves from the inject jar before returning.
        injectLoader.loadClass(COMPOSE_AUTOMATOR_FQN)
        return injectLoader
    }
}

/**
 * Attempts to force-load `ComposeAutomator` via the system [ClassLoader] without initialising it.
 *
 * Called when [Instrumentation.getAllLoadedClasses] turned up no candidates. The class may simply
 * be on the target's classpath but not yet referenced — `Class.forName` triggers the loader to
 * resolve it, after which it shows up in subsequent scans and we can read its [ClassLoader].
 *
 * Returns `null` if the system loader can't find the class. **Does not** walk arbitrary other
 * loaders — IntelliJ plugin loaders are handled by the primary `getAllLoadedClasses` scan because
 * by the time a plugin runs UI code, its plugin classes are reliably loaded.
 *
 * Uses `Class.forName(name, initialize = false, ...)` to avoid running static initialisers we don't
 * control. `ComposeAutomator` is a Kotlin interface so this is mostly defensive — but Kotlin
 * compiles companion-object bridges that could in principle have side effects.
 */
private fun forceLoadFromSystemLoader(): ClassLoader? =
    try {
        val loaded = Class.forName(COMPOSE_AUTOMATOR_FQN, false, ClassLoader.getSystemClassLoader())
        loaded.classLoader
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: NoClassDefFoundError) {
        null
    }

private fun forceLoadComposeHostFromSystemLoader(): ClassLoader? {
    val system = ClassLoader.getSystemClassLoader()
    for (fqn in COMPOSE_HOST_MARKER_FQNS) {
        try {
            val loaded = Class.forName(fqn, false, system)
            return loaded.classLoader ?: system
        } catch (_: ClassNotFoundException) {
            // try next marker
        } catch (_: NoClassDefFoundError) {
            // try next marker
        }
    }
    return null
}

/**
 * Filters [Instrumentation.getAllLoadedClasses] down to classes whose [Class.getName] equals
 * [targetFqn], then collects the distinct [ClassLoader]s that own them.
 *
 * Returns the loaders in encounter order (the JVM's iteration order over its loaded classes),
 * deduplicated by identity.
 */
internal fun collectCandidateClassLoaders(
    instrumentation: Instrumentation,
    targetFqn: String,
): List<ClassLoader> =
    instrumentation.allLoadedClasses
        .asSequence()
        .filter { it.name == targetFqn }
        .mapNotNull { it.classLoader }
        .distinct()
        .toList()

/**
 * Applies the D-14 selection rule to the [candidates] list:
 *
 * 1. If [candidates] is empty → throw via [onEmpty].
 * 2. If [candidates] has exactly one entry → return it.
 * 3. If multiple candidates exist, prefer the one whose class-loader hierarchy chain reaches
 *    [PLUGIN_CLASS_LOADER_FQN].
 *     - If exactly one such loader exists → return it.
 *     - Otherwise (zero or two-or-more) → throw [AmbiguousSpectreClasspathException].
 *
 * Step 3 deliberately surfaces a loud error rather than guessing, because silently picking the
 * wrong loader would produce confusing downstream bugs (the agent's reflective calls would target a
 * `ComposeAutomator` the app isn't actually using).
 */
internal fun selectClassLoader(
    candidates: List<ClassLoader>,
    onEmpty: () -> Nothing = { throw SpectreNotOnClasspathException() },
): ClassLoader {
    if (candidates.isEmpty()) onEmpty()
    if (candidates.size == 1) return candidates.single()

    val pluginCandidates = candidates.filter { it.hasPluginClassLoaderInHierarchy() }
    return when (pluginCandidates.size) {
        1 -> pluginCandidates.single()
        else -> throw AmbiguousSpectreClasspathException(candidates)
    }
}

/**
 * Walks the class-loader parent chain looking for one whose runtime class is the IntelliJ
 * [PLUGIN_CLASS_LOADER_FQN]. Detection is by class name string (not `instanceof`) because the agent
 * module deliberately doesn't depend on IntelliJ — the loader type is matched as a string so the
 * same code works against any IntelliJ version that keeps that FQN stable.
 */
internal fun ClassLoader.hasPluginClassLoaderInHierarchy(): Boolean {
    var current: ClassLoader? = this
    while (current != null) {
        if (current.javaClass.name == PLUGIN_CLASS_LOADER_FQN) return true
        current = current.parent
    }
    return false
}
