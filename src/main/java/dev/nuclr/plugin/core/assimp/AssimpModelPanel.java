package dev.nuclr.plugin.core.assimp;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrResourcePath;
import dev.nuclr.plugin.core.assimp.gl.ModelViewportCanvas;
import dev.nuclr.plugin.core.assimp.model.ModelData;
import lombok.extern.slf4j.Slf4j;

/**
 * Main panel for the 3D quick-viewer plugin.
 *
 * <h3>Layout</h3>
 * <pre>
 * ┌───────────────────────────────────┬──────────────────────┐
 * │                                   │  filename (bold)     │
 * │   ModelViewportCanvas             │  status label        │
 * │   (interactive 3D viewport)       ├──────────────────────┤
 * │                                   │  metadata text area  │
 * │                                   │  (scrollable)        │
 * ├───────────────────────────────────┴──────────────────────┤
 * │  status bar  (Loading… / Ready / error)                  │
 * └──────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Threading</h3>
 * <ul>
 *   <li>Construction and all UI updates happen on the EDT.</li>
 *   <li>Assimp parsing runs on a virtual thread.</li>
 *   <li>GL upload happens on the EDT inside {@code ModelViewportCanvas.paintGL()}.</li>
 * </ul>
 *
 * <h3>Keyboard shortcuts (viewport must have focus — click it first)</h3>
 * {@code W} wireframe · {@code G} grid · {@code X} axes · {@code L} lit/unlit
 * · {@code B} bounding box · {@code F} frame · {@code R} reset camera
 */
@Slf4j
public class AssimpModelPanel extends JPanel {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final int SIDEBAR_WIDTH = 280;

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                             .withZone(ZoneId.systemDefault());

    // ── Native availability (one-time check, shared) ───────────────────────────

    private static volatile Boolean assimpAvailable;

