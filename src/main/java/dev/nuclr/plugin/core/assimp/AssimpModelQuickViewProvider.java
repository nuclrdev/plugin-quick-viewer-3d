package dev.nuclr.plugin.core.assimp;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrPluginRole;
import dev.nuclr.platform.plugin.NuclrResourcePath;
import lombok.extern.slf4j.Slf4j;

/**
 * Quick-view provider for 3D model files via LWJGL Assimp bindings.
 *
 * <p>
 * Supported formats: FBX, OBJ, glTF/GLB, Collada (DAE), 3DS, PLY, STL. The
 * panel is created lazily and reused across files.
 */
@Slf4j
public class AssimpModelQuickViewProvider implements NuclrPlugin {

	private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("fbx", "obj", "gltf", "glb", "dae", "3ds", "ply",
			"stl");

	private NuclrPluginContext context;
	private AssimpModelPanel panel;
	private volatile AtomicBoolean currentCancelled;
	private String uuid = java.util.UUID.randomUUID().toString();

	// ── BasePlugin ────────────────────────────────────────────────────────────

	@Override
	public JComponent panel() {
		if (panel == null)
			panel = new AssimpModelPanel();
		return panel;
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResourcePath source) {
		return List.of();
	}

	@Override
	public void load(NuclrPluginContext context, boolean isTemplate) {
		this.context = context;
	}

	@Override
	public void unload() {
		closeResource();
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
	public boolean supports(NuclrResourcePath resource) {
		if (resource == null || resource.getExtension() == null)
			return false;
		return SUPPORTED_EXTENSIONS.contains(resource.getExtension().toLowerCase());
	}

	@Override
	public int priority() {
		return 1;
	}

	@Override
	public boolean openResource(NuclrResourcePath resource, AtomicBoolean cancelled) {
		if (currentCancelled != null)
			currentCancelled.set(true);
		currentCancelled = cancelled;
		panel(); // ensure panel exists
		return panel.load(resource, cancelled);
	}

	@Override
	public void closeResource() {
		if (currentCancelled != null) {
			currentCancelled.set(true);
			currentCancelled = null;
		}
		if (panel != null)
			panel.closePreview();
	}

	// ── FocusablePlugin ───────────────────────────────────────────────────────

	@Override
	public boolean onFocusGained() {
		return false;
	}

	@Override
	public void onFocusLost() {
	}

	@Override
	public boolean isFocused() {
		return false;
	}

	private String name = "3D Model Quick Viewer";
	private String id = "dev.nuclr.plugin.core.quickviewer.3d";
	private String version = "1.0.0";
	private String description = "A quick viewer for 3D model files (FBX, OBJ, glTF/GLB, DAE, 3DS, PLY, STL) — displays mesh count, vertex/face totals, materials, bounding box, and texture references via Assimp.";
	private String author = "Nuclr Development Team";
	private String license = "Apache-2.0";
	private String website = "https://nuclr.dev";
	private String pageUrl = "https://nuclr.dev/plugins/core/3d-quick-viewer.html";
	private String docUrl = "https://nuclr.dev/plugins/core/3d-quick-viewer.html";

	@Override
	public String id() {
		return id;
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String version() {
		return version;
	}

	@Override
	public String description() {
		return description;
	}

	@Override
	public String author() {
		return author;
	}

	@Override
	public String license() {
		return license;
	}

	@Override
	public String website() {
		return website;
	}

	@Override
	public String pageUrl() {
		return pageUrl;
	}

	@Override
	public String docUrl() {
		return docUrl;
	}

	@Override
	public Developer type() {
		return Developer.Official;
	}

	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
	}

	@Override
	public NuclrPluginRole role() {
		return NuclrPluginRole.QuickViewer;
	}

	@Override
	public NuclrResourcePath getCurrentResource() {
		return null;
	}

	@Override
	public String uuid() {
		return uuid;
	}
	
}
