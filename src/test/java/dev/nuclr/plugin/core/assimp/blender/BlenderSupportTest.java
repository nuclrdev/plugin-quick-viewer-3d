package dev.nuclr.plugin.core.assimp.blender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.nuclr.platform.plugin.NuclrResource;

/** Unit coverage for the pure logic around the Blender bridge. */
class BlenderSupportTest {

    // ── Format routing ────────────────────────────────────────────────────────

    @Test
    @DisplayName("every USD spelling maps to the one importer mode")
    void usdVariantsShareAMode() {
        assertEquals("usd", BlenderConverter.modeFor("usd"));
        assertEquals("usd", BlenderConverter.modeFor("usda"));
        assertEquals("usd", BlenderConverter.modeFor("usdc"));
        assertEquals("usd", BlenderConverter.modeFor("USDZ"));
        assertEquals("blend", BlenderConverter.modeFor("BLEND"));
        assertEquals("abc", BlenderConverter.modeFor("abc"));
    }

    @Test
    @DisplayName("formats Assimp already reads are not routed through Blender")
    void assimpFormatsAreNotRouted() {
        assertNull(BlenderConverter.modeFor("fbx"));
        assertNull(BlenderConverter.modeFor("obj"));
        assertNull(BlenderConverter.modeFor("glb"));
        assertNull(BlenderConverter.modeFor(null));

        assertTrue(BlenderBridge.handles("blend"));
        assertTrue(BlenderBridge.handles(".BLEND".substring(1)));
        assertTrue(!BlenderBridge.handles("fbx"));
        assertTrue(!BlenderBridge.handles(null));
    }

    @Test
    @DisplayName("every advertised extension has a converter mode")
    void advertisedExtensionsAreAllConvertible() {
        for (String extension : BlenderBridge.extensions()) {
            assertNotEquals(null, BlenderConverter.modeFor(extension),
                    "no mode for advertised extension " + extension);
        }
    }

    // ── Version parsing ───────────────────────────────────────────────────────

    @Test
    @DisplayName("the version banner is read from real Blender output")
    void parsesVersionBanner() {
        String banner = """
                Blender 4.4.3
                \tbuild date: 2025-04-29
                \tbuild time: 15:39:58
                """;
        Optional<BlenderLocator.Version> version = BlenderLocator.parseVersion(banner);
        assertTrue(version.isPresent());
        assertEquals(4, version.get().major());
        assertEquals(4, version.get().minor());
        assertEquals(3, version.get().patch());
        assertEquals("4.4.3", version.get().text());
    }

    @Test
    @DisplayName("a two-part version still parses, with patch zero")
    void parsesTwoPartVersion() {
        Optional<BlenderLocator.Version> version = BlenderLocator.parseVersion("Blender 3.6");
        assertTrue(version.isPresent());
        assertEquals("3.6.0", version.get().text());
    }

    @Test
    @DisplayName("output from something that is not Blender is rejected")
    void rejectsForeignOutput() {
        assertTrue(BlenderLocator.parseVersion("bash: blender: command not found").isEmpty());
        assertTrue(BlenderLocator.parseVersion("").isEmpty());
        assertTrue(BlenderLocator.parseVersion(null).isEmpty());
    }

    // ── Error extraction ──────────────────────────────────────────────────────

    @Test
    @DisplayName("the script's own error line is picked out of the noise")
    void extractsMarkedError() {
        String output = """
                Blender 4.4.3 (hash 802179c51ccc)
                Read blend: "C:\\models\\broken.blend"
                NUCLR_EXPORT_ERROR: the scene contains no exportable geometry
                Blender quit
                """;
        assertEquals("the scene contains no exportable geometry",
                BlenderConverter.extractError(output, 1));
    }

    @Test
    @DisplayName("without a marker the exit code is reported instead")
    void fallsBackToExitCode() {
        assertEquals("Blender exited with code 137.",
                BlenderConverter.extractError("killed by the OOM killer", 137));
        assertEquals("Blender exited with code -1.", BlenderConverter.extractError(null, -1));
    }

    @Test
    @DisplayName("a long error is truncated so it fits the sidebar")
    void truncatesLongErrors() {
        String message = BlenderConverter.extractError(
                "NUCLR_EXPORT_ERROR: " + "x".repeat(2_000), 1);
        assertTrue(message.length() < 500, "message was " + message.length() + " chars");
        assertTrue(message.endsWith("…"));
    }

    // ── Cache keys ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the same file under the same Blender reuses one cache entry")
    void keyIsStable() {
        NuclrResource item = resource("/models/city.blend", 4096);
        assertEquals(GlbCache.keyFor(item, "4.4.3", "abc123"),
                     GlbCache.keyFor(item, "4.4.3", "abc123"));
    }

    @Test
    @DisplayName("anything that changes the output changes the key")
    void keyTracksEveryInput() {
        NuclrResource item = resource("/models/city.blend", 4096);
        String baseline = GlbCache.keyFor(item, "4.4.3", "abc123");

        assertNotEquals(baseline, GlbCache.keyFor(resource("/models/other.blend", 4096), "4.4.3", "abc123"),
                "a different path must not share an entry");
        assertNotEquals(baseline, GlbCache.keyFor(resource("/models/city.blend", 8192), "4.4.3", "abc123"),
                "an edited file of a different size must not share an entry");
        assertNotEquals(baseline, GlbCache.keyFor(item, "4.5.0", "abc123"),
                "a Blender upgrade must retire old entries");
        assertNotEquals(baseline, GlbCache.keyFor(item, "4.4.3", "def456"),
                "a changed converter script must retire old entries");

        NuclrResource touched = resource("/models/city.blend", 4096);
        touched.setLastModifiedDateTime(LocalDateTime.of(2026, 3, 1, 12, 0));
        assertNotEquals(baseline, GlbCache.keyFor(touched, "4.4.3", "abc123"),
                "a newer modification time must not share an entry");
    }

    @Test
    @DisplayName("keys are filename-safe")
    void keysAreFilenameSafe() {
        String key = GlbCache.keyFor(resource("/models/awkward name (1).blend", 10), "4.4.3", "abc");
        assertTrue(key.matches("[0-9a-f]{32}"), "unexpected key: " + key);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** A resource with no local path, so key computation touches no filesystem. */
    private static NuclrResource resource(String fullPath, long length) {
        NuclrResource item = new NuclrResource((Path) null) {
        };
        item.setUuid(UUID.randomUUID().toString());
        item.setName(fullPath.substring(fullPath.lastIndexOf('/') + 1));
        item.setFullPath(fullPath);
        item.setLength(length);
        return item;
    }
}
