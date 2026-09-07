package dev.nuclr.plugin.core.assimp.blender;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Finds a usable Blender executable on this machine.
 *
 * <p>Candidates come from an explicit setting, the environment, {@code PATH} and
 * the platform's usual install locations, in that order. A candidate only counts
 * once it has actually run {@code --version} and reported a new enough release:
 * uninstalling commonly leaves the versioned folder behind with the binary gone,
 * so the presence of a directory proves nothing.
 *
 * <p>The result is resolved once and memoised. {@link #locate()} blocks while the
 * probe runs; {@link #resolved()} never blocks and is what UI threads should ask.
 */
@Slf4j
public final class BlenderLocator {

    /** USD import and the glTF exporter options this plugin relies on land in 3.0. */
    static final int MINIMUM_MAJOR_VERSION = 3;

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

    private static final Pattern VERSION_BANNER =
            Pattern.compile("Blender\\s+(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /** Matches the "Blender 4.4" folder name the Windows and Linux installers create. */
    private static final Pattern VERSIONED_DIRECTORY =
            Pattern.compile("[Bb]lender[\\s-]*(\\d+)\\.(\\d+).*");

    private static final Object LOCK = new Object();

    /** Null until the probe has run; the Optional itself is the memoised answer. */
    private static volatile Optional<BlenderInstall> resolved;

    private static volatile String configuredExecutable;

    /** A parsed {@code blender --version} banner. */
    record Version(int major, int minor, int patch) {

        String text() {
            return major + "." + minor + "." + patch;
        }
    }

    private BlenderLocator() {
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Points the locator at a specific binary, ahead of every other candidate.
     * A change discards the memoised result so the next lookup probes again.
     *
     * @param executable path to a Blender binary, or {@code null} to auto-detect
     */
    public static void setConfiguredExecutable(String executable) {
        String cleaned = executable != null && !executable.isBlank() ? executable.strip() : null;
        if (!Objects.equals(cleaned, configuredExecutable)) {
            synchronized (LOCK) {
                configuredExecutable = cleaned;
                resolved = null;
            }
        }
    }

    /** Resolves Blender, probing candidates on the first call. Blocks. */
    public static Optional<BlenderInstall> locate() {
        Optional<BlenderInstall> memoised = resolved;
        if (memoised != null) {
            return memoised;
        }
        synchronized (LOCK) {
            if (resolved == null) {
                resolved = detect();
            }
            return resolved;
        }
    }

    /**
     * The memoised result, or an empty outer Optional if the probe has not
     * finished yet. Never blocks, so it is safe to call from the EDT.
     */
    public static Optional<Optional<BlenderInstall>> resolved() {
        return Optional.ofNullable(resolved);
    }

    // ── Detection ─────────────────────────────────────────────────────────────

    private static Optional<BlenderInstall> detect() {
        long startedAt = System.nanoTime();
        for (Path candidate : candidates()) {
            Optional<BlenderInstall> install = probe(candidate);
            if (install.isPresent()) {
                log.info("Using Blender {} at {} (found in {} ms)", install.get().version(),
                         install.get().executable(), (System.nanoTime() - startedAt) / 1_000_000);
                return install;
            }
        }
        log.info("No usable Blender executable found; .blend, USD and Alembic preview is disabled");
        return Optional.empty();
    }

    /** Ordered, deduplicated locations worth trying. */
    private static Collection<Path> candidates() {
        Collection<Path> out = new LinkedHashSet<>();

        addPath(out, configuredExecutable);
        addPath(out, System.getProperty("nuclr.blender.path"));
        addPath(out, environment("NUCLR_BLENDER_PATH"));
        addPath(out, environment("BLENDER_PATH"));

        addSearchPathEntries(out);

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            addWindowsCandidates(out);
        } else if (os.contains("mac")) {
            addMacCandidates(out);
        } else {
            addLinuxCandidates(out);
        }
        return out;
    }

    private static void addSearchPathEntries(Collection<Path> out) {
        String searchPath = environment("PATH");
        if (searchPath == null) {
            return;
        }
        String executableName = isWindows() ? "blender.exe" : "blender";
        for (String entry : searchPath.split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) {
                addRoot(out, entry.strip(), executableName);
            }
        }
    }

    private static void addWindowsCandidates(Collection<Path> out) {
        List<Path> roots = new ArrayList<>();
        addRoot(roots, environment("ProgramFiles"), "Blender Foundation");
        addRoot(roots, environment("ProgramW6432"), "Blender Foundation");
        addRoot(roots, environment("ProgramFiles(x86)"), "Blender Foundation");
        addRoot(roots, environment("LOCALAPPDATA"), "Programs", "Blender Foundation");

        for (Path root : roots) {
            addVersionedInstalls(out, root, "blender.exe");
        }
        addRoot(out, environment("ProgramFiles(x86)"),
                "Steam", "steamapps", "common", "Blender", "blender.exe");
    }

    private static void addMacCandidates(Collection<Path> out) {
        addPath(out, "/Applications/Blender.app/Contents/MacOS/Blender");
        addPath(out, "/Applications/Blender/Blender.app/Contents/MacOS/Blender");
        addRoot(out, System.getProperty("user.home"),
                "Applications", "Blender.app", "Contents", "MacOS", "Blender");
    }

    private static void addLinuxCandidates(Collection<Path> out) {
        addPath(out, "/usr/bin/blender");
        addPath(out, "/usr/local/bin/blender");
        addPath(out, "/snap/bin/blender");
        addPath(out, "/var/lib/flatpak/exports/bin/org.blender.Blender");
        addRoot(out, System.getProperty("user.home"), ".local", "bin", "blender");
        addRoot(out, System.getProperty("user.home"),
                ".local", "share", "flatpak", "exports", "bin", "org.blender.Blender");
        addVersionedInstalls(out, Path.of("/opt"), "blender");
    }

    /**
     * Adds {@code <root>/Blender X.Y/<executable>} for every versioned folder under
     * {@code root}, newest first — a machine with several releases installed should
     * preview with the most capable one.
     */
    private static void addVersionedInstalls(Collection<Path> out, Path root, String executableName) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        Comparator<Version> newestFirst = Comparator
                .comparingInt(Version::major)
                .thenComparingInt(Version::minor)
                .reversed();

        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .filter(directory -> directoryVersion(directory) != null)
                    .sorted(Comparator.comparing(BlenderLocator::directoryVersion, newestFirst))
                    .forEach(directory -> out.add(directory.resolve(executableName)));
        } catch (Exception e) {
            log.debug("Could not list {}: {}", root, e.getMessage());
        }
    }

    private static Version directoryVersion(Path directory) {
        Path name = directory.getFileName();
        if (name == null) {
            return null;
        }
        Matcher matcher = VERSIONED_DIRECTORY.matcher(name.toString());
        return matcher.matches()
                ? new Version(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), 0)
                : null;
    }

    /** Runs {@code --version} and accepts the binary only if it answers properly. */
    private static Optional<BlenderInstall> probe(Path executable) {
        if (!Files.isRegularFile(executable)) {
            return Optional.empty();
        }

        ProcessRunner.Outcome outcome = ProcessRunner.run(
                List.of(executable.toString(), "--version"), null, PROBE_TIMEOUT, null);

        if (!outcome.succeeded()) {
            log.debug("{} did not answer --version ({})", executable, outcome.status());
            return Optional.empty();
        }

        Optional<Version> version = parseVersion(outcome.output());
        if (version.isEmpty()) {
            log.debug("{} answered --version with no recognisable banner", executable);
            return Optional.empty();
        }

        Version found = version.get();
        if (found.major() < MINIMUM_MAJOR_VERSION) {
            log.info("Ignoring Blender {} at {}: {}.0 or newer is required",
                     found.text(), executable, MINIMUM_MAJOR_VERSION);
            return Optional.empty();
        }
        return Optional.of(new BlenderInstall(executable.toAbsolutePath(), found.text(), found.major()));
    }

    /** Extracts the version from a {@code blender --version} banner. */
    static Optional<Version> parseVersion(String banner) {
        if (banner == null) {
            return Optional.empty();
        }
        Matcher matcher = VERSION_BANNER.matcher(banner);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new Version(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0));
    }

    // ── Small helpers ─────────────────────────────────────────────────────────

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String environment(String name) {
        try {
            String value = System.getenv(name);
            return value != null && !value.isBlank() ? value : null;
        } catch (SecurityException e) {
            return null;
        }
    }

    private static void addPath(Collection<Path> out, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return;
        }
        try {
            out.add(Path.of(candidate.strip()));
        } catch (InvalidPathException e) {
            log.debug("Ignoring malformed Blender path '{}'", candidate);
        }
    }

    private static void addRoot(Collection<Path> out, String base, String... segments) {
        if (base == null || base.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(base);
            for (String segment : segments) {
                path = path.resolve(segment);
            }
            out.add(path);
        } catch (InvalidPathException e) {
            log.debug("Ignoring malformed base path '{}'", base);
        }
    }
}
