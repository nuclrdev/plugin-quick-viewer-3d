package dev.nuclr.plugin.core.assimp;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import org.apache.commons.io.FilenameUtils;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import lombok.extern.slf4j.Slf4j;

/**
 * Quick-view provider for 3D model files via LWJGL Assimp bindings.
 *
 * <p>
 * Supported formats: FBX, OBJ, glTF/GLB, Collada (DAE), 3DS, PLY, STL. The
 * panel is created lazily and reused across files.
 */
@Slf4j
public class AssimpModelQuickViewProvider implements QuickViewNuclrPlugin {

	private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("fbx", "obj", "gltf", "glb", "dae", "3ds", "ply",
			"stl");

	private NuclrPluginContext context;
	private AssimpModelPanel panel;
	private volatile AtomicBoolean currentCancelled;
	private String uuid = java.util.UUID.randomUUID().toString();

	@Override
	public JComponent panel() {
		if (panel == null)
			panel = new AssimpModelPanel();
		return panel;
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
	}

	@Override
	public void init() {
	}

	@Override
	public NuclrPluginContext getContext() {
		return this.context;
	}

	@Override
	public void unload() {
		closeResource();
		if (panel != null) {
			// Dispose GL resources before the panel is dropped.
			// Must be called on the EDT â€” the plugin framework guarantees this.
			panel.disposeViewport();
			panel = null;
		}
		context = null;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		String extension = extension(resource);
		if (extension == null)
			return false;
		return SUPPORTED_EXTENSIONS.contains(extension.toLowerCase());
	}

	private static String extension(NuclrResource resource) {
		if (resource == null || resource.getName() == null) {
			return null;
		}
		String name = resource.getName();
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return null;
		}
		return name.substring(dot + 1);
	}
	
	private static String extension(Path path) {
		var name = path.getFileName() != null ? path.getFileName().toString() : path.toString();
		return FilenameUtils.getExtension(name);
	}


	@Override
	public boolean openResource(NuclrResource resource, AtomicBoolean cancelled) {
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

	// â”€â”€ FocusablePlugin â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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



	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
	}

	@Override
	public NuclrResource getCurrentResource() {
		return null;
	}

	@Override
	public String uuid() {
		return uuid;
	}
	

}
