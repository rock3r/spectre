package dev.sebastiano.spectre.cli.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Plans Project Leyden AOT cache usage (JEP 483/514/515, JDK 25+) for one Spectre CLI jar.
 *
 * <p>The cache is never shipped: an AOT cache is only valid for the exact JDK build, OS/arch,
 * jar path, and jar bytes it was trained against, and the JVM rejects relocated or stale
 * caches. Instead the first run on a machine trains a cache into a per-user cache directory
 * (see {@link SpectreBootstrap}), and later runs pass {@code -XX:AOTCache} to skip class
 * loading/linking and replay method profiles. A missing or invalid cache is not fatal — the
 * JVM logs an {@code [error][aot]} line and continues uncached — but the fingerprint in the
 * file name (jar path and content digest, runtime version) means a replaced jar plans
 * a fresh training run instead of pointing the JVM at a cache it would reject. An invalid
 * cache that does land on the fingerprint path (corrupt or foreign file) is likewise not
 * fatal: the JVM logs an {@code [error][aot]} line — silenced for foreground launches,
 * whose stdout is a protocol surface — and continues uncached until the cache directory
 * is deleted and the next run retrains.
 *
 * <p>This class is pure Java with no Kotlin or third-party dependencies: it runs in the
 * bootstrap JVM before the real CLI classpath is warmed up, and it is also called from the
 * Kotlin daemon launcher. All inputs are parameters so the planning logic is unit-testable.
 */
public final class AotCache {

    /** How a launch should treat the AOT cache. */
    public enum Mode {
        /** Disabled via {@code SPECTRE_AOT=off}; launch without any AOT flags. */
        DISABLED,
        /** A trained cache exists and matches this jar; pass {@code -XX:AOTCache}. */
        PRODUCTION,
        /**
         * No matching cache yet; before running the real command, the bootstrap trains
         * one with a discarded-stdio {@code --help} record run ({@code jvmArguments})
         * plus a separate cache-assembly JVM (see {@link SpectreBootstrap}).
         */
        TRAINING,
        /** Caching cannot or should not be used for this launch; run without AOT flags. */
        UNAVAILABLE
    }

    /**
     * The plan for one launch.
     *
     * @param mode how this launch uses the cache
     * @param cacheFile final cache location, or {@code null} when no cache applies
     * @param trainingFile unique temporary output the training assembly JVM writes before the
     *     cache is atomically promoted to {@code cacheFile}, or {@code null} when not training
     * @param jvmArguments JVM flags to add to the launch, empty unless producing or consuming
     *     a cache
     */
    public record Plan(Mode mode, Path cacheFile, Path trainingFile, List<String> jvmArguments) {
        public Plan {
            jvmArguments = jvmArguments == null ? List.of() : List.copyOf(jvmArguments);
        }
    }

    /** First JDK this CLI supports; its Leyden cache workflow (JEP 483/514/515) is what training uses. */
    public static final int MINIMUM_FEATURE_VERSION = 25;

    /** Module options that disable CDS, and with it Leyden cache creation. */
    private static final List<String> CDS_INCOMPATIBLE_MODULE_OPTIONS =
            List.of("--patch-module", "--upgrade-module-path", "--limit-modules");

    /** Whether {@code moduleOptions} carries an option that disables CDS. */
    private static boolean hasCdsIncompatibleModuleOption(List<String> moduleOptions) {
        if (moduleOptions == null) {
            return false;
        }
        return moduleOptions.stream()
                .anyMatch(option -> CDS_INCOMPATIBLE_MODULE_OPTIONS.stream()
                        .anyMatch(name -> option.equals(name) || option.startsWith(name + "=")));
    }

