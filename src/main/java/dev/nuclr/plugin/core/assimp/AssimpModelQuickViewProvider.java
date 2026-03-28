package dev.nuclr.plugin.core.assimp;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nuclr.plugin.ApplicationPluginContext;
import dev.nuclr.plugin.MenuResource;
import dev.nuclr.plugin.PluginManifest;
import dev.nuclr.plugin.PluginPathResource;
import dev.nuclr.plugin.QuickViewProviderPlugin;
import lombok.extern.slf4j.Slf4j;

/**
 * Quick-view provider for 3D model files via LWJGL Assimp bindings.
 *
 * <p>Supported formats: FBX, OBJ, glTF/GLB, Collada (DAE), 3DS, PLY, STL.
 * The panel is created lazily and reused across files.
 */
@Slf4j
public class AssimpModelQuickViewProvider implements QuickViewProviderPlugin {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "fbx", "obj", "gltf", "glb", "dae", "3ds", "ply", "stl"
    );

    private ApplicationPluginContext context;
    private AssimpModelPanel         panel;
    private volatile AtomicBoolean   currentCancelled;

    // ── BasePlugin ────────────────────────────────────────────────────────────

    @Override
    public PluginManifest getPluginInfo() {
        ObjectMapper om = context != null ? context.getObjectMapper() : new ObjectMapper();
        try (InputStream is = getClass().getResourceAsStream("/plugin.json")) {
            if (is != null) return om.readValue(is, PluginManifest.class);
        } catch (Exception e) {
            log.error("Error reading /plugin.json", e);
        }
        return null;
    }

    @Override
    public JComponent getPanel() {
        if (panel == null) panel = new AssimpModelPanel();
        return panel;
    }

    @Override
    public List<MenuResource> getMenuItems(PluginPathResource source) {
        return List.of();
    }

    @Override
    public void load(ApplicationPluginContext context) {
        this.context = context;
    }

    @Override
    public void unload() {
        closeItem();
        if (panel != null) {
            // Dispose GL resources before the panel is dropped.
            // Must be called on the EDT — the plugin framework guarantees this.
            panel.disposeViewport();
            panel = null;
        }
        context = null;
    }

    // ── QuickViewProviderPlugin ───────────────────────────────────────────────

    @Override
    public boolean supports(PluginPathResource resource) {
        if (resource == null || resource.getExtension() == null) return false;
        return SUPPORTED_EXTENSIONS.contains(resource.getExtension().toLowerCase());
    }

    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public boolean openItem(PluginPathResource resource, AtomicBoolean cancelled) {
        if (currentCancelled != null) currentCancelled.set(true);
        currentCancelled = cancelled;
        getPanel(); // ensure panel exists
        return panel.load(resource, cancelled);
    }

    @Override
    public void closeItem() {
        if (currentCancelled != null) {
            currentCancelled.set(true);
            currentCancelled = null;
        }
        if (panel != null) panel.closePreview();
    }

    // ── FocusablePlugin ───────────────────────────────────────────────────────

    @Override
    public void onFocusGained() {
    }

    @Override
    public void onFocusLost() {
    }
}
