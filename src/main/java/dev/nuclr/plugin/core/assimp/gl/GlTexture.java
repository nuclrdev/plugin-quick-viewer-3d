package dev.nuclr.plugin.core.assimp.gl;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import dev.nuclr.plugin.core.assimp.model.TextureData;

/**
 * Wraps a single {@code GL_TEXTURE_2D} object.
 * Upload and mipmap generation happen in the constructor.
 *
 * <p>Must be created and closed while the GL context is current.
 */
public final class GlTexture implements AutoCloseable {

    private final int texId;

    public GlTexture(TextureData data) {
        texId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,     GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,     GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        ByteBuffer buf = MemoryUtil.memAlloc(data.pixels.length);
        try {
            buf.put(data.pixels).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                    data.width, data.height, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        } finally {
            MemoryUtil.memFree(buf);
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    /**
     * Activates the given texture unit and binds this texture to it.
     *
     * @param unit zero-based texture unit index (e.g. 0 → {@code GL_TEXTURE0})
     */
    public void bind(int unit) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
    }

    @Override
    public void close() {
        GL11.glDeleteTextures(texId);
    }
}
