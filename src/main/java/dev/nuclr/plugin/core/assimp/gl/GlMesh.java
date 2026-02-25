package dev.nuclr.plugin.core.assimp.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import dev.nuclr.plugin.core.assimp.model.MeshData;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * GPU representation of a single mesh: one VAO, one interleaved VBO
 * (positions + normals), one IBO.
 *
 * <p>Interleaved layout per vertex (stride = 24 bytes = 6 floats):
 * <pre>
 *   [px, py, pz,  nx, ny, nz]
 *    ↑ attrib 0   ↑ attrib 1
 * </pre>
 *
 * <p>Must be created and closed while the GL context is current.
 */
public final class GlMesh implements AutoCloseable {

    private final int vaoId;
    private final int vboId;
    private final int iboId;
    private final int indexCount;

    public final float colorR, colorG, colorB;

    public GlMesh(MeshData data) {
        this.colorR     = data.colorR;
        this.colorG     = data.colorG;
        this.colorB     = data.colorB;
        this.indexCount = data.indices.length;

        vaoId = GL30.glGenVertexArrays();
        vboId = GL15.glGenBuffers();
        iboId = GL15.glGenBuffers();

        GL30.glBindVertexArray(vaoId);

        // ── Interleaved VBO (pos + normal) ────────────────────────────────────
        int nv = data.numVertices;
        FloatBuffer interleaved = MemoryUtil.memAllocFloat(nv * 6);
        try {
            for (int v = 0; v < nv; v++) {
                int b = v * 3;
                interleaved.put(data.positions[b]).put(data.positions[b+1]).put(data.positions[b+2]);
                interleaved.put(data.normals[b]  ).put(data.normals[b+1]  ).put(data.normals[b+2]  );
            }
            interleaved.flip();

            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, interleaved, GL15.GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(interleaved);
        }

        int stride = 6 * Float.BYTES;
        // attrib 0 = position (vec3)
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0L);
        GL20.glEnableVertexAttribArray(0);
        // attrib 1 = normal (vec3)
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3L * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);

        // ── IBO ───────────────────────────────────────────────────────────────
        IntBuffer idxBuf = MemoryUtil.memAllocInt(data.indices.length);
        try {
            idxBuf.put(data.indices).flip();
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, iboId);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, idxBuf, GL15.GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(idxBuf);
        }

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    // ── Draw calls ────────────────────────────────────────────────────────────

    public void draw() {
        GL30.glBindVertexArray(vaoId);
        GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, 0L);
        GL30.glBindVertexArray(0);
    }

    /**
     * Draws as a wireframe overlay by temporarily switching polygon mode.
     * Caller should set the shader colour before calling.
     */
    public void drawWireframe() {
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
        draw();
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        GL30.glDeleteVertexArrays(vaoId);
        GL15.glDeleteBuffers(vboId);
        GL15.glDeleteBuffers(iboId);
    }
}
