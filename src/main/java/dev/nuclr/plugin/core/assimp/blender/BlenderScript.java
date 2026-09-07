package dev.nuclr.plugin.core.assimp.blender;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

/**
 * Unpacks the converter script from the plugin jar onto disk, where Blender can
 * run it.
 *
 * <p>The file is named after a hash of its own contents, so a plugin upgrade that
 * changes the script writes a new file and — because that hash also feeds the
 * cache key — retires the GLBs the previous version produced.
 */
@Slf4j
final class BlenderScript {

    private static final String RESOURCE = "/blender/nuclr_export_glb.py";

    private static final Object LOCK = new Object();

    private static volatile Path materialised;
    private static volatile String identity;

    private BlenderScript() {
    }

    /** Path to the on-disk script, unpacking it on first use. */
    static Path path() throws IOException {
        Path memoised = materialised;
        if (memoised != null && Files.isRegularFile(memoised)) {
            return memoised;
        }
        synchronized (LOCK) {
            if (materialised == null || !Files.isRegularFile(materialised)) {
                unpack();
            }
            return materialised;
        }
    }

    /** Content hash of the script, part of the cache key. */
    static String identity() throws IOException {
        path();
        return identity;
    }

    private static void unpack() throws IOException {
        byte[] source = read();
        String hash = Digests.shortHash(source);

        Path directory = Files.createDirectories(GlbCache.root().resolve("bin"));
        Path target = directory.resolve("nuclr_export_glb-" + hash + ".py");

        // The name already pins the contents, so an entry of the right size is
        // the same script; only a partial write is worth redoing.
        if (!Files.isRegularFile(target) || Files.size(target) != source.length) {
            writeAtomically(target, source);
            log.debug("Unpacked the Blender converter script to {}", target);
        }

        identity = hash;
        materialised = target;
    }

    private static byte[] read() throws IOException {
        try (InputStream stream = BlenderScript.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IOException("The plugin jar is missing " + RESOURCE);
            }
            return stream.readAllBytes();
        }
    }

    /**
     * Writes through a temporary file so a second commander instance unpacking
     * the same script concurrently never sees a half-written one.
     */
    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException e) {
                log.debug("Could not delete {}: {}", temporary, e.getMessage());
            }
        }
    }
}
