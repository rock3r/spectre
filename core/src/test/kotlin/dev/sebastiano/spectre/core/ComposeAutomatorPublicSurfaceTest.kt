package dev.sebastiano.spectre.core

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the intended public surface of the `core` module. The test partitions every JVM-public
 * declaration **that the test knows about** into three buckets — published user API,
 * `@InternalSpectreApi` escape hatch, and "must not be JVM-public" — at three granularities. The
 * type-level allowlist is maintained explicitly (`DECLARED_TYPES`); a future top-level class not
 * added there will not be caught by this test until R7's full BCV pass is wired in.
 *
 * 1. **Declared types** in `dev.sebastiano.spectre.core` (classes, interfaces, annotations).
 * 2. **Public methods on user-facing declared classes** (so drift on `ComposeAutomator.windows` vs
 *    `ComposeAutomator.surfaceIds` etc. is caught explicitly).
 * 3. **File-level top-level functions** in named Kotlin facade classes that ship publicly-named
 *    helpers (`HiDpiMapperKt`).
 *
 * Drives the boundary R7 (Kotlin Binary Compatibility Validator) will eventually freeze. Add new
 * intentional public API by editing the relevant allowlist below in the same commit as the new
 * declaration.
 */
class ComposeAutomatorPublicSurfaceTest {

    @Test
    fun `published user-facing types match the allowlist`() {
        val actual =
            declaredTypeEntries().filter { it.bucket == Bucket.Published }.map { it.name }.toSet()
        assertEquals(
            PUBLISHED_TYPES.toSortedSet(),
            actual.toSortedSet(),
            "Published user-facing types drifted from the allowlist. Update PUBLISHED_TYPES in " +
                "the same commit as the deliberate API change.",
        )
    }

    @Test
    fun `escape-hatch types match the allowlist`() {
        val actual =
            declaredTypeEntries().filter { it.bucket == Bucket.EscapeHatch }.map { it.name }.toSet()
        assertEquals(
            ESCAPE_HATCH_TYPES.toSortedSet(),
            actual.toSortedSet(),
            "Escape-hatch type surface drifted. Widening or narrowing @InternalSpectreApi is a " +
                "stability-policy decision — update ESCAPE_HATCH_TYPES intentionally.",
        )
    }

    @Test
    fun `no unintended JVM-public type exists in the core package`() {
        val unknown =
            declaredTypeEntries().filter {
                it.bucket == Bucket.JvmPublic &&
                    it.name !in PUBLISHED_TYPES &&
                    it.name !in ESCAPE_HATCH_TYPES
            }
        assertTrue(
            unknown.isEmpty(),
            "Found JVM-public types in dev.sebastiano.spectre.core that are not on either " +
                "type allowlist: ${unknown.map { it.name }}. Either add a deliberate allowlist " +
                "entry or change the declaration's visibility.",
        )
    }

    @Test
    fun `ComposeAutomator user methods match the allowlist`() {
        val actual = userVisibleMethodNames(ComposeAutomator::class.java)
        assertEquals(
            COMPOSE_AUTOMATOR_PUBLISHED_METHODS.toSortedSet(),
            actual.toSortedSet(),
            "ComposeAutomator's user-visible method surface drifted. Add intentional new methods " +
                "to COMPOSE_AUTOMATOR_PUBLISHED_METHODS, route experimental ones through " +
                "@InternalSpectreApi (escape hatch), or change visibility to internal.",
        )
    }

    @Test
    fun `ComposeAutomator escape-hatch members match the allowlist`() {
        val escapeHatch = internallyAnnotatedNames(ComposeAutomator::class.java)
        assertEquals(
            COMPOSE_AUTOMATOR_ESCAPE_HATCH_MEMBERS.toSortedSet(),
            escapeHatch.toSortedSet(),
            "ComposeAutomator's @InternalSpectreApi members drifted. " +
                "Update COMPOSE_AUTOMATOR_ESCAPE_HATCH_MEMBERS intentionally.",
        )
    }

