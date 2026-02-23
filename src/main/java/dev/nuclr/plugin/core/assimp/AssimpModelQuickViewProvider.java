package dev.nuclr.plugin.core.assimp;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import dev.nuclr.plugin.QuickViewItem;
import dev.nuclr.plugin.QuickViewProvider;
import lombok.extern.slf4j.Slf4j;

/**
 * Quick-view provider for 3D model files via LWJGL Assimp bindings.
 *
 * <p>Supported formats: FBX, OBJ, glTF/GLB, Collada (DAE), 3DS, PLY, STL.
 * The panel is created lazily and reused across files.
 */
@Slf4j
public class AssimpModelQuickViewProvider implements QuickViewProvider {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "fbx", "obj", "gltf", "glb", "dae", "3ds", "ply", "stl"
    );

    private AssimpModelPanel    panel;
    private volatile AtomicBoolean currentCancelled;

    // ── QuickViewProvider ─────────────────────────────────────────────────────

    @Override
    public String getPluginClass() {
        return getClass().getName();
    }

    @Override
    public boolean matches(QuickViewItem item) {
        String ext = item.extension();
        return ext != null && SUPPORTED_EXTENSIONS.contains(ext.toLowerCase());
    }

    @Override
    public JComponent getPanel() {
        if (panel == null) {
            panel = new AssimpModelPanel();
        }
        return panel;
    }

    @Override
    public boolean open(QuickViewItem item, AtomicBoolean cancelled) {
        if (currentCancelled != null) {
            currentCancelled.set(true); // cancel previous load
        }
        currentCancelled = cancelled;
        getPanel(); // ensure panel exists
        return panel.load(item, cancelled);
    }

    @Override
    public void close() {
        if (currentCancelled != null) {
            currentCancelled.set(true);
        }
        if (panel != null) {
            panel.clear();
        }
    }

    @Override
    public void unload() {
        close();
        panel = null;
    }

    @Override
    public int priority() {
        return 1;
    }
}