    private static synchronized boolean checkAssimp() {
        if (assimpAvailable == null) {
            try {
                int maj = org.lwjgl.assimp.Assimp.aiGetVersionMajor();
                int min = org.lwjgl.assimp.Assimp.aiGetVersionMinor();
                int pat = org.lwjgl.assimp.Assimp.aiGetVersionRevision();
                log.info("Assimp {}.{}.{} native library available", maj, min, pat);
                assimpAvailable = Boolean.TRUE;
            } catch (UnsatisfiedLinkError e) {
                log.warn("Assimp native library unavailable: {}", e.getMessage());
                assimpAvailable = Boolean.FALSE;
            }
        }
        return assimpAvailable;
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** Incremented on every load; stale background callbacks must be dropped. */
    private final AtomicLong generation = new AtomicLong(0);

    // ── UI components ─────────────────────────────────────────────────────────

    private final JLabel    nameLabel;
    private final JLabel    viewportStatusLabel;  // inside sidebar
    private final JTextArea statsArea;
    private final JLabel    statusBar;            // bottom status

    /** The live GL canvas; null when GL failed or not yet constructed. */
    private ModelViewportCanvas viewport;

    /** Substituted when GL init fails — shows the error and metadata. */
    private JLabel glFallbackLabel;

    /** The centre panel (BorderLayout CENTER) — swapped between viewport / loading label. */
    private final JPanel centreHolder;
    private final JLabel placeholderLabel;

    // ── Constructor ───────────────────────────────────────────────────────────

    public AssimpModelPanel() {
        setLayout(new BorderLayout(0, 0));

        // ── Sidebar ───────────────────────────────────────────────────────────
        nameLabel = new JLabel(" ");
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));
        nameLabel.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));

        viewportStatusLabel = new JLabel("Ready");
        viewportStatusLabel.setFont(viewportStatusLabel.getFont().deriveFont(11f));
        viewportStatusLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 6, 8));

        statsArea = new JTextArea("No file selected.");
        statsArea.setEditable(false);
        statsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        statsArea.setOpaque(false);
        statsArea.setLineWrap(false);
        statsArea.setFocusable(false);

        JScrollPane statsScroll = new JScrollPane(statsArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        statsScroll.setBorder(null);
        statsScroll.setOpaque(false);
        statsScroll.getViewport().setOpaque(false);

        // Keyboard-hints label at the bottom of the sidebar
        JLabel hintsLabel = new JLabel(
                "<html><font color='#888888' size='2'>"
                + "W wireframe &nbsp;G grid &nbsp;X axes<br>"
                + "L lit &nbsp;B bbox &nbsp;F frame &nbsp;R reset<br>"
                + "LMB rotate &nbsp;Shift+LMB / MMB pan<br>"
                + "Scroll wheel zoom</font></html>");
        hintsLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
        hintsLabel.setFocusable(false);

        JPanel sidebar = new JPanel(new BorderLayout(0, 0));
        sidebar.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(60, 60, 60)));

        JPanel sidebarHeader = new JPanel(new BorderLayout());
        sidebarHeader.setOpaque(false);
        sidebarHeader.add(nameLabel, BorderLayout.NORTH);
        sidebarHeader.add(viewportStatusLabel, BorderLayout.SOUTH);

        sidebar.add(sidebarHeader,  BorderLayout.NORTH);
        sidebar.add(statsScroll,    BorderLayout.CENTER);
        sidebar.add(hintsLabel,     BorderLayout.SOUTH);

        add(sidebar, BorderLayout.EAST);

        // ── Status bar ────────────────────────────────────────────────────────
        statusBar = new JLabel(" ");
        statusBar.setFont(statusBar.getFont().deriveFont(11f));
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 60)),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        add(statusBar, BorderLayout.SOUTH);

        // ── Centre holder ─────────────────────────────────────────────────────
        centreHolder = new JPanel(new BorderLayout());
        centreHolder.setBackground(new Color(33, 33, 35));

        placeholderLabel = new JLabel("No file selected.",
                SwingConstants.CENTER);
        placeholderLabel.setForeground(new Color(160, 160, 160));
        centreHolder.add(placeholderLabel, BorderLayout.CENTER);

        add(centreHolder, BorderLayout.CENTER);

        // ── Create the GL viewport (deferred until first use) ─────────────────
        initViewport();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts an async load for {@code item}.
     * Returns {@code true} immediately; the panel updates itself via
     * {@code SwingUtilities.invokeLater} when parsing finishes.
     */
    public boolean load(NuclrResourcePath item, AtomicBoolean cancelled) {
        final long gen = generation.incrementAndGet();

        SwingUtilities.invokeLater(() -> {
            ensureViewport();
            nameLabel.setText(item.getName());
            viewportStatusLabel.setText("Loading\u2026");
            statusBar.setText("Parsing model\u2026");
            statsArea.setText("Parsing model data\u2026");
        });

        Thread.ofVirtual()
              .name("assimp-load-" + item.getName())
              .start(() -> {
                  ModelData data;
                  try {
                      if (!checkAssimp()) {
                          ModelStats s = new ModelStats();
                          s.getWarnings().add("Assimp native library is not available.");
                          data = new ModelData("Assimp unavailable.", s);
                      } else {
                          data = AssimpModelReader.read(item, cancelled);
                      }
                  } catch (Exception e) {
                      log.warn("Unexpected error parsing '{}'", item.getName(), e);
                      ModelStats s = new ModelStats();
                      s.getWarnings().add("Unexpected error: " + e.getMessage());
                      data = new ModelData(e.getMessage(), s);
                  }

                  final ModelData finalData = data;
                  if (!cancelled.get() && generation.get() == gen) {
                      SwingUtilities.invokeLater(() -> {
                          if (!cancelled.get() && generation.get() == gen) {
                              displayResult(item, finalData);
                          }
                      });
                  }
              });

        return true;
    }

    /** Clears the panel and cancels any in-flight load. */
    public void clear() {
        generation.incrementAndGet();
        SwingUtilities.invokeLater(() -> {
            nameLabel.setText(" ");
            viewportStatusLabel.setText("Ready");
            statusBar.setText(" ");
            statsArea.setText("No file selected.");
            if (viewport != null) viewport.setModelData(null);
        });
    }

    /** Releases GL resources.  Must be called on the EDT. */
    public void disposeViewport() {
        if (viewport != null) {
            viewport.dispose();
        }
    }

    public void closePreview() {
        generation.incrementAndGet();
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::closePreview);
            return;
        }

        if (viewport != null) {
            viewport.setModelData(null);
            viewport.dispose();
            centreHolder.remove(viewport);
            viewport = null;
        } else {
            centreHolder.removeAll();
        }
        glFallbackLabel = null;
        showPlaceholder();
        centreHolder.revalidate();
        centreHolder.repaint();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void initViewport() {
        try {
            viewport = new ModelViewportCanvas(this::onGlError);
            centreHolder.removeAll();
            centreHolder.add(viewport, BorderLayout.CENTER);
            centreHolder.revalidate();
            centreHolder.repaint();
        } catch (UnsatisfiedLinkError | Exception e) {
            onGlError("3D preview unavailable (OpenGL init failed): " + e.getMessage());
        }
    }

    private void ensureViewport() {
        if (viewport == null) {
            initViewport();
        }
    }

    /** Called on the EDT when the GL canvas reports an error. */
    private void onGlError(String message) {
        log.warn("ModelViewportCanvas error: {}", message);
        viewport = null;

        glFallbackLabel = new JLabel(
                "<html><center><font color='#E07070'>"
                + "3D preview unavailable<br><br>"
                + "<font color='#AAAAAA'><small>" + escapeHtml(message) + "</small></font>"
                + "</font></center></html>",
                SwingConstants.CENTER);

        centreHolder.removeAll();
        centreHolder.add(glFallbackLabel, BorderLayout.CENTER);
        centreHolder.revalidate();
        centreHolder.repaint();

        statusBar.setText("OpenGL unavailable — metadata only.");
    }

    private void showPlaceholder() {
        centreHolder.removeAll();
        centreHolder.add(placeholderLabel, BorderLayout.CENTER);
    }

    private void displayResult(NuclrResourcePath item, ModelData data) {
        boolean ok = !data.hasError();

        viewportStatusLabel.setText(ok ? "Ready" : "Failed");
        statusBar.setText(ok
                ? (data.meshes.isEmpty() ? "Metadata only (model exceeds size limit)." : "Ready")
                : "Error: " + data.error);

        statsArea.setText(formatStats(item, data.stats));
        statsArea.setCaretPosition(0);

        if (viewport != null) {
            viewport.setModelData(data);
        }
    }

    // ── Stats formatting ──────────────────────────────────────────────────────

    private static String formatStats(NuclrResourcePath item, ModelStats stats) {
        NumberFormat nf  = NumberFormat.getIntegerInstance();
        StringBuilder sb = new StringBuilder(512);
        String sep = "\u2500".repeat(32) + "\n";

        sb.append("Model Statistics\n").append(sep);
        row(sb, "Meshes",    nf.format(stats.getMeshCount()));
        row(sb, "Vertices",  nf.format(stats.getTotalVertices()));
        row(sb, "Faces",     nf.format(stats.getTotalFaces()));
        row(sb, "Materials", nf.format(stats.getMaterialCount()));
        row(sb, "File Size", formatSize(item.getSizeBytes()));

        Path p = item.getPath();
        if (p != null) {
            try {
                FileTime ft = Files.getLastModifiedTime(p);
                row(sb, "Modified", TS_FMT.format(ft.toInstant()));
            } catch (Exception ignored) { /* best-effort */ }
        }

        if (stats.isHasBoundingBox()) {
            sb.append("\nBounding Box\n").append(sep);
            sb.append(String.format("  Min  %9.3f %9.3f %9.3f%n",
                    stats.getMinX(), stats.getMinY(), stats.getMinZ()));
            sb.append(String.format("  Max  %9.3f %9.3f %9.3f%n",
                    stats.getMaxX(), stats.getMaxY(), stats.getMaxZ()));
            sb.append(String.format("  Size %9.3f %9.3f %9.3f%n",
                    stats.getMaxX() - stats.getMinX(),
                    stats.getMaxY() - stats.getMinY(),
                    stats.getMaxZ() - stats.getMinZ()));
            sb.append(String.format("  Radius  %.4f%n", stats.getBoundingRadius()));
        }

        List<String> textures = stats.getTextures();
        sb.append("\nTextures (").append(textures.size()).append(")\n").append(sep);
        if (textures.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (String t : textures) sb.append("  ").append(t).append('\n');
        }

        List<String> warnings = stats.getWarnings();
        if (!warnings.isEmpty()) {
            sb.append("\nWarnings\n").append(sep);
            for (String w : warnings) sb.append("  \u26A0 ").append(w).append('\n');
        }

        return sb.toString();
    }

    private static void row(StringBuilder sb, String key, String val) {
        sb.append(String.format("%-12s  %s%n", key + ":", val));
    }

    private static String formatSize(long bytes) {
        if (bytes < 0)              return "unknown";
        if (bytes < 1_024)          return bytes + " B";
        if (bytes < 1_048_576)      return String.format("%.1f KB", bytes / 1_024.0);
        if (bytes < 1_073_741_824L) return String.format("%.2f MB", bytes / 1_048_576.0);
        return                             String.format("%.2f GB", bytes / 1_073_741_824.0);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
