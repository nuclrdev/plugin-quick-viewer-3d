package dev.nuclr.plugin.core.assimp;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import org.apache.commons.io.FilenameUtils;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import dev.nuclr.plugin.core.assimp.blender.BlenderBridge;
import lombok.extern.slf4j.Slf4j;

/**
 * Quick-view provider for 3D model files via LWJGL Assimp bindings.
 *
 * <p>
 * Supported formats: FBX, OBJ, glTF/GLB, Collada (DAE), 3DS, PLY, STL. The
 * panel is created lazily and reused across files.
 *
 * <p>
 * Blender fills in the formats Assimp cannot read — {@code .blend}, USD and
 * Alembic — by converting them to glTF on the way in. That only applies when
 * Blender is installed; see {@link BlenderBridge}.
 *
 * <h3>Settings</h3>
 * <ul>
 * <li>{@code blenderExecutable} — path to a Blender binary, when auto-detection
 * does not find the one you want</li>
 * <li>{@code blenderTimeoutSeconds} — conversion timeout, default 180</li>
 * </ul>
 */
@Slf4j
public class AssimpModelQuickViewProvider implements QuickViewNuclrPlugin {

	private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("fbx", "obj", "gltf", "glb", "dae", "3ds", "ply",
			"stl");

	private static final String SETTINGS_NAMESPACE = "dev.nuclr.plugin.core.quickviewer.3d";

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
		BlenderBridge.configure(settingText("blenderExecutable"), settingInt("blenderTimeoutSeconds", 0));
		// Detection runs a process, so it starts here rather than on the first
		// selection — supports() is called on the EDT and must not wait for it.
		BlenderBridge.probeAsync();
	}

	private String settingText(String key) {
		return settingValue(key) instanceof String text && !text.isBlank() ? text.strip() : null;
	}

	private int settingInt(String key, int fallback) {
		Object value = settingValue(key);
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value instanceof String text) {
			try {
				return Integer.parseInt(text.strip());
			} catch (NumberFormatException e) {
				log.debug("Setting '{}' is not a number: {}", key, text);
			}
		}
		return fallback;
	}

	/** Settings come from a store this plugin does not own; never fail a load over one. */
	private Object settingValue(String key) {
		try {
			return context != null && context.getSettings() != null
					? context.getSettings().get(SETTINGS_NAMESPACE, key)
					: null;
		} catch (Exception e) {
			log.debug("Could not read the '{}' setting: {}", key, e.getMessage());
			return null;
		}
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
		extension = extension.toLowerCase(Locale.ROOT);
		if (SUPPORTED_EXTENSIONS.contains(extension))
			return true;
		// A Blender format is claimed unless detection has already come back
		// empty. While the probe is still running the answer is UNKNOWN, and
		// claiming is the better guess: the probe starts at init and settles in
		// well under a second, and a load that does find Blender missing says so
		// far more usefully than another viewer showing the file as bytes.
		return BlenderBridge.handles(extension)
				&& BlenderBridge.availability() != BlenderBridge.Availability.UNAVAILABLE;
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