    @Test
    fun `HiDpiMapper escape-hatch top-level functions match the allowlist`() {
        val clazz = Class.forName("dev.sebastiano.spectre.core.HiDpiMapperKt")
        val escapeHatch = internallyAnnotatedNames(clazz)
        assertEquals(
            HIDPI_ESCAPE_HATCH_FUNCTIONS.toSortedSet(),
            escapeHatch.toSortedSet(),
            "HiDpiMapper escape-hatch top-level functions drifted. " +
                "Update HIDPI_ESCAPE_HATCH_FUNCTIONS intentionally.",
        )
    }

    @Test
    fun `ComposeAutomator companion factory surface matches the allowlist`() {
        val companion = ComposeAutomator.Companion::class.java
        val factoryNames = userVisibleMethodNames(companion)
        assertEquals(
            COMPOSE_AUTOMATOR_FACTORY_METHODS.toSortedSet(),
            factoryNames.toSortedSet(),
            "ComposeAutomator companion factory surface drifted. " +
                "Update COMPOSE_AUTOMATOR_FACTORY_METHODS intentionally — the factory shape is " +
                "exactly what R1 changed (windowTracker/semanticsReader removed from inProcess).",
        )
    }

    @Test
    fun `ComposeAutomator inProcess does not accept removed collaborators`() {
        val companion = ComposeAutomator.Companion::class.java
        val forbiddenTypeNames =
            setOf(
                "dev.sebastiano.spectre.core.WindowTracker",
                "dev.sebastiano.spectre.core.SemanticsReader",
            )
        val violators =
            companion.declaredMethods
                .filter {
                    Modifier.isPublic(it.modifiers) &&
                        !it.isSynthetic &&
                        !it.isBridge &&
                        stripValueClassMangling(it.name) == "inProcess"
                }
                .flatMap { method ->
                    method.parameterTypes
                        .filter { it.name in forbiddenTypeNames }
                        .map { param -> "${method.name}(${param.name})" }
                }
        assertTrue(
            violators.isEmpty(),
            "Found inProcess overload(s) still accepting removed collaborator types: $violators. " +
                "R1 intentionally removed windowTracker / semanticsReader from the public " +
                "factory surface; restoring either to a public overload undoes that boundary.",
        )
    }

    @Test
    fun `HiDpiMapper has no unannounced public top-level functions`() {
        val clazz = Class.forName("dev.sebastiano.spectre.core.HiDpiMapperKt")
        // Kotlin does not mangle `internal` top-level function names on the JVM (mangling only
        // applies to internal members of classes). So `composeToAwtX` / `composeToAwtY` appear
        // as JVM-public even though they are Kotlin-`internal`. We track those explicitly here
        // so the test still catches genuine drift: any new helper that is neither in the
        // escape-hatch list nor in the documented "internal but JVM-public" list fails.
        val unannounced =
            clazz.declaredMethods.filter { method ->
                Modifier.isPublic(method.modifiers) &&
                    !method.isSynthetic &&
                    !method.name.endsWith("\$annotations") &&
                    !method.name.contains('$') &&
                    method.name !in HIDPI_ESCAPE_HATCH_FUNCTIONS &&
                    method.name !in HIDPI_KOTLIN_INTERNAL_FUNCTIONS
            }
        assertTrue(
            unannounced.isEmpty(),
            "Found user-callable top-level functions in HiDpiMapperKt that are neither " +
                "@InternalSpectreApi nor allowlisted: ${unannounced.map { it.name }}. Demote to " +
                "`internal` or mark @InternalSpectreApi.",
        )
    }

    /**
     * Strips Kotlin's value-class mangling suffix (`-XxxXxxXx`) from a method name. When a function
     * takes a value-class parameter (`kotlin.time.Duration`, `androidx.compose.ui.semantics.Role`),
     * Kotlin emits a JVM method named `original-<hash>`. The hash is unstable across compilations
     * so the allowlist tracks the original name.
     */
    private fun stripValueClassMangling(name: String): String {
        val dash = name.indexOf('-')
        return if (dash > 0) name.substring(0, dash) else name
    }

