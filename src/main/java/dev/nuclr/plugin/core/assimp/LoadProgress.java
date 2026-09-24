package dev.nuclr.plugin.core.assimp;

/**
 * What a model load reports while it runs. Every method is called on the loading
 * thread, never on the EDT.
 */
@FunctionalInterface
public interface LoadProgress {

    /** A short status line for the step now running. */
    void step(String message);

    /**
     * A Blender conversion is about to run. It is followed by exactly one
     * {@link #conversionFinished()}, whether the conversion succeeds, fails or is
     * cancelled. A cache hit reports both within milliseconds.
     */
    default void conversionStarted() {
    }

    /** The conversion announced by {@link #conversionStarted()} is over. */
    default void conversionFinished() {
    }
}
