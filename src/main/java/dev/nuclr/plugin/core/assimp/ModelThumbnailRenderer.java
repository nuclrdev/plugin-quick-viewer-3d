package dev.nuclr.plugin.core.assimp;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nuclr.plugin.core.assimp.model.MeshData;
import dev.nuclr.plugin.core.assimp.model.ModelData;
import dev.nuclr.plugin.core.assimp.model.TextureData;

/**
 * Draws a still thumbnail of an imported model on the CPU.
 *
 * <p>The live viewport renders through an OpenGL canvas owned by the panel, but a
 * thumbnail may be asked for on any thread, concurrently, and before the panel
 * exists - so it gets a small z-buffered rasteriser of its own. The view matches
 * the viewport's starting camera (30 degrees of yaw, 25 of pitch) through an
 * orthographic projection, under which plain affine texture interpolation is
 * exact. The background is left transparent.
 */
final class ModelThumbnailRenderer {

	private static final double YAW = Math.toRadians(30.0);
	private static final double PITCH = Math.toRadians(25.0);
	/** Share of each side left clear around the model. */
	private static final double PADDING = 0.05;
	private static final float AMBIENT = 0.35f;
	/** Light direction in view space: from the upper left, towards the model. */
	private static final float[] LIGHT = normalize(-0.4f, 0.6f, 0.7f);
	/** Rendered at twice the size and shrunk, for anti-aliased edges... */
	private static final int SUPERSAMPLE = 2;
	/** ...unless the box is already this many pixels, where it would cost more than it shows. */
	private static final int SUPERSAMPLE_PIXEL_LIMIT = 1024 * 1024;

	private ModelThumbnailRenderer() {
	}

	/**
	 * @return the picture, sized to the model's silhouette within the box, or
	 *         {@code null} when the model has nothing to draw or {@code cancelled} was set
	 */
	static BufferedImage render(ModelData model, int maxWidth, int maxHeight, AtomicBoolean cancelled) {
		if (model == null || model.hasError() || model.meshes.isEmpty() || maxWidth <= 0 || maxHeight <= 0) {
			return null;
		}

		// Camera basis: eye direction as CameraOrbit places it, right and up from world Y.
		float[] eye = { (float) (Math.cos(PITCH) * Math.sin(YAW)), (float) Math.sin(PITCH),
				(float) (Math.cos(PITCH) * Math.cos(YAW)) };
		float[] right = normalize(eye[2], 0, -eye[0]);
		float[] up = cross(eye, right);

		// View-space positions (x right, y up, z towards the eye) and normals, per mesh.
		float[][] positions = new float[model.meshes.size()][];
		float[][] normals = new float[model.meshes.size()][];
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int m = 0; m < model.meshes.size(); m++) {
			MeshData mesh = model.meshes.get(m);
			float[] view = new float[mesh.numVertices * 3];
			for (int v = 0; v < mesh.numVertices; v++) {
				float px = mesh.positions[v * 3] - model.centerX;
				float py = mesh.positions[v * 3 + 1] - model.centerY;
				float pz = mesh.positions[v * 3 + 2] - model.centerZ;
				float x = px * right[0] + py * right[1] + pz * right[2];
				float y = px * up[0] + py * up[1] + pz * up[2];
				view[v * 3] = x;
				view[v * 3 + 1] = y;
				view[v * 3 + 2] = px * eye[0] + py * eye[1] + pz * eye[2];
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minY = Math.min(minY, y);
				maxY = Math.max(maxY, y);
			}
			positions[m] = view;
			normals[m] = rotate(mesh.normals, mesh.numVertices, right, up, eye);
			if (cancelled.get()) {
				return null;
			}
		}
		if (minX > maxX) {
			return null;
		}

		// Size the picture to the silhouette, so a tall model gives a tall thumbnail.
		float floor = Math.max(model.boundingRadius, 1e-6f) * 1e-3f;
		double contentWidth = Math.max(maxX - minX, floor);
		double contentHeight = Math.max(maxY - minY, floor);
		double usable = 1 - 2 * PADDING;
		double fit = Math.min(maxWidth * usable / contentWidth, maxHeight * usable / contentHeight);
		int outWidth = (int) Math.clamp(Math.round(contentWidth * fit / usable), 1, maxWidth);
		int outHeight = (int) Math.clamp(Math.round(contentHeight * fit / usable), 1, maxHeight);
		int ss = (long) outWidth * outHeight <= SUPERSAMPLE_PIXEL_LIMIT ? SUPERSAMPLE : 1;

