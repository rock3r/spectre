package dev.sebastiano.spectre.cli.bootstrap;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Entry point of every packaged Spectre CLI launch (the fat jar's {@code Main-Class} and the
 * Roast/Construo {@code mainClass}).
 *
 * <p>The packaged launcher (Roast) passes a static list of JVM options from its JSON config,
 * so it cannot conditionally add {@code -XX:AOTCache} only when a trained cache exists. This
 * bootstrap can: it is a single pure-Java class, so its own JVM is warm in tens of
 * milliseconds, and it then either
 *
 * <ul>
 *   <li>execs a child JVM running the real CLI ({@code SpectreCliKt}) with {@code
 *       -XX:AOTCache} when {@link AotCache} finds a matching cache (production),
 *   <li>on the first run for a jar, trains that cache <em>before</em> running the real
 *       command — a {@code --help} record run plus a cache-assembly JVM, both with
 *       discarded stdio — promotes the result to the cache location, and then execs the
 *       real command against it, or, when training fails for any reason, execs the real
 *       command uncached,
 *   <li>execs a child JVM without AOT flags when caching is explicitly disabled, so the
 *       real CLI never runs inside this JVM (packaged launches give the bootstrap lean
 *       startup flags the long-lived MCP server must not inherit),
 *   <li>execs a child JVM carrying the caller's own JVM options — without the frontend
 *       contract or a managed cache — when the caller manages AOT or the collector
 *       themselves, or
 *   <li>runs the real CLI in-process when caching is unavailable (JDK &lt; 25, exploded
 *       or multi-entry classpath — the latter is the thin start-script distribution,
 *       whose child could not be built from the CLI jar alone) or when the caller's
 *       instrumentation agent already holds a fixed listen address in this JVM, so
 *       behaviour is unchanged.
 * </ul>
 *
 * <p>Training never touches the real command's stdio: a record run appends an {@code
 * AOTConfiguration recorded} line to stdout and Leyden logging is stdout by default, either
 * of which would corrupt {@code spectre mcp}'s protocol stream and {@code --json} output.
 * The sacrificial {@code --help} workload covers the startup class graph (Kotlin runtime,
 * Clikt, CLI scaffolding) that dominates cold-start cost; classes it never loads are simply
 * loaded normally from the jar on later runs. For the same reason a production child gets
 * {@code -Xlog:aot=off}: if the JVM ever rejects a cache (corrupt or foreign file at the
 * fingerprint path), its {@code [aot]} diagnostics must not land on stdout.
 *
 * <p>Measured on Temurin 25 in the development container, the bootstrap JVM costs ~30 ms
 * while the cache cuts a full CLI start from ~330 ms to ~135 ms, so the extra process is a
 * net win on every cached invocation. The real child's stdio is inherited untouched.
 */
public final class SpectreBootstrap {

    /** Real CLI entry point, launched by name so this class stays dependency-free. */
    static final String MAIN_CLASS_NAME = "dev.sebastiano.spectre.cli.SpectreCliKt";

    /** How many trained caches to retain per user (see {@link AotCache#pruneOldCaches}). */
    private static final int MAX_RETAINED_CACHES = 3;

    private SpectreBootstrap() {}