    private static final String DISABLE_ENV = "SPECTRE_AOT";
    private static final String CACHE_DIR_ENV = "SPECTRE_AOT_CACHE_DIR";
    private static final String[] USER_OPTION_ENV = {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"};
    private static final int FINGERPRINT_HEX_LENGTH = 32;

    private AotCache() {}

    /**
     * Plans cache usage for {@code jarFile}.
     *
     * @param allowTraining whether this launch may produce a cache when none matches. Only the
     *     foreground CLI launch trains; the daemon is long-lived and launched detached, so it
     *     only ever consumes a ready cache (see {@link #daemonJvmArguments}).
     * @param trainingSuffix uniqueness suffix for the temporary training output (the caller
     *     passes its pid), so concurrent first runs never write the same file.
     */
    public static Plan plan(
            Path jarFile,
            Map<String, String> environment,
            Path userHome,
            String osName,
            String osArch,
            String runtimeVersion,
            int featureVersion,
            boolean allowTraining,
            String trainingSuffix,
            List<String> moduleOptions) {
        if (jarFile == null || !isJarFile(jarFile)) {
            return unavailable(null);
        }
        if (isDisabled(environment)) {
            return new Plan(Mode.DISABLED, null, null, List.of());
        }
        if (featureVersion < MINIMUM_FEATURE_VERSION || hasUserAotOptions(environment)) {
            return unavailable(null);
        }
        if (hasCdsIncompatibleModuleOption(moduleOptions)) {
            // --patch-module, --upgrade-module-path, and --limit-modules disable
            // CDS, and Leyden cache creation is CDS-based: the record/assembly
            // sequence could never promote a cache, so a TRAINING plan would
            // repeat its discarded work on every launch. Run uncached instead;
            // the child still receives the caller's options (see SpectreBootstrap).
            return unavailable(null);
        }
        final Path cacheFile;
        try {
            cacheFile = cacheDirectory(environment, userHome, osName)
                    .resolve("spectre-" + fingerprint(jarFile, runtimeVersion, osName, osArch, moduleOptions) + ".aot");
        } catch (IOException | SecurityException | java.nio.file.InvalidPathException e) {
            // A malformed SPECTRE_AOT_CACHE_DIR or LOCALAPPDATA must degrade to an
            // uncached launch, never terminate the bootstrap.
            return unavailable(null);
        }
        try {
            if (Files.isRegularFile(cacheFile) && Files.size(cacheFile) > 0) {
                return new Plan(
                        Mode.PRODUCTION, cacheFile, null, List.of("-XX:AOTCache=" + cacheFile));
            }
        } catch (IOException | SecurityException e) {
            return unavailable(cacheFile);
        }
        if (!allowTraining) {
            return unavailable(cacheFile);
        }
        Path trainingFile =
                cacheFile.resolveSibling(cacheFile.getFileName() + ".training-" + trainingSuffix);
        // Record step of the two-step workflow (JEP 483): the bootstrap runs the CLI's
        // --help with these flags and discarded stdio, then assembles trainingFile from
        // the recorded configuration with a second, main-class-less JVM. The recorded
        // configuration lives at trainingFile + ".config" by bootstrap convention.
        return new Plan(
                Mode.TRAINING,
                cacheFile,
                trainingFile,
                List.of(
                        "-XX:AOTMode=record",
                        "-XX:AOTConfiguration=" + trainingFile + ".config"));
    }

    /**
     * JVM arguments for a daemon launch: a ready cache when the classpath is a single existing
     * jar and one matches, otherwise no arguments. The daemon never trains.
     *
     * <p>A consumed cache also needs {@code --enable-native-access=ALL-UNNAMED}: the cache
     * was recorded and assembled with that module option, and JEP 483 rejects an archive
     * whose archived module options differ from the consuming JVM's. Without it the daemon
     * would silently start uncached on every launch.
     */
    public static List<String> daemonJvmArguments(
            String classPath,
            Map<String, String> environment,
            Path userHome,
            String osName,
            String osArch,
            String runtimeVersion,
            int featureVersion,
            List<String> moduleOptions,
            List<String> inputArguments) {
        return singleJarFile(classPath)
                .map(jar -> {
                    Plan plan = plan(jar, environment, userHome, osName, osArch, runtimeVersion,
                            featureVersion, false, "daemon", moduleOptions);
                    if (SpectreBootstrap.hasForeignCollector(inputArguments)) {
                        // The caller chose the collector, so the whole launch
                        // chain is user-managed: handing the daemon the
                        // G1-trained managed cache would override that choice
                        // in the long-lived process doing the attach work.
                        return List.<String>of();
                    }
                    if (hasUserAotArguments(inputArguments, plan.cacheFile())) {
                        // The caller manages AOT for this launch chain; the
                        // daemon consumes no managed cache. (An -XX:AOTCache
                        // naming the managed cache itself is not the caller's:
                        // it is the flag the bootstrap put on this CLI's own
                        // command line, and must not trip this guard.)
                        return List.<String>of();
                    }
                    if (plan.mode() != Mode.PRODUCTION) {
                        return plan.jvmArguments();
                    }
                    // The cache was recorded and assembled under the frontend
                    // layout (explicit G1 and the pinned heap representation in
                    // SpectreBootstrap.FRONTEND_JVM_ARGUMENTS); the daemon must
                    // run with that same layout or the JVM rejects the archive —
                    // on a machine whose ergonomics would pick another collector
                    // or representation, on every start.
                    List<String> arguments =
                            new java.util.ArrayList<>(SpectreBootstrap.FRONTEND_JVM_ARGUMENTS);
                    arguments.addAll(plan.jvmArguments());
                    arguments.add("--enable-native-access=ALL-UNNAMED");
                    return List.copyOf(arguments);
                })
                .orElse(List.of());
    }

    /** {@link #daemonJvmArguments} resolved against this process's environment and runtime. */
    public static List<String> daemonJvmArguments(String classPath) {
        List<String> inputArguments =
                java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        if (SpectreBootstrap.hasInstrumentationAgent(inputArguments)) {
            // An instrumentation agent whose JVMTI hooks can make a cache
            // unloadable: the daemon consumes no managed cache.
            return List.of();
        }
        return daemonJvmArguments(
                classPath,
                System.getenv(),
                userHomePath(),
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                runtimeIdentity(),
                Runtime.version().feature(),
                SpectreBootstrap.callerModuleOptions(inputArguments),
                inputArguments);
    }

    /**
     * Whether the input arguments carry caller-supplied AOT options, ignoring an
     * {@code -XX:AOTCache} that names {@code managedCacheFile}: the bootstrap puts
     * exactly that flag on the managed CLI's own command line, so its presence
     * proves a Spectre-managed launch, not a caller-managed one. Any {@code
     * AOTMode} option, or a cache pointing anywhere else, is the caller's.
     */
    public static boolean hasUserAotArguments(List<String> inputArguments, Path managedCacheFile) {
        return inputArguments.stream()
                .anyMatch(arg -> {
                    if (arg.contains("AOTMode")) {
                        return true;
                    }
                    if (arg.startsWith("-XX:AOTCache=")) {
                        String value = arg.substring("-XX:AOTCache=".length());
                        return managedCacheFile == null
                                || !value.equals(managedCacheFile.toString());
                    }
                    return arg.contains("AOTCache");
                });
    }

    /**
     * This process's user home as a path, or {@code null} when the {@code user.home}
     * property is missing or malformed. A caller can supply that property (via
     * {@code -Duser.home} in {@code JAVA_OPTS}/{@code SPECTRE_OPTS} or an inherited
     * JVM-option variable), and converting it must never abort a launch before
     * planning starts: callers pass the {@code null} on to {@link #plan}, which
     * degrades to an uncached launch.
     */
    public static Path userHomePath() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return null;
        }
        try {
            return Path.of(userHome);
        } catch (java.nio.file.InvalidPathException | SecurityException e) {
            return null;
        }
    }

    /**
     * Identity of this runtime for cache fingerprinting: the version plus the vendor and
     * VM build, so two distributions reporting the same {@link Runtime#version()} text
     * (for example a Temurin and a JBR build of the same release) do not share caches.
     */
    public static String runtimeIdentity() {
        return Runtime.version().toString()
                + "|" + System.getProperty("java.vendor", "")
                + "|" + System.getProperty("java.vm.version", "");
    }

    /**
     * Returns the single classpath entry when {@code classPath} consists of exactly one
     * existing jar file. Exploded class directories (tests, Gradle dev runs) and multi-entry
     * classpaths have no stable cache identity and return empty.
     */
    public static Optional<Path> singleJarFile(String classPath) {
        if (classPath == null || classPath.isBlank()) {
            return Optional.empty();
        }
        String[] entries = classPath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1);
        if (entries.length != 1) {
            return Optional.empty();
        }
        final Path candidate;
        try {
            candidate = Path.of(entries[0]);
        } catch (java.nio.file.InvalidPathException e) {
            return Optional.empty();
        }
        return isJarFile(candidate) ? Optional.of(candidate.toAbsolutePath().normalize()) : Optional.empty();
    }

    /**
     * {@code userHome} or a planning failure the caller already handles: a missing
     * or malformed user home (see {@link #userHomePath}) means no cache directory
     * can be derived, and {@link #plan} turns this into an uncached launch.
     */
    private static Path requireUserHome(Path userHome) {
        if (userHome == null) {
            throw new java.nio.file.InvalidPathException("user.home", "user home is unavailable");
        }
        return userHome;
    }

    /** Per-user directory holding trained caches for this OS. */
    public static Path cacheDirectory(Map<String, String> environment, Path userHome, String osName) {
        String override = environment.getOrDefault(CACHE_DIR_ENV, "").strip();
        if (!override.isEmpty()) {
            return Path.of(override);
        }
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String localAppData = environment.getOrDefault("LOCALAPPDATA", "").strip();
            Path base = localAppData.isEmpty()
                    ? requireUserHome(userHome).resolve("AppData").resolve("Local")
                    : Path.of(localAppData);
            return base.resolve("Spectre").resolve("aot");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return requireUserHome(userHome)
                    .resolve("Library").resolve("Caches").resolve("Spectre").resolve("aot");
        }
        String xdgCache = environment.getOrDefault("XDG_CACHE_HOME", "").strip();
        Path base = xdgCache.isEmpty() ? requireUserHome(userHome).resolve(".cache") : Path.of(xdgCache);
        return base.resolve("spectre").resolve("aot");
    }

    /**
     * Best-effort retention for a cache directory: keeps {@code keep} plus the newest
     * {@code maxRetained - 1} other {@code spectre-*.aot} files and deletes the rest, along
     * with orphaned {@code .training-*} outputs from interrupted training runs. Each trained
     * cache is tens of megabytes and every jar upgrade fingerprints to a new file, so
     * without pruning the directory grows by one cache per installed version. Keeping a
     * couple of predecessors leaves a recently replaced install (same jar path is
     * impossible, but a second installation location is not) one run away from a cache hit
     * instead of a retrain. Files that cannot be deleted (for example a cache mapped by a
     * running process on Windows) are simply left for the next attempt.
     */
    public static void pruneOldCaches(Path directory, Path keep, int maxRetained) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (var stream = Files.list(directory)) {
            List<Path> caches = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("spectre-") && name.endsWith(".aot");
                    })
                    .sorted((left, right) -> {
                        try {
                            return Files.getLastModifiedTime(right)
                                    .compareTo(Files.getLastModifiedTime(left));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .toList();
            int retained = 0;
            for (Path cache : caches) {
                if (cache.equals(keep) || retained < Math.max(0, maxRetained - 1)) {
                    if (!cache.equals(keep)) {
                        retained++;
                    }
                    continue;
                }
                deleteQuietly(cache);
            }
        } catch (IOException | SecurityException ignored) {
            // Pruning is housekeeping; failure must never fail a launch.
        }
        // Orphaned training outputs are never usable (their pid-unique names are only
        // promoted by the run that created them). Only remove ones old enough that no
        // concurrent first run can still be writing them.
        long orphanCutoffMillis = System.currentTimeMillis() - 60L * 60L * 1000L;
        try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains(".training-"))
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis() < orphanCutoffMillis;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(AotCache::deleteQuietly);
        } catch (IOException | SecurityException ignored) {
            // As above: housekeeping only.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | SecurityException ignored) {
            // Left for the next pruning attempt.
        }
    }

    private static boolean isJarFile(Path candidate) {
        return candidate != null
                && candidate.getFileName() != null
                && candidate.getFileName().toString().endsWith(".jar")
                && Files.isRegularFile(candidate);
    }

    private static boolean isDisabled(Map<String, String> environment) {
        String value = environment.getOrDefault(DISABLE_ENV, "").strip().toLowerCase(Locale.ROOT);
        return value.equals("off") || value.equals("false") || value.equals("0") || value.equals("no");
    }

    private static boolean hasUserAotOptions(Map<String, String> environment) {
        for (String name : USER_OPTION_ENV) {
            String value = environment.getOrDefault(name, "");
            if (value.contains("AOTCache") || value.contains("AOTMode")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the caller put AOT options on this JVM's own command line (directly, or
     * through a start script's {@code JAVA_OPTS}/{@code SPECTRE_OPTS}). Those reach the
     * JVM as input arguments rather than through the option environment variables, and
     * user-managed AOT options take precedence over Spectre's managed cache: callers
     * must run the CLI in-process (where their flags apply) or skip managed arguments.
     */
    public static boolean hasUserAotArguments(List<String> inputArguments) {
        return inputArguments.stream()
                .anyMatch(arg -> arg.contains("AOTCache") || arg.contains("AOTMode"));
    }

    private static String fingerprint(
            Path jarFile, String runtimeVersion, String osName, String osArch, List<String> moduleOptions)
            throws IOException {
        // The OS/arch and the runtime identity (version, vendor, VM build) are part of
        // the identity: an AOT archive is only valid for the exact JDK build and
        // OS/CPU combination that produced it, and a shared home directory (for
        // example NFS) must not let one host plan PRODUCTION against another host's
        // incompatible cache and then reject it forever. The caller's module
        // options are part of it too: the archived module graph must match the
        // consuming JVM's exactly, so a different --add-opens set needs its own
        // cache instead of a PRODUCTION plan against an incompatible one. The
        // options are sorted and deduplicated first — their order does not
        // change the module graph, so it must not change the identity either.
        String moduleIdentity = moduleIdentity(moduleOptions);
        // The jar's content digest is part of the identity: size and modification
        // time are not, because deployment tooling that preserves timestamps can
        // replace the jar with different bytes of the same size, and the old
        // cache would then be selected (and rejected, or worse, used) forever.
        // Hashing the jar on every launch costs a few tens of milliseconds —
        // the price of an identity that cannot point at the wrong archive.
        String identity = jarFile.toAbsolutePath().normalize()
                + "\n" + Files.size(jarFile)
                + "\n" + contentDigest(jarFile)
                + "\n" + runtimeVersion
                + "\n" + osName
                + "\n" + osArch
                + "\n" + moduleIdentity;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, FINGERPRINT_HEX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }

    /**
     * Normalized identity of a module-option list: option/value pairs are kept
     * associated (a token not starting an option belongs to the option before
     * it), repeated last-wins options collapse to their effective final
     * occurrence, and the surviving pairs are sorted and deduplicated. Sorting
     * the flattened tokens instead would conflate different module graphs —
     * {@code --add-opens A --add-exports B} and {@code --add-opens B
     * --add-exports A} share a token multiset but are not the same graph.
     * Without the last-wins collapse, {@code --module-path=/a --module-path=/b}
     * and its reversal would share one fingerprint although HotSpot runs them
     * with different effective module paths (the final occurrence wins), so the
     * second launch would plan PRODUCTION against a cache the JVM rejects on
     * every launch instead of training a compatible one.
     */
    private static String moduleIdentity(List<String> moduleOptions) {
        if (moduleOptions == null || moduleOptions.isEmpty()) {
            return "";
        }
        List<String> pairs = new java.util.ArrayList<>();
        for (String token : moduleOptions) {
            if (token.startsWith("--") || token.equals("-p") || pairs.isEmpty()) {
                pairs.add(token);
            } else {
                pairs.set(pairs.size() - 1, pairs.get(pairs.size() - 1) + " " + token);
            }
        }
        List<String> effective = new java.util.ArrayList<>();
        for (String pair : pairs) {
            String lastWinsName = lastWinsOptionName(pair);
            if (lastWinsName != null) {
                effective.removeIf(existing -> lastWinsName.equals(lastWinsOptionName(existing)));
            }
            effective.add(pair);
        }
        return String.join("\n", effective.stream().sorted().distinct().toList());
    }

    /** Module options HotSpot resolves to their final occurrence when repeated. */
    private static final List<String> LAST_WINS_MODULE_OPTIONS =
            List.of(
                    "--module-path",
                    "-p",
                    "--upgrade-module-path",
                    "--limit-modules",
                    "--illegal-native-access");

    /**
     * The canonical name of the last-wins option {@code pair} starts, or {@code
     * null} when it starts an accumulating option. The {@code -p} alias
     * canonicalizes to {@code --module-path}: both forms set the same property,
     * so a superseded occurrence must collapse across forms too.
     */
    private static String lastWinsOptionName(String pair) {
        int end = pair.length();
        int equals = pair.indexOf('=');
        if (equals >= 0 && equals < end) {
            end = equals;
        }
        int space = pair.indexOf(' ');
        if (space >= 0 && space < end) {
            end = space;
        }
        String name = pair.substring(0, end);
        if (!LAST_WINS_MODULE_OPTIONS.contains(name)) {
            return null;
        }
        return name.equals("-p") ? "--module-path" : name;
    }

    /** SHA-256 of the jar's bytes, streamed so large jars are not held in memory. */
    private static String contentDigest(Path jarFile) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
        try (var input = Files.newInputStream(jarFile)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Plan unavailable(Path cacheFile) {
        return new Plan(Mode.UNAVAILABLE, cacheFile, null, List.of());
    }
}
