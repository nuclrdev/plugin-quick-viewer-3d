package dev.nuclr.plugin.core.assimp.blender;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

/** Runs one headless Blender conversion from a source model to a GLB. */
@Slf4j
final class BlenderConverter {

    private static final String OK_MARKER = "NUCLR_EXPORT_OK";
    private static final String ERROR_MARKER = "NUCLR_EXPORT_ERROR:";

    /** Blender paths make error lines long; the panel only has a narrow sidebar. */
    private static final int MAX_REPORTED_ERROR_CHARS = 400;

    private BlenderConverter() {
    }

    /**
     * The converter script's mode for a file extension, or {@code null} when
     * Blender has nothing to offer for it.
     */
    static String modeFor(String extension) {
        if (extension == null) {
            return null;
        }
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "blend" -> "blend";
            case "usd", "usda", "usdc", "usdz" -> "usd";
            case "abc" -> "abc";
            default -> null;
        };
    }

    static void convert(BlenderInstall install, Path script, String mode,
                        Path source, Path destination,
                        Duration timeout, AtomicBoolean cancelled) throws BlenderException {

        List<String> command = List.of(
                install.executable().toString(),
                "--background",       // no window, no event loop
                "--factory-startup",  // ignore the user's preferences and add-ons
                "--disable-autoexec", // never run scripts carried inside the previewed file
                "--python", script.toString(),
                "--", mode,
                source.toAbsolutePath().toString(),
                destination.toAbsolutePath().toString());

        log.debug("Converting {} with Blender {}", source, install.version());

        // Run inside the cache directory: anything Blender writes relative to the
        // working directory lands there rather than in the user's model folder.
        ProcessRunner.Outcome outcome =
                ProcessRunner.run(command, destination.getParent(), timeout, cancelled);

        switch (outcome.status()) {
            case START_FAILED -> throw new BlenderException("Could not start Blender: " + outcome.failure());
            case CANCELLED -> throw new BlenderException("Conversion cancelled.");
            case TIMED_OUT -> throw new BlenderException(
                    "Blender did not finish within " + timeout.toSeconds() + " s.");
            case COMPLETED -> { /* exit code and markers are checked below */ }
        }

        if (!outcome.succeeded() || !outcome.output().contains(OK_MARKER)) {
            log.debug("Blender output:\n{}", outcome.output());
            throw new BlenderException(extractError(outcome.output(), outcome.exitCode()));
        }

        try {
            if (!Files.isRegularFile(destination) || Files.size(destination) == 0) {
                throw new BlenderException("Blender reported success but wrote no data.");
            }
        } catch (IOException e) {
            throw new BlenderException("Could not read the converted model: " + e.getMessage(), e);
        }
    }

    /**
     * Turns Blender's output into one line worth showing. The script reports its
     * own failures through a marker; anything else fell over before reaching it.
     */
    static String extractError(String output, int exitCode) {
        String reported = null;
        if (output != null) {
            for (String line : output.split("\\R")) {
                int marker = line.indexOf(ERROR_MARKER);
                if (marker >= 0) {
                    reported = line.substring(marker + ERROR_MARKER.length()).strip();
                }
            }
        }
        if (reported == null || reported.isBlank()) {
            reported = "Blender exited with code " + exitCode + ".";
        }
        return reported.length() > MAX_REPORTED_ERROR_CHARS
                ? reported.substring(0, MAX_REPORTED_ERROR_CHARS) + "…"
                : reported;
    }
}