    public static void main(String[] args) {
        List<String> inputArguments =
                java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        if (hasFixedAddressAgent(inputArguments)) {
            // This agent already holds a fixed resource in this process (a JDWP
            // listen address) that a re-exec'd child carrying the identical
            // agent could not bind. Debugging this process is also the point of
            // such an agent, so run the real CLI here, agent and all, as before
            // the bootstrap — accepting this JVM's lean packaged-launcher
            // limits for a launch shape that is inherently interactive.
            runInProcess(args);
            return;
        }
        Optional<Path> jarFile = currentJarFile();
        if ((AotCache.hasUserAotArguments(inputArguments)
                        || hasForeignCollector(inputArguments)
                        || hasInstrumentationAgent(inputArguments))
                && jarFile.isPresent()) {
            // The caller manages AOT or the collector themselves (java
            // -XX:AOTCache=..., or a start script's JAVA_OPTS/SPECTRE_OPTS): their
            // options take precedence over the managed cache, exactly as
            // documented. Re-exec them onto a child JVM rather than running
            // in-process: this JVM carries the packaged launcher's lean startup
            // flags (-Xmx64m, TieredStopAtLevel=1), which a long-lived
            // `spectre mcp` must not inherit.
            System.exit(
                    runChild(
                            userManagedChildCommand(
                                    childJavaExecutable(), jarFile.get(), args, inputArguments)));
            return;
        }
        if (AotCache.hasUserAotArguments(inputArguments)) {
            // Exploded classpath (tests, Gradle dev runs): no child can be built,
            // and this JVM has no packaged-launcher caps, so the caller's options
            // govern the launch in-process as before.
            runInProcess(args);
            return;
        }
        AotCache.Plan plan = jarFile
                .map(SpectreBootstrap::planFor)
                .orElseGet(() -> new AotCache.Plan(AotCache.Mode.UNAVAILABLE, null, null, List.of()));
        if (plan.mode() == AotCache.Mode.TRAINING && jarFile.isPresent()) {
            // Best-effort: any failure leaves the plan re-planned below and the real
            // command simply runs uncached, exactly as it would without a cache.
            trainCache(plan, jarFile.get());
            plan = planFor(jarFile.get());
            if (plan.mode() == AotCache.Mode.TRAINING) {
                plan = new AotCache.Plan(AotCache.Mode.DISABLED, null, null, List.of());
            }
        }
        if (jarFile.isEmpty()) {
            // Exploded classpath (tests, Gradle dev runs): nothing to exec, and this
            // JVM carries no packaged-launcher caps, so run in-process as before.
            // Every jar-backed plan — production, training, disabled, and
            // unavailable alike — execs a child instead: even an unavailable cache
            // (for example a malformed SPECTRE_AOT_CACHE_DIR) must degrade to an
            // uncached child, never to a capped in-process run.
            runInProcess(args);
            return;
        }
        System.exit(runChild(plan, jarFile.get(), args));
    }

    private static AotCache.Plan planFor(Path jarFile) {
        return AotCache.plan(
                jarFile,
                System.getenv(),
                AotCache.userHomePath(),
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                AotCache.runtimeIdentity(),
                Runtime.version().feature(),
                true,
                Long.toString(ProcessHandle.current().pid()),
                callerModuleOptions(
                        java.lang.management.ManagementFactory.getRuntimeMXBean()
                                .getInputArguments()));
    }

    /** First JDK feature release that warns about restricted native methods at runtime. */
    private static final int FIRST_RESTRICTED_METHOD_WARNING_FEATURE = 24;

    /**
     * JVM flags every frontend JVM runs with — the production child, the training record
     * run, and the cache-assembly JVM — so all three share one collector and one archived
     * heap representation.
     *
     * <p>G1 is explicit: the packaged launcher defaults to ZGC, which JDK 25's Leyden AOT
     * cache does not support, and merely omitting the choice would let JVM ergonomics pick
     * different collectors on different machines. The remaining flags pin the object
     * representation the AOT archive header validates (compressed oops, no compact object
     * headers, 8-byte alignment) instead of inheriting whatever ergonomics chooses. {@code
     * UseCompressedClassPointers} is deliberately not pinned: it defaults to {@code true}
     * alongside compressed oops, and naming it explicitly makes JDK 25 print a deprecation
     * warning to stderr on every launch. The daemon JVM is untouched by this list — it is
     * launched by the CLI itself, not by this bootstrap.
     */
    static final List<String> FRONTEND_JVM_ARGUMENTS =
            List.of(
                    "-XX:+UseG1GC",
                    "-XX:+UseCompressedOops",
                    "-XX:-UseCompactObjectHeaders",
                    "-XX:ObjectAlignmentInBytes=8");

