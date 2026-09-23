package dev.nuclr.plugin.core.assimp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.assimp.model.MeshData;
import dev.nuclr.plugin.core.assimp.model.ModelData;

class ModelThumbnailRendererTest {

	@Test
	void drawsATallModelIntoATallPictureWithinTheBox() {
		BufferedImage image = ModelThumbnailRenderer.render(tallPanel(), 100, 100, new AtomicBoolean());

		assertNotNull(image);
		assertEquals(100, image.getHeight(), "the model's height should fill the box");
		assertTrue(image.getWidth() < image.getHeight(), "a tall model makes a tall thumbnail");
	}

	@Test
	void leavesTheBackgroundTransparentAndShadesTheModel() {
		BufferedImage image = ModelThumbnailRenderer.render(tallPanel(), 100, 100, new AtomicBoolean());

		assertNotNull(image);
		assertEquals(0, image.getRGB(0, 0) >>> 24, "corner is background");
		int centre = image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
		assertEquals(0xFF, centre >>> 24, "centre is model");
		assertTrue((centre & 0xFF) > 0, "lit, not black");
	}

	@Test
	void returnsNullWhenCancelledOrWhenTheImportFailed() {
		assertNull(ModelThumbnailRenderer.render(tallPanel(), 100, 100, new AtomicBoolean(true)));
		assertNull(ModelThumbnailRenderer.render(new ModelData("broken", new ModelStats()), 100, 100,
				new AtomicBoolean()));
	}

	/** A 1 x 3 upright quad facing +Z, centred on the origin, without normals. */
	private static ModelData tallPanel() {
		float[] positions = {
				-0.5f, -1.5f, 0,
				0.5f, -1.5f, 0,
				0.5f, 1.5f, 0,
				-0.5f, 1.5f, 0 };
		int[] indices = { 0, 1, 2, 0, 2, 3 };
		MeshData mesh = new MeshData(positions, null, null, indices, 4, 2, 0.4f, 0.6f, 0.9f, -1);
		return new ModelData(new ModelStats(), List.of(mesh), List.of(), 0, 0, 0, 1.6f);
	}
}
