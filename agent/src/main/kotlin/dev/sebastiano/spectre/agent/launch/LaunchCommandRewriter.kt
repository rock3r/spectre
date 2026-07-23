package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi

/**
 * Pure rewrites applied to [LaunchSpec.command] before [ProcessBuilder] starts the process.
 *
 * Kept free of I/O so unit tests cover injection and Gradle detection without spawning.
 */
@ExperimentalSpectreAgentApi
public object LaunchCommandRewriter {

    /** True when [command] looks like a direct HotSpot/`java` launcher invocation. */
    public fun isDirectJvmLaunch(command: List<String>): Boolean {
        if (command.isEmpty()) return false
        val executable = basename(command.first())
        return executable.equals("java", ignoreCase = true) ||
            executable.equals("java.exe", ignoreCase = true)
    }

    /**
     * True when [command] is Gradle-client shaped (`gradlew` / `gradle` as the executable).
     *
     * Only the **launcher** basename is used — a direct `java -jar hotrun-tools.jar` (or a main arg
     * containing `hotRun`) must stay a direct JVM launch so readiness attaches to the launched PID.
     * Compose Hot Reload `hotRun` tasks are still covered when invoked via `./gradlew …`.
     */
    public fun isGradleishLaunch(command: List<String>): Boolean {
        if (command.isEmpty()) return false
        val launcher = basename(command.first()).lowercase()
        return launcher == "gradlew" ||
            launcher == "gradlew.bat" ||
            launcher == "gradle" ||
            launcher == "gradle.bat"
    }

    /**
     * Loud multi-line warning for Gradle-ish launches. Callers should print this to the harness
     * warning sink before readiness begins.
     */
    public fun gradleLaunchWarning(): String =
        """
        |WARNING: Spectre launch detected a Gradle-ish command (./gradlew …:run / hotRun / gradle).
        |  • The app JVM is typically spawned by the Gradle daemon, not the gradlew client.
        |  • Teardown targets the discovered app JVM only — never the Gradle daemon.
        |  • External JVM-arg injection is not possible; start the app with
        |    -XX:+EnableDynamicAgentLoading inside the build if JEP 451 warns.
        |  • Agent-sandboxed Gradle daemons can leave attach/UDS failures that look like Spectre bugs.
        |Prefer a prod-like launch (java -jar, installDist, packaged app) when you control the build.
        """
            .trimMargin()

    /**
     * For direct JVM launches, insert `-XX:+EnableDynamicAgentLoading` immediately after the `java`
     * binary when [inject] is true and the flag is not already present. Also inserts [extraJvmArgs]
     * after the injected flag (or after `java` when injection is skipped).
     *
     * Non-direct launches are returned unchanged ( [extraJvmArgs] are not applied ).
     */
    public fun rewriteDirectJvmCommand(
        command: List<String>,
        extraJvmArgs: List<String> = emptyList(),
        inject: Boolean = true,
    ): List<String> {
        if (!isDirectJvmLaunch(command)) return command
        val result = command.toMutableList()
        var insertAt = 1
        val alreadyPresent =
            hasDynamicAgentLoadingFlag(command) ||
                extraJvmArgs.any { isExactDynamicAgentLoadingFlag(it) }
        if (inject && !alreadyPresent) {
            result.add(insertAt, DYNAMIC_AGENT_LOADING_FLAG)
            insertAt++
        }
        if (extraJvmArgs.isNotEmpty()) {
            result.addAll(insertAt, extraJvmArgs)
        }
        return result
    }

    /**
     * True when [command] already sets `±EnableDynamicAgentLoading` as a **launcher** JVM flag
     * (before `-jar` / main class). Unrelated tokens such as `-Dfeature=EnableDynamicAgentLoading`
     * do not count. Tokens that take a value (`-cp` / `-classpath`) skip their following argument.
     */
    public fun hasDynamicAgentLoadingFlag(command: List<String>): Boolean {
        if (!isDirectJvmLaunch(command)) return false
        var i = 1
        while (i < command.size) {
            val token = command[i]
            when {
                token == "-jar" -> return false
                isExactDynamicAgentLoadingFlag(token) -> return true
                token == "-cp" || token == "-classpath" || token == "--class-path" ->
                    i += 2 // skip classpath value
                token.startsWith("-") -> i++
                else -> return false // main class boundary
            }
        }
        return false
    }

    private fun isExactDynamicAgentLoadingFlag(token: String): Boolean =
        token == DYNAMIC_AGENT_LOADING_FLAG || token == "-XX:-EnableDynamicAgentLoading"

    /**
     * Best-effort scan of a direct JVM command line for a `-cp` / `-classpath` value among
     * **launcher** options only. Stops at `-jar <file>` or the first non-option token (main class)
     * so application arguments like `java -jar app.jar -cp user-value` are not misread.
     *
     * Returns null when no classpath is present on the command line.
     */
    public fun extractClasspath(command: List<String>): String? {
        if (!isDirectJvmLaunch(command)) return null
        var i = 1
        while (i < command.size) {
            val token = command[i]
            when {
                token == "-jar" -> return null // remaining tokens are jar path + app args
                token == "-cp" || token == "-classpath" || token == "--class-path" ->
                    return command.getOrNull(i + 1)
                token.startsWith("-cp=") -> return token.removePrefix("-cp=")
                token.startsWith("-classpath=") -> return token.removePrefix("-classpath=")
                token.startsWith("--class-path=") -> return token.removePrefix("--class-path=")
                token.startsWith("-") -> i++ // other JVM launcher option
                else -> return null // main class — end of launcher options
            }
        }
        return null
    }

    /** True when [classpath] contains a path segment that looks like a spectre-core jar. */
    public fun classpathContainsSpectreCore(classpath: String): Boolean {
        // Split on both Unix (:) and Windows (;) classpath separators without a spread operator.
        val entries = classpath.replace(';', ':').split(':')
        return entries.any { entry ->
            val lower = entry.lowercase()
            val name = basename(entry).lowercase()
            name.startsWith("spectre-core") ||
                lower.contains("/spectre-core") ||
                lower.contains("\\spectre-core") ||
                // In-repo module output: .../core/build/libs/core-*.jar
                lower.contains("/core/build/") ||
                lower.contains("\\core\\build\\")
        }
    }

    internal fun basename(path: String): String {
        val slash = path.lastIndexOf('/')
        val backslash = path.lastIndexOf('\\')
        val cut = maxOf(slash, backslash)
        return if (cut < 0) path else path.substring(cut + 1)
    }

    public const val DYNAMIC_AGENT_LOADING_FLAG: String = "-XX:+EnableDynamicAgentLoading"
}