		Raster raster = new Raster(outWidth * ss, outHeight * ss);
		float scale = (float) (fit * ss);
		float offsetX = (float) ((raster.width - contentWidth * scale) / 2);
		float offsetY = (float) ((raster.height - contentHeight * scale) / 2);

		for (int m = 0; m < model.meshes.size(); m++) {
			MeshData mesh = model.meshes.get(m);
			float[] view = positions[m];
			float[] screen = new float[view.length];
			for (int v = 0; v < mesh.numVertices; v++) {
				screen[v * 3] = (view[v * 3] - minX) * scale + offsetX;
				screen[v * 3 + 1] = (maxY - view[v * 3 + 1]) * scale + offsetY;
				screen[v * 3 + 2] = view[v * 3 + 2] * scale; // scaled like x and y, so face normals stay true
			}
			TextureData texture = mesh.textureIndex >= 0 && mesh.textureIndex < model.textures.size()
					&& mesh.uvs != null ? model.textures.get(mesh.textureIndex) : null;
			if (!raster.drawMesh(mesh, screen, normals[m], texture, cancelled)) {
				return null;
			}
		}
		return ThumbnailScaler.fit(raster.toImage(), outWidth, outHeight);
	}

	private static float[] rotate(float[] normals, int count, float[] right, float[] up, float[] eye) {
		if (normals == null || normals.length < count * 3) {
			return null;
		}
		float[] view = new float[count * 3];
		for (int v = 0; v < count; v++) {
			float nx = normals[v * 3], ny = normals[v * 3 + 1], nz = normals[v * 3 + 2];
			view[v * 3] = nx * right[0] + ny * right[1] + nz * right[2];
			view[v * 3 + 1] = nx * up[0] + ny * up[1] + nz * up[2];
			view[v * 3 + 2] = nx * eye[0] + ny * eye[1] + nz * eye[2];
		}
		return view;
	}

	private static float[] cross(float[] a, float[] b) {
		return normalize(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]);
	}

	private static float[] normalize(float x, float y, float z) {
		float length = (float) Math.sqrt(x * x + y * y + z * z);
		return length == 0 ? new float[] { 0, 0, 1 } : new float[] { x / length, y / length, z / length };
	}

	/** A colour buffer with a depth buffer beside it. */
	private static final class Raster {

		final int width;
		final int height;
		final int[] argb;
		final float[] depth;

		Raster(int width, int height) {
			this.width = width;
			this.height = height;
			this.argb = new int[width * height];
			this.depth = new float[width * height];
			Arrays.fill(depth, -Float.MAX_VALUE);
		}

		/** @return {@code false} if cancelled part-way */
		boolean drawMesh(MeshData mesh, float[] screen, float[] normals, TextureData texture, AtomicBoolean cancelled) {
			int[] idx = mesh.indices;
			for (int f = 0; f + 2 < idx.length; f += 3) {
				if ((f & 0xFFF) == 0 && cancelled.get()) {
					return false;
				}
				int a = idx[f], b = idx[f + 1], c = idx[f + 2];
				if (a >= mesh.numVertices || b >= mesh.numVertices || c >= mesh.numVertices) {
					continue;
				}
				drawTriangle(mesh, screen, normals, texture, a, b, c);
			}
			return true;
		}

		private void drawTriangle(MeshData mesh, float[] s, float[] normals, TextureData texture, int a, int b, int c) {
			float x0 = s[a * 3], y0 = s[a * 3 + 1], z0 = s[a * 3 + 2];
			float x1 = s[b * 3], y1 = s[b * 3 + 1], z1 = s[b * 3 + 2];
			float x2 = s[c * 3], y2 = s[c * 3 + 1], z2 = s[c * 3 + 2];
			float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
			if (Math.abs(area) < 1e-9f) {
				return;
			}
			int left = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
			int right = Math.min(width - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
			int top = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
			int bottom = Math.min(height - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
			if (left > right || top > bottom) {
				return;
			}

			// Without usable vertex normals the face is shaded flat. Screen y points down,
			// a mirror, which turns a cross product taken there into -(x, -y, z) of the
			// view-space one; undo that, then face it towards the eye.
			float[] faceNormal = normalize(
					-((y1 - y0) * (z2 - z0) - (z1 - z0) * (y2 - y0)),
					(z1 - z0) * (x2 - x0) - (x1 - x0) * (z2 - z0),
					-((x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)));
			if (faceNormal[2] < 0) {
				faceNormal[0] = -faceNormal[0];
				faceNormal[1] = -faceNormal[1];
				faceNormal[2] = -faceNormal[2];
			}

			for (int py = top; py <= bottom; py++) {
				float cy = py + 0.5f;
				for (int px = left; px <= right; px++) {
					float cx = px + 0.5f;
					float w0 = ((x2 - x1) * (cy - y1) - (y2 - y1) * (cx - x1)) / area;
					float w1 = ((x0 - x2) * (cy - y2) - (y0 - y2) * (cx - x2)) / area;
					float w2 = 1 - w0 - w1;
					if (w0 < 0 || w1 < 0 || w2 < 0) {
						continue;
					}
					float z = w0 * z0 + w1 * z1 + w2 * z2;
					int at = py * width + px;
					if (z <= depth[at]) {
						continue;
					}
					int base = texture != null ? texel(texture, mesh.uvs, a, b, c, w0, w1, w2) : paletteColour(mesh);
					if (base == 0) {
						continue; // a transparent texel: let what is behind show through
					}
					depth[at] = z;
					argb[at] = shade(base, shading(normals, a, b, c, w0, w1, w2, faceNormal));
				}
			}
		}

		private static float shading(float[] n, int a, int b, int c, float w0, float w1, float w2, float[] face) {
			float nx, ny, nz;
			if (n != null) {
				nx = w0 * n[a * 3] + w1 * n[b * 3] + w2 * n[c * 3];
				ny = w0 * n[a * 3 + 1] + w1 * n[b * 3 + 1] + w2 * n[c * 3 + 1];
				nz = w0 * n[a * 3 + 2] + w1 * n[b * 3 + 2] + w2 * n[c * 3 + 2];
				float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (length < 1e-6f) {
					nx = face[0];
					ny = face[1];
					nz = face[2];
				} else if (nz < 0) {
					// Seen from behind: light the side we are looking at, as a two-sided material would.
					nx = -nx / length;
					ny = -ny / length;
					nz = -nz / length;
				} else {
					nx /= length;
					ny /= length;
					nz /= length;
				}
			} else {
				nx = face[0];
				ny = face[1];
				nz = face[2];
			}
			float diffuse = Math.max(0, nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]);
			return AMBIENT + (1 - AMBIENT) * diffuse;
		}

		private static int paletteColour(MeshData mesh) {
			return 0xFF000000 | channel(mesh.colorR) << 16 | channel(mesh.colorG) << 8 | channel(mesh.colorB);
		}

		/** Nearest texel, sampled the way GL does: rows bottom-first, coordinates wrapping. */
		private static int texel(TextureData t, float[] uv, int a, int b, int c, float w0, float w1, float w2) {
			float u = w0 * uv[a * 2] + w1 * uv[b * 2] + w2 * uv[c * 2];
			float v = w0 * uv[a * 2 + 1] + w1 * uv[b * 2 + 1] + w2 * uv[c * 2 + 1];
			u -= (float) Math.floor(u);
			v -= (float) Math.floor(v);
			int tx = Math.min(t.width - 1, (int) (u * t.width));
			int ty = Math.min(t.height - 1, (int) (v * t.height));
			int i = (ty * t.width + tx) * 4;
			if (i < 0 || i + 3 >= t.pixels.length) {
				return 0xFF808080;
			}
			int alpha = t.pixels[i + 3] & 0xFF;
			if (alpha < 128) {
				return 0;
			}
			return 0xFF000000 | (t.pixels[i] & 0xFF) << 16 | (t.pixels[i + 1] & 0xFF) << 8 | (t.pixels[i + 2] & 0xFF);
		}

		private static int shade(int rgb, float light) {
			int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * light));
			int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * light));
			int b = Math.min(255, Math.round((rgb & 0xFF) * light));
			return 0xFF000000 | r << 16 | g << 8 | b;
		}

		private static int channel(float value) {
			return Math.clamp(Math.round(value * 255f), 0, 255);
		}

		BufferedImage toImage() {
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			int[] target = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
			System.arraycopy(argb, 0, target, 0, argb.length);
			return image;
		}
	}
}
