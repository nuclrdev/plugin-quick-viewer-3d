package dev.nuclr.plugin.core.assimp;

import java.awt.BorderLayout;
import java.awt.Font;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.Assimp;
import org.lwjgl.system.MemoryStack;

import dev.nuclr.plugin.QuickViewItem;
import lombok.extern.slf4j.Slf4j;

/**
 * Swing panel that shows 3D model statistics extracted via Assimp.
 *
 * <p>All Assimp / IO work runs on a virtual thread. The panel shows a
 * "Loading…" state immediately and is updated via {@code SwingUtilities.invokeLater}
 * once parsing completes.
 *
 * <p>A generation counter prevents stale updates when the user switches files quickly.
 */
@Slf4j
public class AssimpModelPanel extends JPanel {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final long MAX_FILE_BYTES    = 250L * 1024 * 1024; // 250 MB
    private static final int  MAX_MESH_COUNT    = 10_000;

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final int[] TEXTURE_TYPES = {
        Assimp.aiTextureType_DIFFUSE,
        Assimp.aiTextureType_SPECULAR,
        Assimp.aiTextureType_AMBIENT,
        Assimp.aiTextureType_EMISSIVE,
        Assimp.aiTextureType_HEIGHT,
        Assimp.aiTextureType_NORMALS,
        Assimp.aiTextureType_SHININESS,
        Assimp.aiTextureType_DISPLACEMENT,
        Assimp.aiTextureType_LIGHTMAP,
        Assimp.aiTextureType_BASE_COLOR,
        Assimp.aiTextureType_METALNESS,
        Assimp.aiTextureType_DIFFUSE_ROUGHNESS,
        Assimp.aiTextureType_AMBIENT_OCCLUSION,
        Assimp.aiTextureType_UNKNOWN,
    };

    // ── Native availability (lazy, thread-safe) ────────────────────────────────

    private static volatile Boolean nativesAvailable;