    private fun internallyAnnotatedNames(clazz: Class<*>): Set<String> {
        // @InternalSpectreApi on a property lands on the `getX$annotations` synthetic the Kotlin
        // compiler emits. Map back to the underlying property name (`getX`) so the allowlist
        // tracks the actual reachable member.
        return clazz.declaredMethods
            .filter { it.isAnnotationPresent(InternalSpectreApi::class.java) }
            .map { method ->
                val stripped = stripValueClassMangling(method.name)
                stripped.removeSuffix("\$annotations")
            }
            .toSet()
    }

    private enum class Bucket {
        Published,
        EscapeHatch,
        JvmPublic,
    }

    private data class TypeEntry(val name: String, val bucket: Bucket)

    private fun declaredTypeEntries(): List<TypeEntry> = DECLARED_TYPES.mapNotNull { name ->
        val clazz = runCatching { Class.forName(name) }.getOrNull() ?: return@mapNotNull null
        if (!Modifier.isPublic(clazz.modifiers)) return@mapNotNull null
        val isInternal = clazz.isAnnotationPresent(InternalSpectreApi::class.java)
        val bucket =
            when {
                isInternal -> Bucket.EscapeHatch
                name in PUBLISHED_TYPES -> Bucket.Published
                else -> Bucket.JvmPublic
            }
        TypeEntry(name, bucket)
    }

    /**
     * Names of methods on [clazz] that are reachable from user code without `@OptIn`. Filters out:
     * - Synthetic methods, bridges, and Kotlin's getter/setter `getXxx$annotations` helpers.
     * - Methods name-mangled with `$` (Kotlin's marker for `internal` visibility on the JVM).
     * - Methods annotated `@InternalSpectreApi` (those live on a separate allowlist).
     * - `Object` overrides (`equals`, `hashCode`, `toString`) — universally present.
     * - Companion accessors (`Companion`).
     */
    private fun userVisibleMethodNames(clazz: Class<*>): Set<String> {
        // The `getXxx$annotations` synthetic carries property-level annotations including
        // @InternalSpectreApi. We must consult the synthetic to know which property getters
        // are escape-hatch, and exclude the synthetic itself from the user-visible set.
        val escapeHatchGetters = internallyAnnotatedNames(clazz)
        return clazz.declaredMethods
            .filter { method ->
                Modifier.isPublic(method.modifiers) &&
                    !method.isSynthetic &&
                    !method.isBridge &&
                    !method.name.endsWith("\$annotations") &&
                    !method.name.contains('$') &&
                    method.name !in OBJECT_METHODS &&
                    method.name != "getCompanion"
            }
            .map { method -> stripValueClassMangling(method.name) }
            .filter { name -> name !in escapeHatchGetters }
            // Many methods exist in multiple overloads (e.g. `findByText`, `screenshot`,
            // `swipe`). Collapse to names — the allowlist tracks names, not signatures.
            .toSet()
    }

