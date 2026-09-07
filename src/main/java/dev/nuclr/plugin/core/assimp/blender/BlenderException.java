package dev.nuclr.plugin.core.assimp.blender;

/**
 * A conversion could not be completed. The message is written for the person
 * previewing the file, not for a log: it goes straight into the viewer panel.
 */
public class BlenderException extends Exception {

    private static final long serialVersionUID = 1L;

    public BlenderException(String message) {
        super(message);
    }

    public BlenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