    /**
     * The full child command: this JVM's {@code java}, the planned AOT flags, the real CLI.
     *
     * <p>A production child also gets {@code -Xlog:aot=off} (see the class javadoc: a
     * rejected cache must not put {@code [aot]} lines on stdout) and, on JDK 24+, {@code
     * --enable-native-access=ALL-UNNAMED}: the CLI loads JNA during startup, and without
     * it every invocation prints a restricted-method warning to stderr. The second flag is
     * gated on the feature version so an older JVM can never die on an unrecognised option
     * before the CLI's own minimum-JDK check runs.
     */
    static List<String> childCommand(
            String javaExecutable,
            AotCache.Plan plan,
            Path jarFile,
            String[] args,
            int runtimeFeature,
            List<String> forwardedJvmOptions,
            List<String> callerSystemProperties) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.addAll(FRONTEND_JVM_ARGUMENTS);
        command.addAll(plan.jvmArguments());
        command.addAll(forwardedJvmOptions);
        command.addAll(callerSystemProperties);
        if (plan.mode() == AotCache.Mode.PRODUCTION) {
            command.add("-Xlog:aot=off");
        }
        addNativeAccessArgument(command, runtimeFeature);
        command.add("-cp");
        command.add(jarFile.toString());
        command.add(MAIN_CLASS_NAME);
        command.addAll(List.of(args));
        return command;
    }

    /**
     * Appends {@code --enable-native-access=ALL-UNNAMED} on JDK 24+. Every JVM that
     * touches a cache — record, assembly, and production — must carry the identical
     * module options: JEP 483 makes the JVM reject a cache whose archived module graph
     * was built with different ones ("Mismatched values for property
     * jdk.module.enable.native.access ... Unable to map shared spaces").
     */
    private static void addNativeAccessArgument(List<String> command, int runtimeFeature) {
        if (runtimeFeature >= FIRST_RESTRICTED_METHOD_WARNING_FEATURE) {
            command.add("--enable-native-access=ALL-UNNAMED");
        }
    }

    /**
     * The sacrificial training workload: {@code --help} with the plan's record flags. It
     * exercises the CLI's whole startup class graph without any side effects; its stdio is
     * discarded by the caller.
     */
    static List<String> trainingCommand(
            String javaExecutable,
            AotCache.Plan plan,
            Path jarFile,
            int runtimeFeature,
            List<String> callerModuleOptions) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.addAll(FRONTEND_JVM_ARGUMENTS);
        command.addAll(plan.jvmArguments());
        command.addAll(callerModuleOptions);
        addNativeAccessArgument(command, runtimeFeature);
        command.add("-cp");
        command.add(jarFile.toString());
        command.add(MAIN_CLASS_NAME);
        command.add("--help");
        return command;
    }

    /** The cache-assembly JVM: creates the training cache from the recorded configuration. */
    static List<String> assemblyCommand(
            String javaExecutable,
            AotCache.Plan plan,
            Path jarFile,
            int runtimeFeature,
            List<String> callerModuleOptions) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.addAll(FRONTEND_JVM_ARGUMENTS);
        command.add("-XX:AOTMode=create");
        command.add("-XX:AOTConfiguration=" + trainingConfigFile(plan));
        command.add("-XX:AOTCacheOutput=" + plan.trainingFile());
        command.addAll(callerModuleOptions);
        addNativeAccessArgument(command, runtimeFeature);
        command.add("-cp");
        command.add(jarFile.toString());
        return command;
    }

    /** Recorded configuration a training plan's record run writes (bootstrap convention). */
    static Path trainingConfigFile(AotCache.Plan plan) {
        return Path.of(plan.trainingFile() + ".config");
    }

    /** {@code bin/java} (or {@code java.exe}) under a {@code java.home} directory. */
    static Path javaExecutable(Path javaHome, String osName) {
        boolean windows = osName != null && osName.toLowerCase(java.util.Locale.ROOT).contains("win");
        return javaHome.resolve("bin").resolve(windows ? "java.exe" : "java");
    }

    /**
     * This JVM's {@code java} executable, made spawnable: zip extraction can drop the
     * execute bit on the bundled runtime's java launcher and on lib/jspawnhelper, which
     * ProcessBuilder itself needs (the DaemonProcessLauncher restores both for the same
     * reason when it spawns the daemon). Without this every child exec fails and
     * packaged launches silently degrade to the in-process fallback.
     */
    private static String childJavaExecutable() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        String javaExecutable =
                javaExecutable(javaHome, System.getProperty("os.name")).toString();
        new File(javaExecutable).setExecutable(true, false);
        new File(javaHome.resolve("lib").resolve("jspawnhelper").toString())
                .setExecutable(true, false);
        return javaExecutable;
    }

    /**
     * The {@code -D} system properties the caller gave this JVM (via {@code java -D…},
     * start-script {@code JAVA_OPTS}/{@code SPECTRE_OPTS}, or {@code JAVA_TOOL_OPTIONS}),
     * filtered from the JVM's input arguments. They are forwarded to the child: documented
     * properties such as {@code dev.sebastiano.spectre.agent.runtimeJar} and {@code
     * dev.sebastiano.spectre.agent.inputCoordination} are read by the real CLI and its
     * daemon launcher, and would silently vanish if they stopped at the bootstrap JVM.
     */
    static List<String> callerSystemProperties(List<String> inputArguments) {
        return inputArguments.stream().filter(arg -> arg.startsWith("-D")).toList();
    }

    /**
     * This JVM's own packaged-launcher flags (the Roast {@code vmArgs} in {@code
     * cli/build.gradle.kts}). They exist to make the throwaway bootstrap JVM cheap;
     * the real CLI must never inherit them, so they are withheld from every child. A
     * caller passing the identical flag themselves cannot be told apart from the
     * launcher and loses it too — an accepted ambiguity, since both values are
     * startup-tuning choices for a process that exits immediately.
     */
    private static final List<String> BOOTSTRAP_ONLY_ARGUMENTS =
            List.of("-XX:TieredStopAtLevel=1", "-Xmx64m");

    /** Module options that take their value as the following argument token. */
    private static final List<String> TWO_TOKEN_MODULE_OPTIONS =
            List.of(
                    "--add-modules",
                    "--add-opens",
                    "--add-exports",
                    "--add-reads",
                    "--patch-module",
                    "--module-path",
                    "--upgrade-module-path");

    /** Whether {@code argument} selects a garbage collector ({@code -XX:+Use…GC}). */
    private static boolean isCollectorSelector(String argument) {
        return argument.startsWith("-XX:+Use") && argument.endsWith("GC");
    }

    /** Whether {@code argument} is one of the caller's own AOT options. */
    private static boolean isUserAotArgument(String argument) {
        return argument.contains("AOTCache") || argument.contains("AOTMode");
    }

    /** Whether {@code argument} deselects a garbage collector ({@code -XX:-Use…GC}). */
    private static boolean isCollectorDeselector(String argument) {
        return argument.startsWith("-XX:-Use") && argument.endsWith("GC");
    }

    /**
     * Whether the caller made a collector choice other than the frontend contract's
     * G1: selecting another collector, or disabling G1 itself. Such a launch is
     * user-managed: the managed cache was trained under G1, and the caller's
     * collector choice takes precedence (see {@link #userManagedChildCommand}).
     * Forwarding {@code -XX:-UseG1GC} after the pinned {@code -XX:+UseG1GC} instead
     * would leave the JVM with no collector selected and exit during
     * initialization. ({@code -XX:+UseCompressedOops} is not a selector: it does
     * not end in {@code GC}.)
     */
    static boolean hasForeignCollector(List<String> inputArguments) {
        return inputArguments.stream()
                .anyMatch(arg ->
                        (isCollectorSelector(arg) && !arg.equals("-XX:+UseG1GC"))
                                || arg.equals("-XX:-UseG1GC"));
    }

    /**
     * Whether the caller attached an instrumentation agent ({@code -javaagent},
     * {@code -agentlib}, {@code -agentpath}: coverage, profilers, debuggers). Such
     * an agent can install a JVMTI class-file-load hook, which JDK 25 lists as a
     * reason an AOT cache cannot load; training records without the caller's agent,
     * so an agent-bearing launch would plan PRODUCTION against a cache its JVM
     * then silently rejects on every invocation. Agent-bearing launches therefore
     * skip managed caching (see {@link #main}): ordinary agents re-exec
     * user-managed, and a fixed-address agent (see {@link #hasFixedAddressAgent})
     * stays in this JVM.
     */
    static boolean hasInstrumentationAgent(List<String> inputArguments) {
        return inputArguments.stream().anyMatch(SpectreBootstrap::isInstrumentationAgentArgument);
    }

    /** Whether {@code argument} attaches an instrumentation agent to this JVM. */
    public static boolean isInstrumentationAgentArgument(String argument) {
        return argument.startsWith("-javaagent:")
                || argument.startsWith("-agentlib:")
                || argument.startsWith("-agentpath:");
    }

    /**
     * Whether the caller attached a JDWP agent ({@code -agentlib:jdwp=…} or the
     * legacy {@code -Xrunjdwp} form, either mode). That agent has already
     * connected in this JVM before {@code main} runs — listening on its address
     * in server mode, attached to the debugger in client mode (the default) —
     * so no second JVM may carry it; other agents hold no such fixed connection
     * and re-exec user-managed like any other caller option.
     */
    static boolean hasFixedAddressAgent(List<String> inputArguments) {
        return inputArguments.stream().anyMatch(SpectreBootstrap::isJdwpAgentArgument);
    }

    /**
     * Whether {@code argument} loads the JDWP agent, by name ({@code
     * -agentlib:jdwp}, legacy {@code -Xrunjdwp}) or by library path ({@code
     * -agentpath:…/libjdwp.so}, {@code jdwp.dll}), whose file name is what
     * identifies it.
     */
    private static boolean isJdwpAgentArgument(String argument) {
        if (argument.startsWith("-agentlib:jdwp") || argument.startsWith("-Xrunjdwp")) {
            return true;
        }
        if (!argument.startsWith("-agentpath:")) {
            return false;
        }
        String library = argument.substring("-agentpath:".length());
        int optionsStart = library.indexOf('=');
        if (optionsStart >= 0) {
            library = library.substring(0, optionsStart);
        }
        int fileNameStart = Math.max(library.lastIndexOf('/'), library.lastIndexOf('\\')) + 1;
        return library.substring(fileNameStart)
                .toLowerCase(java.util.Locale.ROOT)
                .contains("jdwp");
    }

    /**
     * The caller JVM options (from {@code java …}, {@code JAVA_OPTS}/{@code SPECTRE_OPTS},
     * or the option environment variables — all visible as this JVM's input arguments)
     * that are forwarded to a managed child: heap sizing, agents, module access,
     * assertion and {@code -XX} tuning flags. Withheld are the bootstrap's own flags
     * (above), {@code -D} properties (forwarded separately by {@link
     * #callerSystemProperties}), collector selectors and AOT options (a foreign value
     * makes the launch user-managed instead), and {@code --enable-native-access},
     * which the frontend contract adds itself. An option shape outside the forwarded
     * set stays behind rather than risking the child's cache or protocol stdout.
     */
    public static List<String> callerJvmOptions(List<String> inputArguments) {
        return filterCallerOptions(inputArguments, false, true);
    }

    /**
     * {@link #callerJvmOptions} for a managed child running {@code plan}. When the
     * plan consumes no cache ({@code DISABLED} — {@code SPECTRE_AOT=off} — or {@code
     * UNAVAILABLE}), nothing on the child's command line was trained under the
     * frontend contract, so the caller keeps both their heap-representation flags
     * (forwarded after the pinned ones by {@link #childCommand}, where the last
     * value wins) and the standard {@code -X} options the cache-consuming
     * whitelist withholds. A cache-consuming child keeps the contract's filtering:
     * an unknown option shape could risk the cache or the protocol stdout.
     */
    static List<String> callerJvmOptions(List<String> inputArguments, AotCache.Plan plan) {
        boolean cacheConsumed =
                plan.mode() == AotCache.Mode.PRODUCTION || plan.mode() == AotCache.Mode.TRAINING;
        return filterCallerOptions(inputArguments, false, cacheConsumed);
    }

    /**
     * The caller's module options ({@code --add-*}, {@code --patch-module}, {@code
     * --module-path}, and {@code --upgrade-module-path}, with their values) from
     * the input arguments. These shape the module graph JEP 483
     * archives, so the record run and the cache-assembly JVM must carry exactly the
     * ones the production child carries, or the JVM rejects the trained cache on
     * every launch. {@code --enable-native-access} is not part of this list: every
     * phase adds it separately (see {@link #addNativeAccessArgument}). Heap, agent,
     * and tuning options do not shape the archived module graph and stay
     * production-only.
     */
    public static List<String> callerModuleOptions(List<String> inputArguments) {
        List<String> options = new ArrayList<>();
        for (int i = 0; i < inputArguments.size(); i++) {
            String argument = inputArguments.get(i);
            if (!argument.startsWith("--add-")
                    && !argument.startsWith("--patch-module")
                    && !argument.startsWith("--module-path")
                    && !argument.startsWith("--upgrade-module-path")) {
                continue;
            }
            options.add(argument);
            if (TWO_TOKEN_MODULE_OPTIONS.contains(argument) && i + 1 < inputArguments.size()) {
                options.add(inputArguments.get(++i));
            }
        }
        return options;
    }

    /**
     * Whether {@code argument} restates one of the heap-representation flags the
     * frontend contract pins. Forwarding a caller's different value to a
     * cache-consuming child would make every production launch reject the cache
     * the contract trained, silently running uncached forever, so such children
     * never carry it; a child whose plan consumes no cache keeps the caller's
     * value (see {@link #callerJvmOptions(List, AotCache.Plan)}). ({@code
     * UseCompressedClassPointers} is included: naming it also makes JDK 25 print
     * a deprecation warning.)
     */
    private static boolean isPinnedRepresentationArgument(String argument) {
        return argument.startsWith("-XX:+UseCompressedOops")
                || argument.startsWith("-XX:-UseCompressedOops")
                || argument.startsWith("-XX:+UseCompactObjectHeaders")
                || argument.startsWith("-XX:-UseCompactObjectHeaders")
                || argument.startsWith("-XX:+UseCompressedClassPointers")
                || argument.startsWith("-XX:-UseCompressedClassPointers")
                || argument.startsWith("-XX:ObjectAlignmentInBytes=");
    }

    /**
     * The full child command for a user-managed launch: this JVM's {@code java}, the
     * caller's preserved options (their AOT flags, collector selection, heap, agents,
     * module options, and {@code -D} properties, in the order given), then the real
     * CLI. No frontend arguments and no managed cache are added — the caller's cache
     * was trained under their options, and Spectre's must not be mixed into it. The
     * option environment variables are still scrubbed from the child's environment
     * (see {@link #sanitizeChildEnvironment}): every option they carried is already
     * present here explicitly, because the JVM surfaces them as input arguments.
     */
    static List<String> userManagedChildCommand(
            String javaExecutable, Path jarFile, String[] args, List<String> inputArguments) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.addAll(filterCallerOptions(inputArguments, true, false));
        command.add("-cp");
        command.add(jarFile.toString());
        command.add(MAIN_CLASS_NAME);
        command.addAll(List.of(args));
        return command;
    }

    /**
     * One pass over the caller's input arguments keeping the options a child may
     * carry. With {@code userManaged}, the caller's AOT options, collector selectors,
     * {@code --enable-native-access}, and {@code -D} properties are kept as well;
     * without it, only options compatible with the frontend contract are kept.
     */
    private static List<String> filterCallerOptions(
            List<String> inputArguments, boolean userManaged, boolean cacheConsumed) {
        // Only a cache-consuming child needs the caller's options restricted to
        // shapes that cannot invalidate what the frontend contract trained; a
        // user-managed or cache-free child keeps the caller's options as given.
        boolean permissive = userManaged || !cacheConsumed;
        List<String> options = new ArrayList<>();
        for (int i = 0; i < inputArguments.size(); i++) {
            String argument = inputArguments.get(i);
            if (BOOTSTRAP_ONLY_ARGUMENTS.contains(argument)) {
                continue;
            }
            if (!permissive && isPinnedRepresentationArgument(argument)) {
                continue;
            }
            if (argument.startsWith("-D")
                    || isUserAotArgument(argument)
                    || isCollectorSelector(argument)
                    || isCollectorDeselector(argument)
                    || argument.startsWith("--enable-native-access")) {
                if (userManaged) {
                    options.add(argument);
                }
                continue;
            }
            if (!isForwardableOption(argument, permissive)) {
                continue;
            }
            options.add(argument);
            if (TWO_TOKEN_MODULE_OPTIONS.contains(argument) && i + 1 < inputArguments.size()) {
                // --add-opens java.base/java.lang=ALL-UNNAMED arrives as two tokens;
                // forwarding the option without its value would break the child.
                options.add(inputArguments.get(++i));
            }
        }
        return options;
    }

    /**
     * Whether {@code argument} has a JVM-option shape a child may inherit. A
     * permissive child (user-managed, or consuming no cache) also keeps the
     * standard {@code -X} options outside the cache-consuming whitelist
     * ({@code -Xcomp}, {@code -Xint}, {@code -Xlog:…}, …): with no trained cache
     * at stake, dropping them would silently change the real CLI's behavior.
     */
    private static boolean isForwardableOption(String argument, boolean permissive) {
        if (permissive && argument.startsWith("-X")) {
            return true;
        }
        return argument.startsWith("-Xmx")
                || argument.startsWith("-Xms")
                || argument.startsWith("-Xss")
                || argument.startsWith("-XX:")
                || argument.startsWith("-javaagent:")
                || argument.startsWith("-agentlib:")
                || argument.startsWith("-agentpath:")
                || argument.startsWith("--add-")
                || argument.startsWith("--enable-")
                || argument.startsWith("--patch-module")
                || argument.startsWith("--module-path")
                || argument.startsWith("--upgrade-module-path")
                || argument.equals("-ea")
                || argument.equals("-da")
                || argument.startsWith("-ea:")
                || argument.startsWith("-da:")
                || argument.startsWith("-enableassertions")
                || argument.startsWith("-disableassertions")
                || argument.startsWith("-verbose:");
    }

    /** JVM option environment variables a child JVM would otherwise inherit verbatim. */
    private static final List<String> INHERITED_OPTION_ENV =
            List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");

    /**
     * Removes the JVM option environment variables from a child process environment.
     * Every option they carry already reached this JVM as an input argument, and the
     * supported ones (caller {@code -D} properties and JVM options) are forwarded
     * explicitly; letting the raw variables through would re-apply them next to the
     * frontend contract — a caller-selected collector (for example {@code
     * -XX:+UseZGC} in {@code JAVA_TOOL_OPTIONS}) would collide with the pinned G1 and
     * the child would exit with "Multiple garbage collectors selected", even with
     * {@code SPECTRE_AOT=off}.
     */
    public static void sanitizeChildEnvironment(java.util.Map<String, String> environment) {
        INHERITED_OPTION_ENV.forEach(environment::remove);
    }

    /** The currently running real-CLI child, so termination of this JVM can be propagated. */
    private static volatile Process currentChild;

    private static boolean childTerminationHookRegistered;

    /**
     * Registers (once) a shutdown hook that destroys the running child. The child inherits
     * this process's streams and can be long-lived ({@code spectre mcp}, {@code spectre
     * launch}); if the launcher is terminated by PID — an IDE/MCP host, a service timeout,
     * {@code Process.destroy()} — a process-group signal never reaches the child, and
     * without this hook it would keep running as an orphan with active sessions.
     */
    private static synchronized void registerChildTerminationHook() {
        if (childTerminationHookRegistered) {
            return;
        }
        childTerminationHookRegistered = true;
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    Process child = currentChild;
                                    if (child != null && child.isAlive()) {
                                        child.destroy();
                                    }
                                }));
    }

    private static int runChild(AotCache.Plan plan, Path jarFile, String[] args) {
        List<String> inputArguments =
                java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        return runChild(
                childCommand(
                        childJavaExecutable(),
                        plan,
                        jarFile,
                        args,
                        Runtime.version().feature(),
                        callerJvmOptions(inputArguments, plan),
                        callerSystemProperties(inputArguments)));
    }

    static int runChild(List<String> command) {
        try {
            ProcessBuilder childBuilder = new ProcessBuilder(command).inheritIO();
            sanitizeChildEnvironment(childBuilder.environment());
            Process child = childBuilder.start();
            currentChild = child;
            registerChildTerminationHook();
            try {
                return child.waitFor();
            } finally {
                if (currentChild == child) {
                    currentChild = null;
                }
            }
        } catch (IOException e) {
            // No in-process fallback: this JVM carries the packaged launcher's
            // lean startup flags (-Xmx64m, -XX:TieredStopAtLevel=1), so running
            // the full CLI here would let a long-lived `spectre mcp` inherit
            // them and die later with an OOM that hides the real problem.
            // Report the launch failure itself instead.
            System.err.println(
                    "spectre: failed to launch the CLI process (" + command.get(0) + "): "
                            + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Process child = currentChild;
            if (child != null && child.isAlive()) {
                child.destroy();
            }
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    /**
     * Trains and promotes the cache for a TRAINING plan: a discarded-stdio {@code
     * --help} record run, then a discarded-stdio assembly JVM. Entirely best-effort —
     * any failure just leaves no cache, and the caller runs the real command uncached.
     */
    private static void trainCache(AotCache.Plan plan, Path jarFile) {
        Path cacheFile = plan.cacheFile();
        if (plan.trainingFile() == null || cacheFile == null || cacheFile.getParent() == null) {
            return;
        }
        try {
            Files.createDirectories(cacheFile.getParent());
            String javaExecutable = childJavaExecutable();
            int runtimeFeature = Runtime.version().feature();
            List<String> moduleOptions =
                    callerModuleOptions(
                            java.lang.management.ManagementFactory.getRuntimeMXBean()
                                    .getInputArguments());
            int recorded =
                    runDiscarded(
                            trainingCommand(
                                    javaExecutable, plan, jarFile, runtimeFeature, moduleOptions));
            if (recorded == 0 && Files.isRegularFile(trainingConfigFile(plan))) {
                int assembled =
                        runDiscarded(
                                assemblyCommand(
                                        javaExecutable, plan, jarFile, runtimeFeature, moduleOptions));
                if (assembled == 0) {
                    promoteTrainingCache(plan);
                    return;
                }
            }
            // Never promote on a failed step: a partial training output promoted to
            // the cache location would be rejected by every later JVM, which would
            // silently run uncached forever instead of retraining. The training
            // files are unique to this process, so deleting them is race-free.
            deleteTrainingLeftovers(plan);
        } catch (IOException e) {
            // Caching is an optimisation; a training failure must never fail a command.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteTrainingLeftovers(AotCache.Plan plan) {
        try {
            Files.deleteIfExists(plan.trainingFile());
            Files.deleteIfExists(trainingConfigFile(plan));
        } catch (IOException ignored) {
            // Housekeeping only; the files are inert and unique to this process.
        }
    }

    /** Runs a command with stdout and stderr discarded; returns its exit code. */
    private static int runDiscarded(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        sanitizeChildEnvironment(builder.environment());
        Process process = builder.start();
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            process.destroy();
            throw e;
        }
    }

    static void promoteTrainingCache(AotCache.Plan plan) {
        Path trainingFile = plan.trainingFile();
        Path cacheFile = plan.cacheFile();
        if (trainingFile == null || cacheFile == null) {
            return;
        }
        try {
            // The recorded configuration sits next to the training output; it is never
            // needed once the cache has been assembled.
            Files.deleteIfExists(trainingConfigFile(plan));
        } catch (IOException ignored) {
            // Housekeeping only.
        }
        if (!Files.isRegularFile(trainingFile)) {
            return;
        }
        try {
            if (Files.isRegularFile(cacheFile) && Files.size(cacheFile) > 0) {
                // A concurrent first run won the race; its cache is equivalent.
                // A zero-length file is not a cache (planning treats it as such),
                // so it must not win: leaving it would retrain on every launch.
                Files.deleteIfExists(trainingFile);
                return;
            }
            try {
                Files.move(
                        trainingFile,
                        cacheFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(trainingFile, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // A cache that cannot be promoted only means the next run trains again.
            return;
        }
        if (cacheFile.getParent() != null) {
            AotCache.pruneOldCaches(cacheFile.getParent(), cacheFile, MAX_RETAINED_CACHES);
        }
    }

    private static Optional<Path> currentJarFile() {
        try {
            var codeSource = SpectreBootstrap.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return Optional.empty();
            }
            Path location = Path.of(codeSource.getLocation().toURI());
            boolean isJar = Files.isRegularFile(location)
                    && location.getFileName() != null
                    && location.getFileName().toString().endsWith(".jar");
            if (!isJar) {
                return Optional.empty();
            }
            Path jarFile = location.toAbsolutePath().normalize();
            // The child command re-execs with -cp <jar>, which is only sound when
            // this jar is the whole classpath (the fat jar: java -jar, Roast). A
            // thin start-script distribution launches with the CLI jar plus
            // separate dependency jars; re-execing with the thin jar alone would
            // drop every dependency, so those launches stay in-process.
            return isSoleClasspathEntry(jarFile, System.getProperty("java.class.path"))
                    ? Optional.of(jarFile)
                    : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Whether {@code jarFile} is the only entry of {@code classPath}. */
    static boolean isSoleClasspathEntry(Path jarFile, String classPath) {
        if (classPath == null || classPath.isBlank()) {
            return false;
        }
        String[] entries = classPath.split(java.util.regex.Pattern.quote(File.pathSeparator), -1);
        if (entries.length != 1) {
            return false;
        }
        try {
            return Path.of(entries[0]).toAbsolutePath().normalize().equals(jarFile);
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }

    private static void runInProcess(String[] args) {
        try {
            Class<?> mainClass = Class.forName(MAIN_CLASS_NAME);
            Method main = mainClass.getMethod("main", String[].class);
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Spectre CLI entry point " + MAIN_CLASS_NAME + " is unavailable", e);
        }
    }
}
