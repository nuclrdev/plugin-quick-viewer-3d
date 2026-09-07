package dev.nuclr.plugin.core.assimp.blender;

import java.nio.file.Path;

/**
 * A Blender executable that answered {@code --version} and met the minimum
 * version requirement.
 *
 * @param executable absolute path to the binary
 * @param version    version as reported by Blender itself, e.g. {@code "4.4.3"}
 * @param major      major version number, already checked against the minimum
 */
public record BlenderInstall(Path executable, String version, int major) {
}
