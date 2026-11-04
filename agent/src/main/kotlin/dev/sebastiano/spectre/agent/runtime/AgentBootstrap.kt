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
 * **Injection (#209):** when `ComposeAutomator` is absent, [findSpectre] falls back to loading the
 * nested inject-runtime jar (relocated kotlinx + core, no Compose) against a Compose host
 * classloader discovered via [COMPOSE_HOST_MARKER_FQNS] / D-14.
 *
 * Designed as `internal object` so callers go through the agent entry points ([SpectreAgent])
 * rather than reaching directly into the bootstrap machinery.
 */
/**
 * Result of [AgentBootstrap.findSpectre]. When Spectre was injected, it also carries the inject
 * payload ([SpectreInjectClassLoader] and its extracted jar), which [AgentBootstrap] keeps for the
 * rest of the JVM's life and releases only when the JVM shuts down.
 */
internal data class SpectreBootstrapResult(
    val classLoader: ClassLoader,
    val injectJar: Path? = null,
    val injectClassLoader: SpectreInjectClassLoader? = null,
) {
    fun releaseInjectResources() {
        runCatching { injectClassLoader?.close() }
        injectJar?.let { path -> runCatching { java.nio.file.Files.deleteIfExists(path) } }
    }
}

internal object AgentBootstrap {
    /**
     * The injected Spectre runtime, kept for the rest of the JVM's life once injection succeeds.
     *
     * A closed injected loader cannot be found again: the classes it already defined stay visible
     * to the bootstrap scan, but it can no longer define the ones it has not used yet. So every
     * later attach in this JVM reuses this runtime, whether the previous attacher sent Detach
     * (#574) or vanished without it (#557). A shutdown hook closes the loader and deletes its jar
     * when the JVM exits.
     */
    @Volatile private var injectedRuntime: SpectreBootstrapResult? = null

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
     * Spectre core via [SpectreInjectClassLoader] parented on a Compose host loader. Once a JVM has
     * an injected runtime, every later call returns that same runtime.
     *
     * @throws SpectreNotOnClasspathException when neither preinstalled core nor inject payload can
     *   supply `ComposeAutomator` (e.g. inject resource missing).
     * @throws ComposeNotOnClasspathException when inject is attempted but the target has no
     *   Compose.
     * @throws AmbiguousSpectreClasspathException when multiple candidates exist and no winner
     *   emerges from the selection rule.
     */
    fun findSpectre(instrumentation: Instrumentation): SpectreBootstrapResult {
        injectedRuntime?.let {
            return it
        }
        val initialCandidates = collectCandidateClassLoaders(instrumentation, COMPOSE_AUTOMATOR_FQN)
        if (initialCandidates.isNotEmpty()) {
            return SpectreBootstrapResult(selectClassLoader(initialCandidates))
        }

        forceLoadFromSystemLoader()?.let {
            return SpectreBootstrapResult(it)
        }

        return injectSpectre(instrumentation) ?: throw SpectreNotOnClasspathException()
    }

    /** Convenience for tests and call sites that only need the [ClassLoader]. */
    fun findSpectreClassLoader(instrumentation: Instrumentation): ClassLoader =
        findSpectre(instrumentation).classLoader

    /**
     * Builds an inject [ClassLoader] that owns Spectre core from [injectJar] while resolving
     * Compose against [composeHost]. Exposed for unit tests.
     */
    fun openInjectClassLoader(injectJar: Path, composeHost: ClassLoader): SpectreInjectClassLoader {
        val urls = arrayOf(injectJar.toUri().toURL())
        return SpectreInjectClassLoader(urls, composeHost)
    }

    /**
     * Locates a Compose-capable host classloader for injection (D-14 when multiple).
     *
     * Looks, in order, for a loaded Compose marker class, for Compose on [systemLoader], and for
     * Compose behind an IntelliJ plugin classloader. The last case covers IntelliJ Platform IDEs,
     * which keep Compose in a plugin module loader and load no Compose class until a Compose
     * surface opens (#574).
     *
     * @throws ComposeNotOnClasspathException when no Compose marker class is loadable.
     * @throws AmbiguousSpectreClasspathException when multiple hosts match and D-14 cannot pick
     *   one.
     */
    fun findComposeHostClassLoader(
        instrumentation: Instrumentation,
        systemLoader: ClassLoader = ClassLoader.getSystemClassLoader(),
    ): ClassLoader {
        val candidates =
            COMPOSE_HOST_MARKER_FQNS.flatMap { fqn ->
                    collectCandidateClassLoaders(instrumentation, fqn)
                }
                .distinct()
        if (candidates.isNotEmpty()) {
            return selectClassLoader(candidates) { throw ComposeNotOnClasspathException() }
        }
        systemLoader.composeHostOrNull()?.let {
            return it
        }
        val pluginHosts = composeHostsBehindPluginLoaders(instrumentation)
        if (pluginHosts.isNotEmpty()) {
            return selectClassLoader(pluginHosts) { throw ComposeNotOnClasspathException() }
        }
        throw ComposeNotOnClasspathException()
    }

    /**
     * Whether an attach that finds no Compose may wait for Compose to load instead of failing
     * (#574).
     *
     * True for IntelliJ Platform targets, which this recognises by IntelliJ's plugin classloader
     * class being loaded. An IDE loads Compose only when the first Compose surface opens, so an
     * attach made from the Welcome screen has to wait for it. Any other JVM without Compose keeps
     * failing with [ComposeNotOnClasspathException], because that is far more often the wrong
     * process than a process that will load Compose later.
     */
    fun canWaitForCompose(instrumentation: Instrumentation): Boolean =
        instrumentation.allLoadedClasses.any { it.name == PLUGIN_CLASS_LOADER_FQN }

    @Synchronized
    private fun injectSpectre(instrumentation: Instrumentation): SpectreBootstrapResult? {
        injectedRuntime?.let {
            return it
        }
        // Compose comes first: an IDE waiting for Compose retries this on every request, and
        // must not extract a jar each time just to delete it again.
        val composeHost = findComposeHostClassLoader(instrumentation)
        val injectJar = InjectRuntimeLocator.extractInjectJar() ?: return null
        System.err.println(
            "[spectre-agent] spectre-core not on target classpath; injecting from $injectJar"
        )
        System.err.println("[spectre-agent] Compose host classloader: $composeHost")
        var injectLoader: SpectreInjectClassLoader? = null
        val runtime =
            try {
                injectLoader = openInjectClassLoader(injectJar, composeHost)
                // Prove ComposeAutomator resolves from the inject jar before returning.
                injectLoader.loadClass(COMPOSE_AUTOMATOR_FQN)
                SpectreBootstrapResult(
                    classLoader = injectLoader,
                    injectJar = injectJar,
                    injectClassLoader = injectLoader,
                )
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                // Cleanup must run for any failure after extract (classloader open, loadClass).
                // Specific typed catches would miss LinkageError-derived RuntimeExceptions from
                // the target Compose stack.
                // Close loader before delete so Windows can unlink the jar file.
                runCatching { injectLoader?.close() }
                runCatching { java.nio.file.Files.deleteIfExists(injectJar) }
                throw error
            }
        Runtime.getRuntime()
            .addShutdownHook(Thread(runtime::releaseInjectResources, INJECT_CLEANUP_HOOK_NAME))
        injectedRuntime = runtime
        return runtime
    }

    private const val INJECT_CLEANUP_HOOK_NAME = "spectre-agent-inject-cleanup"
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

/**
 * Returns the loader that defines a Compose marker class for this loader, or null when this loader
 * cannot see Compose. Loads the marker without initialising it.
 */
private fun ClassLoader.composeHostOrNull(): ClassLoader? {
    for (fqn in COMPOSE_HOST_MARKER_FQNS) {
        try {
            val loaded = Class.forName(fqn, false, this)
            return loaded.classLoader ?: this
        } catch (_: ClassNotFoundException) {
            // try next marker
        } catch (_: NoClassDefFoundError) {
            // try next marker
        }
    }
    return null
}

/**
 * Asks every IntelliJ plugin classloader that has loaded a class whether it can see Compose, and
 * returns the distinct loaders that define Compose for them.
 *
 * In IntelliJ Platform 2024.2+ Compose is a plugin module, so the system loader cannot see it. Its
 * own loader may have loaded nothing yet, but a plugin that depends on it can see it through
 * delegation, and the class it loads comes from the module's loader. A cheap resource lookup skips
 * the plugins that cannot see Compose at all.
 */
private fun composeHostsBehindPluginLoaders(instrumentation: Instrumentation): List<ClassLoader> =
    instrumentation.allLoadedClasses
        .asSequence()
        .mapNotNull { it.classLoader }
        .distinct()
        .filter { it.javaClass.name == PLUGIN_CLASS_LOADER_FQN }
        .filter { loader -> COMPOSE_HOST_MARKER_RESOURCES.any { loader.getResource(it) != null } }
        .mapNotNull { it.composeHostOrNull() }
        .distinct()
        .toList()

private val COMPOSE_HOST_MARKER_RESOURCES: List<String> = COMPOSE_HOST_MARKER_FQNS.map {
    it.replace('.', '/') + ".class"
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
