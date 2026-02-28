package dev.nuclr.plugin.core.assimp.model;

/**
 * CPU-side mesh data ready for upload to the GPU.
 *
 * <p>All arrays are Java heap arrays so the GC handles them.
 * Once uploaded to OpenGL they can be discarded (the GlMesh holds the GPU handles).
 *
 * <p>Layout:
 * <ul>
 *   <li>{@code positions} — interleaved x,y,z  (length = numVertices × 3)</li>
 *   <li>{@code normals}   — interleaved nx,ny,nz (length = numVertices × 3)</li>
 *   <li>{@code uvs}       — interleaved u,v (length = numVertices × 2), or {@code null}
 *                           if the mesh has no UV channel</li>
 *   <li>{@code indices}   — triangle index triples (length = numFaces × 3)</li>
 * </ul>
 */
public final class MeshData {

    public final float[] positions;   // vec3 per vertex
    public final float[] normals;     // vec3 per vertex
    /** UV coordinates (vec2 per vertex), or {@code null} if no UV channel. */
    public final float[] uvs;         // vec2 per vertex, or null
    public final int[]   indices;     // 3 indices per triangle face

    public final int numVertices;
    public final int numFaces;

    /** Display colour assigned from a palette (r, g, b in [0,1]). */
    public final float colorR, colorG, colorB;

    /**
     * Index into {@link ModelData#textures}, or {@code -1} if this mesh has
     * no diffuse texture (falls back to the palette colour).
     */
    public final int textureIndex;

    public MeshData(float[] positions, float[] normals, float[] uvs, int[] indices,
                    int numVertices, int numFaces,
                    float colorR, float colorG, float colorB,
                    int textureIndex) {
        this.positions    = positions;
        this.normals      = normals;
        this.uvs          = uvs;
        this.indices      = indices;
        this.numVertices  = numVertices;
        this.numFaces     = numFaces;
        this.colorR       = colorR;
        this.colorG       = colorG;
        this.colorB       = colorB;
        this.textureIndex = textureIndex;
    }
}
