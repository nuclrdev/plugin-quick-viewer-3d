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
 *   <li>{@code indices}   — triangle index triples (length = numFaces × 3)</li>
 * </ul>
 */
public final class MeshData {

    public final float[] positions;   // vec3 per vertex
    public final float[] normals;     // vec3 per vertex
    public final int[]   indices;     // 3 indices per triangle face

    public final int numVertices;
    public final int numFaces;

    /** Display colour assigned from a palette (r, g, b in [0,1]). */
    public final float colorR, colorG, colorB;

    public MeshData(float[] positions, float[] normals, int[] indices,
                    int numVertices, int numFaces,
                    float colorR, float colorG, float colorB) {
        this.positions   = positions;
        this.normals     = normals;
        this.indices     = indices;
        this.numVertices = numVertices;
        this.numFaces    = numFaces;
        this.colorR      = colorR;
        this.colorG      = colorG;
        this.colorB      = colorB;
    }
}