    private static synchronized boolean checkNatives() {
        if (nativesAvailable == null) {
            try {
                int major = Assimp.aiGetVersionMajor();
                int minor = Assimp.aiGetVersionMinor();
                int patch = Assimp.aiGetVersionRevision();
                log.info("Assimp native library loaded successfully (version {}.{}.{})", major, minor, patch);
                nativesAvailable = Boolean.TRUE;
            } catch (UnsatisfiedLinkError e) {
                log.warn("Assimp native library not available: {}", e.getMessage());
                nativesAvailable = Boolean.FALSE;
            }
        }
        return nativesAvailable;
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** Incremented on every new load; background tasks must match to update UI. */
    private final AtomicLong generation = new AtomicLong(0);

    // ── UI components ─────────────────────────────────────────────────────────

    private final JLabel   nameLabel;
    private final JLabel   statusLabel;
    private final JTextArea statsArea;

    // ── Constructor ───────────────────────────────────────────────────────────

    public AssimpModelPanel() {
        setLayout(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        nameLabel = new JLabel(" ");
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));

        statusLabel = new JLabel("Ready");
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));

        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        headerPanel.setOpaque(false);
        headerPanel.add(nameLabel);
        headerPanel.add(Box.createVerticalStrut(2));
        headerPanel.add(statusLabel);
        add(headerPanel, BorderLayout.NORTH);

        statsArea = new JTextArea("No file selected.");
        statsArea.setEditable(false);
        statsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        statsArea.setOpaque(false);
        statsArea.setLineWrap(false);

        JScrollPane scroll = new JScrollPane(statsArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        add(scroll, BorderLayout.CENTER);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts an async load for {@code item}.  Returns {@code true} immediately;
     * the panel updates itself on the EDT when parsing finishes.
     */
    public boolean load(QuickViewItem item, AtomicBoolean cancelled) {
        final long gen = generation.incrementAndGet();

        // Show loading state immediately (safe to call from any thread)
        SwingUtilities.invokeLater(() -> {
            nameLabel.setText(item.name());
            statusLabel.setText("Loading\u2026");
            statsArea.setText("Parsing model data\u2026");
        });

        Thread.ofVirtual()
              .name("assimp-load-" + item.name())
              .start(() -> {
                  ModelStats stats;
                  try {
                      stats = parseModel(item, cancelled, gen);
                  } catch (Exception e) {
                      log.warn("Unexpected error parsing model '{}': {}", item.name(), e.getMessage(), e);
                      stats = new ModelStats();
                      stats.getWarnings().add("Parse error: " + e.getMessage());
                  }

                  final ModelStats finalStats = stats;
                  if (!cancelled.get() && generation.get() == gen) {
                      SwingUtilities.invokeLater(() -> {
                          if (!cancelled.get() && generation.get() == gen) {
                              displayStats(item, finalStats);
                          }
                      });
                  }
              });

        return true;
    }

    /** Resets the panel to an empty state and cancels any in-flight load. */
    public void clear() {
        generation.incrementAndGet(); // invalidate pending loads before touching UI
        SwingUtilities.invokeLater(() -> {
            nameLabel.setText(" ");
            statusLabel.setText("Ready");
            statsArea.setText("No file selected.");
        });
    }

    // ── Background parsing ────────────────────────────────────────────────────

    private ModelStats parseModel(QuickViewItem item, AtomicBoolean cancelled, long gen) {
        ModelStats stats = new ModelStats();

        if (!checkNatives()) {
            stats.getWarnings().add("Assimp native library is not available on this platform.");
            return stats;
        }

        Path filePath = item.path();
        if (filePath == null) {
            stats.getWarnings().add(
                "Assimp requires a real file on disk; stream-only items are not supported.");
            return stats;
        }

        long sizeBytes = item.sizeBytes();
        if (sizeBytes > MAX_FILE_BYTES) {
            stats.getWarnings().add(String.format(
                "File too large (%.1f MB); limit is 250 MB. Parsing skipped.",
                sizeBytes / 1_048_576.0));
            return stats;
        }

        int flags = Assimp.aiProcess_Triangulate
                  | Assimp.aiProcess_JoinIdenticalVertices
                  | Assimp.aiProcess_SortByPType;

        AIScene scene = Assimp.aiImportFile(filePath.toAbsolutePath().toString(), flags);
        if (scene == null) {
            String err = Assimp.aiGetErrorString();
            stats.getWarnings().add(
                "Could not import model: " + (err != null && !err.isBlank() ? err : "unknown error"));
            return stats;
        }

        try {
            if (!cancelled.get() && generation.get() == gen) {
                extractStats(scene, stats, cancelled, gen);
            }
        } finally {
            Assimp.aiReleaseImport(scene);
        }

        return stats;
    }

    private void extractStats(AIScene scene, ModelStats stats,
                               AtomicBoolean cancelled, long gen) {
        int meshCount = scene.mNumMeshes();
        stats.setMeshCount(meshCount);
        stats.setMaterialCount(scene.mNumMaterials());

        boolean skipBBox = meshCount > MAX_MESH_COUNT;
        if (skipBBox) {
            stats.getWarnings().add(String.format(
                "Mesh count (%,d) exceeds limit (%,d); bounding box skipped.",
                meshCount, MAX_MESH_COUNT));
        }

        var meshBuf = scene.mMeshes();
        if (meshBuf != null) {
            for (int i = 0; i < meshCount; i++) {
                if (cancelled.get() || generation.get() != gen) return;

                AIMesh mesh = AIMesh.create(meshBuf.get(i));
                stats.setTotalVertices(stats.getTotalVertices() + mesh.mNumVertices());
                stats.setTotalFaces(stats.getTotalFaces() + mesh.mNumFaces());

                if (!skipBBox) {
                    computeBBox(mesh, stats);
                }
            }
            if (!skipBBox && meshCount > 0
                    && stats.getMinX() != Float.MAX_VALUE) {
                stats.setHasBoundingBox(true);
            }
        }

        var matBuf = scene.mMaterials();
        if (matBuf != null) {
            Set<String> seenPaths = new LinkedHashSet<>();
            for (int i = 0; i < scene.mNumMaterials(); i++) {
                if (cancelled.get() || generation.get() != gen) return;
                AIMaterial mat = AIMaterial.create(matBuf.get(i));
                extractTextures(mat, seenPaths);
            }
            stats.getTextures().addAll(seenPaths);
        }
    }

    private static void computeBBox(AIMesh mesh, ModelStats stats) {
        AIVector3D.Buffer verts = mesh.mVertices();
        if (verts == null) return;

        int n = mesh.mNumVertices();
        for (int v = 0; v < n; v++) {
            AIVector3D vtx = verts.get(v);
            float x = vtx.x(), y = vtx.y(), z = vtx.z();
            if (x < stats.getMinX()) stats.setMinX(x);
            if (y < stats.getMinY()) stats.setMinY(y);
            if (z < stats.getMinZ()) stats.setMinZ(z);
            if (x > stats.getMaxX()) stats.setMaxX(x);
            if (y > stats.getMaxY()) stats.setMaxY(y);
            if (z > stats.getMaxZ()) stats.setMaxZ(z);
        }
    }

    private static void extractTextures(AIMaterial mat, Set<String> seenPaths) {
        for (int type : TEXTURE_TYPES) {
            int count = Assimp.aiGetMaterialTextureCount(mat, type);
            for (int j = 0; j < count; j++) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    AIString pathStr = AIString.calloc(stack);
                    int result = Assimp.aiGetMaterialTexture(mat, type, j, pathStr,
                            (IntBuffer) null, null, null, null, null, null);
                    if (result == Assimp.aiReturn_SUCCESS) {
                        String tex = pathStr.dataString();
                        if (tex != null && !tex.isBlank()) {
                            seenPaths.add(tex);
                        }
                    }
                }
            }
        }
    }

    // ── EDT display ───────────────────────────────────────────────────────────

    private void displayStats(QuickViewItem item, ModelStats stats) {
        boolean failed = stats.getMeshCount() == 0 && !stats.getWarnings().isEmpty();
        statusLabel.setText(failed ? "Failed" : "Ready");
        nameLabel.setText(item.name());
        statsArea.setText(formatStats(item, stats));
        statsArea.setCaretPosition(0);
    }

    private static String formatStats(QuickViewItem item, ModelStats stats) {
        NumberFormat nf  = NumberFormat.getIntegerInstance();
        StringBuilder sb = new StringBuilder(512);
        String sep = "\u2500".repeat(40) + "\n"; // ─ × 40

        // ── Model Statistics ─────────────────────────────────────────────────
        sb.append("Model Statistics\n").append(sep);
        appendRow(sb, "Meshes",     nf.format(stats.getMeshCount()));
        appendRow(sb, "Vertices",   nf.format(stats.getTotalVertices()));
        appendRow(sb, "Faces",      nf.format(stats.getTotalFaces()));
        appendRow(sb, "Materials",  nf.format(stats.getMaterialCount()));
        appendRow(sb, "File Size",  formatSize(item.sizeBytes()));

        Path filePath = item.path();
        if (filePath != null) {
            try {
                FileTime ft = Files.getLastModifiedTime(filePath);
                appendRow(sb, "Modified", TS_FMT.format(ft.toInstant()));
            } catch (Exception ignored) { /* best-effort */ }
        }

        // ── Bounding Box ─────────────────────────────────────────────────────
        if (stats.isHasBoundingBox()) {
            sb.append("\nBounding Box\n").append(sep);
            sb.append(String.format("  Min:   %10.4f, %10.4f, %10.4f%n",
                    stats.getMinX(), stats.getMinY(), stats.getMinZ()));
            sb.append(String.format("  Max:   %10.4f, %10.4f, %10.4f%n",
                    stats.getMaxX(), stats.getMaxY(), stats.getMaxZ()));
            sb.append(String.format("  Size:  %10.4f, %10.4f, %10.4f%n",
                    stats.getMaxX() - stats.getMinX(),
                    stats.getMaxY() - stats.getMinY(),
                    stats.getMaxZ() - stats.getMinZ()));
        }

        // ── Textures ─────────────────────────────────────────────────────────
        List<String> textures = stats.getTextures();
        sb.append("\nTextures (").append(textures.size()).append(")\n").append(sep);
        if (textures.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (String tex : textures) {
                sb.append("  ").append(tex).append("\n");
            }
        }

        // ── Warnings ─────────────────────────────────────────────────────────
        List<String> warnings = stats.getWarnings();
        if (!warnings.isEmpty()) {
            sb.append("\nWarnings\n").append(sep);
            for (String w : warnings) {
                sb.append("  \u26A0 ").append(w).append("\n");
            }
        }

        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, String key, String value) {
        sb.append(String.format("%-16s  %s%n", key + ":", value));
    }

    private static String formatSize(long bytes) {
        if (bytes < 0)              return "unknown";
        if (bytes < 1_024)          return bytes + " B";
        if (bytes < 1_048_576)      return String.format("%.1f KB", bytes / 1_024.0);
        if (bytes < 1_073_741_824L) return String.format("%.2f MB", bytes / 1_048_576.0);
        return                             String.format("%.2f GB", bytes / 1_073_741_824.0);
    }
}
