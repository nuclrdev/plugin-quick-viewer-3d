package dev.nuclr.plugin.core.assimp.blender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.assimp.AssimpModelReader;
import dev.nuclr.plugin.core.assimp.model.ModelData;

/**
 * Drives a real Blender through the whole conversion path.
 *
 * <p>Skipped entirely when no Blender is installed, which is also the state most
 * CI machines are in — the plugin is expected to work without one.
 */
class BlenderConversionTest {

    private static BlenderInstall install;

    @TempDir
    static Path fixtures;

    @BeforeAll
    static void findBlender() {
        install = BlenderLocator.locate().orElse(null);
        assumeTrue(install != null, "no Blender installed; skipping conversion tests");
    }

    // ── The conversion itself ─────────────────────────────────────────────────

    @Test
    @DisplayName("a .blend converts to a non-empty GLB and is cached on the second look")
    void convertsAndCaches() throws Exception {
        Path source = fixture("cube.blend");
        NuclrResource item = resource(source);

        BlenderBridge.Conversion first = BlenderBridge.convert(item, source, "blend", new AtomicBoolean());
        assertFalse(first.cached(), "the first conversion cannot be a cache hit");
        assertTrue(Files.size(first.glb()) > 0, "conversion produced an empty file");
        assertEquals(install.version(), first.blenderVersion());
        assertTrue(isGlb(first.glb()), "output is not a binary glTF");

        BlenderBridge.Conversion second = BlenderBridge.convert(item, source, "blend", new AtomicBoolean());
        assertTrue(second.cached(), "the second conversion should have come from the cache");
        assertEquals(first.glb(), second.glb());
    }

    @Test
    @DisplayName("an edited file is converted again rather than served from the cache")
    void staleEntriesAreNotReused() throws Exception {
        Path source = fixture("edited.blend");
        NuclrResource item = resource(source);

        Path before = BlenderBridge.convert(item, source, "blend", new AtomicBoolean()).glb();

        // Same bytes, later timestamp: the key must move even though the content did not.
        Files.setLastModifiedTime(source,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000));

        BlenderBridge.Conversion after = BlenderBridge.convert(item, source, "blend", new AtomicBoolean());
        assertFalse(after.cached(), "a touched file must not hit the cache");
        assertTrue(!before.equals(after.glb()), "a touched file must land under a new key");
    }

    @Test
    @DisplayName("the reader turns a .blend into renderable mesh data")
    void readerProducesMeshes() throws Exception {
        Path source = fixture("readable.blend");
        List<String> progress = new ArrayList<>();

        ModelData data = AssimpModelReader.read(resource(source), new AtomicBoolean(), progress::add);

        assertNull(data.error, "import failed: " + data.error);
        assertFalse(data.meshes.isEmpty(), "no meshes came back");
        assertTrue(data.stats.getTotalVertices() > 0, "no vertices came back");
        assertTrue(data.boundingRadius > 0, "no bounding sphere was computed");

        assertNotNull(data.stats.getSourceNote(), "the conversion was not recorded in the stats");
        assertTrue(data.stats.getSourceNote().contains("Blender " + install.version()),
                "unexpected source note: " + data.stats.getSourceNote());
        assertTrue(progress.stream().anyMatch(message -> message.contains("Blender")),
                "the conversion reported no progress: " + progress);
    }

    @Test
    @DisplayName("a file Blender cannot open fails with Blender's own explanation")
    void reportsBlenderErrors() throws Exception {
        Path source = fixtures.resolve("garbage.blend");
        Files.writeString(source, "this is not a blend file");

        BlenderException failure = assertThrows(BlenderException.class,
                () -> BlenderBridge.convert(resource(source), source, "blend", new AtomicBoolean()));

        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().toLowerCase().contains("format")
                        || failure.getMessage().toLowerCase().contains("read"),
                "unhelpful message: " + failure.getMessage());
    }

    @Test
    @DisplayName("a cancelled load stops promptly instead of running to completion")
    void cancellationIsHonoured() throws Exception {
        Path source = fixture("cancelled.blend");

        AtomicBoolean cancelled = new AtomicBoolean(true);
        long startedAt = System.nanoTime();

        BlenderException failure = assertThrows(BlenderException.class,
                () -> BlenderBridge.convert(resource(source), source, "blend", cancelled));

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertTrue(failure.getMessage().toLowerCase().contains("cancel"),
                "expected a cancellation message, got: " + failure.getMessage());
        assertTrue(elapsedMs < 10_000, "cancellation took " + elapsedMs + " ms");
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** Builds a small .blend with Blender itself, so no binary lives in the repo. */
    private static Path fixture(String name) throws IOException, InterruptedException {
        Path target = fixtures.resolve(name);
        if (Files.exists(target)) {
            return target;
        }
        String script = "import bpy; bpy.ops.wm.read_factory_settings(use_empty=True); "
                + "bpy.ops.mesh.primitive_cube_add(); "
                + "bpy.ops.wm.save_as_mainfile(filepath=r'" + target.toAbsolutePath() + "')";

        Process process = new ProcessBuilder(List.of(
                install.executable().toString(),
                "--background", "--factory-startup", "--disable-autoexec",
                "--python-expr", script))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();

        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "fixture generation timed out");
        assertTrue(Files.isRegularFile(target), "Blender did not write the fixture " + name);
        return target;
    }

    /** The first four bytes of a binary glTF are the magic "glTF". */
    private static boolean isGlb(Path path) throws IOException {
        byte[] magic = new byte[4];
        try (var stream = Files.newInputStream(path)) {
            return stream.read(magic) == 4 && new String(magic, java.nio.charset.StandardCharsets.US_ASCII).equals("glTF");
        }
    }

    private static NuclrResource resource(Path path) throws IOException {
        NuclrResource item = new NuclrResource(path) {
        };
        item.setUuid(UUID.randomUUID().toString());
        item.setName(path.getFileName().toString());
        item.setFullPath(path.toAbsolutePath().toString());
        item.setLength(Files.size(path));
        item.setLastModifiedDateTime(LocalDateTime.now());
        return item;
    }
}
