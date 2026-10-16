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
 *       startup flags the long-lived MCP server must not inherit), or
 *   <li>runs the real CLI in-process when caching is unavailable (JDK &lt; 25, exploded
 *       classpath, user-managed AOT flags), so behaviour is unchanged.
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
        Optional<Path> jarFile = currentJarFile();
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
        if (!shouldExecInChild(plan)) {
            runInProcess(args);
            return;
        }
        System.exit(runChild(plan, jarFile.orElseThrow(), args));
    }

    private static AotCache.Plan planFor(Path jarFile) {
        return AotCache.plan(
                jarFile,
                System.getenv(),
                Path.of(System.getProperty("user.home")),
                System.getProperty("os.name"),
                Runtime.version().toString(),
                Runtime.version().feature(),
                true,
                Long.toString(ProcessHandle.current().pid()));
    }

    /** Launches that produce or consume a cache — and the explicitly disabled launch — need a child JVM. */
    static boolean shouldExecInChild(AotCache.Plan plan) {
        return plan.mode() == AotCache.Mode.PRODUCTION
                || plan.mode() == AotCache.Mode.TRAINING
                || plan.mode() == AotCache.Mode.DISABLED;
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
            String javaExecutable, AotCache.Plan plan, Path jarFile, String[] args, int runtimeFeature) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.addAll(FRONTEND_JVM_ARGUMENTS);
        command.addAll(plan.jvmArguments());
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
            String javaExecutable, AotCache.Plan plan, Path jarFile, int runtimeFeature) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.addAll(FRONTEND_JVM_ARGUMENTS);
        command.addAll(plan.jvmArguments());
        addNativeAccessArgument(command, runtimeFeature);
        command.add("-cp");
        command.add(jarFile.toString());
        command.add(MAIN_CLASS_NAME);
        command.add("--help");
        return command;
    }

    /** The cache-assembly JVM: creates the training cache from the recorded configuration. */
    static List<String> assemblyCommand(
            String javaExecutable, AotCache.Plan plan, Path jarFile, int runtimeFeature) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.addAll(FRONTEND_JVM_ARGUMENTS);
        command.add("-XX:AOTMode=create");
        command.add("-XX:AOTConfiguration=" + trainingConfigFile(plan));
        command.add("-XX:AOTCacheOutput=" + plan.trainingFile());
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

    private static int runChild(AotCache.Plan plan, Path jarFile, String[] args) {
        try {
            Process child = new ProcessBuilder(
                            childCommand(
                                    childJavaExecutable(),
                                    plan,
                                    jarFile,
                                    args,
                                    Runtime.version().feature()))
                    .inheritIO()
                    .start();
            return child.waitFor();
        } catch (IOException e) {
            // The JVM we are running on exists, so this is unexpected; fall back to running
            // in-process rather than failing a command that would otherwise work.
            runInProcess(args);
            return 0;
        } catch (InterruptedException e) {
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
            int recorded = runDiscarded(trainingCommand(javaExecutable, plan, jarFile, runtimeFeature));
            if (recorded == 0 && Files.isRegularFile(trainingConfigFile(plan))) {
                int assembled = runDiscarded(assemblyCommand(javaExecutable, plan, jarFile, runtimeFeature));
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
        return new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
    }

    private static void promoteTrainingCache(AotCache.Plan plan) {
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
            if (Files.isRegularFile(cacheFile)) {
                // A concurrent first run won the race; its cache is equivalent.
                Files.deleteIfExists(trainingFile);
                return;
            }
            try {
                Files.move(trainingFile, cacheFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(trainingFile, cacheFile);
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
            return isJar ? Optional.of(location.toAbsolutePath().normalize()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
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