    private companion object {

        // Every type the test knows about. Adding a new top-level type means updating this
        // list in the same commit; the allowlists below decide which bucket it falls in.
        val DECLARED_TYPES: List<String> =
            listOf(
                "dev.sebastiano.spectre.core.AutomatorIdlingResource",
                "dev.sebastiano.spectre.core.AutomatorNode",
                "dev.sebastiano.spectre.core.AutomatorTree",
                "dev.sebastiano.spectre.core.AutomatorWindow",
                "dev.sebastiano.spectre.core.BothBounds",
                "dev.sebastiano.spectre.core.ComposeAutomator",
                "dev.sebastiano.spectre.core.IdleTimeoutException",
                "dev.sebastiano.spectre.core.InternalSpectreApi",
                "dev.sebastiano.spectre.core.NodeKey",
                "dev.sebastiano.spectre.core.PerfettoTracer",
                "dev.sebastiano.spectre.core.RobotDriver",
                "dev.sebastiano.spectre.core.TextMatchType",
                "dev.sebastiano.spectre.core.TextQuery",
                "dev.sebastiano.spectre.core.TrackedWindow",
                "dev.sebastiano.spectre.core.Tracer",
                "dev.sebastiano.spectre.core.WindowTracker",
            )

        // The intended user-facing types. Update only on a deliberate API addition.
        val PUBLISHED_TYPES: Set<String> =
            setOf(
                "dev.sebastiano.spectre.core.AutomatorIdlingResource",
                "dev.sebastiano.spectre.core.AutomatorNode",
                "dev.sebastiano.spectre.core.AutomatorTree",
                "dev.sebastiano.spectre.core.AutomatorWindow",
                "dev.sebastiano.spectre.core.BothBounds",
                "dev.sebastiano.spectre.core.ComposeAutomator",
                "dev.sebastiano.spectre.core.IdleTimeoutException",
                "dev.sebastiano.spectre.core.InternalSpectreApi",
                "dev.sebastiano.spectre.core.NodeKey",
                "dev.sebastiano.spectre.core.PerfettoTracer",
                "dev.sebastiano.spectre.core.RobotDriver",
                "dev.sebastiano.spectre.core.TextMatchType",
                "dev.sebastiano.spectre.core.TextQuery",
                "dev.sebastiano.spectre.core.Tracer",
            )

        // Reachable from Kotlin/JVM, but not part of the stability policy.
        val ESCAPE_HATCH_TYPES: Set<String> =
            setOf(
                "dev.sebastiano.spectre.core.TrackedWindow",
                "dev.sebastiano.spectre.core.WindowTracker",
            )

        // The intended user-callable methods on ComposeAutomator (by name; overloads collapse).
        val COMPOSE_AUTOMATOR_PUBLISHED_METHODS: Set<String> =
            setOf(
                "surfaceIds",
                "refreshWindows",
                "tree",
                "allNodes",
                "findByTestTag",
                "findOneByTestTag",
                "findByText",
                "findOneByText",
                "findByContentDescription",
                "findByRole",
                "click",
                "doubleClick",
                "longClick",
                "swipe",
                "scrollWheel",
                "typeText",
                "pasteText",
                "clearAndTypeText",
                "pressKey",
                "pressEnter",
                "focusWindow",
                "performSemanticsClick",
                "screenshot",
                "registerIdlingResource",
                "unregisterIdlingResource",
                "withTracing",
                "waitForIdle",
                "waitForVisualIdle",
                "waitForNode",
                "printTree",
            )

        // Members on ComposeAutomator that ship under @InternalSpectreApi — currently just the
        // `windows: List<TrackedWindow>` accessor for the experimental server transport.
        // Property getters are emitted as `getWindows`.
        val COMPOSE_AUTOMATOR_ESCAPE_HATCH_MEMBERS: Set<String> = setOf("getWindows")

        // Public factory methods on ComposeAutomator.Companion. R1 narrowed `inProcess(...)`
        // to a single `robotDriver` parameter; restoring `windowTracker` / `semanticsReader`
        // here would undo the API boundary. Update this set in the same commit as any
        // intentional factory addition.
        val COMPOSE_AUTOMATOR_FACTORY_METHODS: Set<String> = setOf("inProcess")

        // Top-level functions in HiDpiMapper that intentionally stay reachable under
        // @InternalSpectreApi for the Windows HiDPI diagnostic harness.
        val HIDPI_ESCAPE_HATCH_FUNCTIONS: Set<String> =
            setOf("composeBoundsToAwtCenter", "composeBoundsToAwtRectangle")

        // Kotlin-`internal` top-level functions still appear as JVM-public because top-level
        // internal function names are NOT mangled by the Kotlin compiler (mangling only applies
        // to internal *members* of classes). Documented here so the surface test treats them as
        // "known not user-facing despite JVM bytecode visibility". R7's BCV pass will refine
        // this once it lands.
        val HIDPI_KOTLIN_INTERNAL_FUNCTIONS: Set<String> = setOf("composeToAwtX", "composeToAwtY")

        val OBJECT_METHODS: Set<String> = setOf("equals", "hashCode", "toString")
    }
}
