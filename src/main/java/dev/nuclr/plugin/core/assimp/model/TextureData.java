package dev.nuclr.plugin.core.assimp.model;

/**
 * Decoded texture image ready for upload to the GPU.
 *
 * <p>Pixels are in RGBA order, one byte per channel, row-major.
 * Row 0 is the <em>bottom</em> row so the array can be passed directly
 * to {@code glTexImage2D} without any re-ordering.
 */
public final class TextureData {

    public final int    width;
    public final int    height;
    /** RGBA bytes, bottom-row-first (OpenGL order).  Length = width × height × 4. */
    public final byte[] pixels;

    public TextureData(int width, int height, byte[] pixels) {
        this.width  = width;
        this.height = height;
        this.pixels = pixels;
    }
}
