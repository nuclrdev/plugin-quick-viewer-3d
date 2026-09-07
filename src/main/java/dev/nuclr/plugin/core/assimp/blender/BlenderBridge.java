package dev.nuclr.plugin.core.assimp.blender;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Brings formats Assimp cannot open into the viewer by converting them with a
 * locally installed Blender.
 *
 * <p>Blender runs out of process, on files the user chose to preview. Two flags
 * are load-bearing rather than tidiness: {@code --factory-startup} keeps the
 * user's add-ons and preferences from changing the result, and
 * {@code --disable-autoexec} stops a {@code .blend} from executing the Python
 * drivers it may carry. Previewing a file must never run that file's code.
 *
 * <p>Blender is never bundled — it is found if installed, and the feature simply
 * stays off if it is not. Detection runs once, in the background, off
 * {@link #probeAsync()}.
 */
@Slf4j
public final class BlenderBridge {

    /** Formats worth converting: Assimp reads none of these. */
    private static final Set<String> EXTENSIONS =
            Set.of("blend", "usd", "usda", "usdc", "usdz", "abc");

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
    private static final int MAX_TIMEOUT_SECONDS = 1800;

    private static final String NOT_FOUND =
            "Blender was not found. Install Blender " + BlenderLocator.MINIMUM_MAJOR_VERSION
            + ".0 or newer to preview this format, or set 'blenderExecutable' in the plugin settings.";

    private static volatile Duration timeout = DEFAULT_TIMEOUT;

    /** Whether Blender is usable, as far as this session currently knows. */
    public enum Availability {
        /** Detection has not finished; assume the best and let the load report back. */
        UNKNOWN,
        AVAILABLE,
        UNAVAILABLE
    }

    /** A converted model, ready for the ordinary Assimp import path. */
    public record Conversion(Path glb, String blenderVersion, boolean cached) {
    }

    private BlenderBridge() {
    }

    // ── Capability ────────────────────────────────────────────────────────────

    /** Whether this extension is one Blender handles on the plugin's behalf. */
    public static boolean handles(String extension) {
        return extension != null && EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    public static Set<String> extensions() {
        return EXTENSIONS;
    }

    /** Never blocks: safe to consult from the EDT while deciding what to claim. */
    public static Availability availability() {
        return BlenderLocator.resolved()
                .map(install -> install.isPresent() ? Availability.AVAILABLE : Availability.UNAVAILABLE)
                .orElse(Availability.UNKNOWN);
    }

    /**
     * Starts detection in the background. Called at plugin init so the answer is
     * settled long before anyone selects a {@code .blend}.
     */
    public static void probeAsync() {
        if (BlenderLocator.resolved().isPresent()) {
            return;
        }
        Thread.ofVirtual().name("blender-detect").start(BlenderLocator::locate);
    }

    /**
     * Applies plugin settings.
     *
     * @param executable     explicit path to a Blender binary, or {@code null} to auto-detect
     * @param timeoutSeconds conversion timeout; anything below 1 keeps the default
     */
    public static void configure(String executable, int timeoutSeconds) {
        BlenderLocator.setConfiguredExecutable(executable);
        timeout = timeoutSeconds > 0
                ? Duration.ofSeconds(Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS))
                : DEFAULT_TIMEOUT;
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    /**
     * Converts {@code source} to a self-contained GLB, reusing a cached result
     * when one matches.
     *
     * <p>Blocks for as long as Blender takes, so it must be called from a
     * background thread. Honours {@code cancelled} throughout: the process is
     * killed if the user moves on mid-conversion.
     *
     * @param item      the resource being previewed; identifies the cache entry
     * @param source    a real file on disk holding that resource's bytes
     * @param extension the source format, one of {@link #extensions()}
     * @param cancelled cancellation token, polled while Blender runs
     */
    public static Conversion convert(NuclrResource item, Path source, String extension,
                                     AtomicBoolean cancelled) throws BlenderException {

        String mode = BlenderConverter.modeFor(extension);
        if (mode == null) {
            throw new BlenderException("Blender cannot import '." + extension + "'.");
        }

        BlenderInstall install = BlenderLocator.locate()
                .orElseThrow(() -> new BlenderException(NOT_FOUND));

        try {
            Path script = BlenderScript.path();
            String key = GlbCache.keyFor(item, install.version(), BlenderScript.identity());

            Path entry = GlbCache.entryFor(key);
            if (GlbCache.isUsable(entry)) {
                GlbCache.touch(entry);
                log.debug("Cache hit for {}", item.getName());
                return new Conversion(entry, install.version(), true);
            }

            Path staging = GlbCache.stagingFor(key);
            try {
                BlenderConverter.convert(install, script, mode, source, staging, timeout, cancelled);
                GlbCache.publish(staging, entry);
            } finally {
                // A no-op once the move above succeeded; the safety net otherwise.
                GlbCache.discard(staging);
            }

            GlbCache.evictLater();
            return new Conversion(entry, install.version(), false);

        } catch (IOException e) {
            throw new BlenderException("Could not write to the conversion cache: " + e.getMessage(), e);
        }
    }
}
