package dev.nuclr.plugin.core.assimp.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Renders a ground-plane grid and XYZ world-space axes using simple line
 * geometry (position-only VAO + flat-colour fragment shader).
 *
 * <p>The grid is centred at the world origin, scaled dynamically to match the
 * model size via {@link #draw(float[], float)}.
 *
 * <p>Must be created and closed while the GL context is current.
 */
public final class GridRenderer implements AutoCloseable {

    // Grid: 2×HALF_STEPS+1 lines in each direction (total 4*(HALF_STEPS+1) lines)
    private static final int HALF_STEPS = 10; // grid extends from -10 to +10

    private static final String VERT_SRC =
        "#version 330 core\n"
        + "layout(location = 0) in vec3 aPos;\n"
        + "uniform mat4 uMVP;\n"
        + "void main() { gl_Position = uMVP * vec4(aPos, 1.0); }\n";

    private static final String FRAG_SRC =
        "#version 330 core\n"
        + "uniform vec3 uColor;\n"
        + "out vec4 FragColor;\n"
        + "void main() { FragColor = vec4(uColor, 1.0); }\n";

    private final ShaderProgram shader;

    // Grid geometry
    private final int gridVao, gridVbo;
    private final int gridVertexCount;

    // Axes geometry (each axis = 2 vertices × 3 axes = 6 vertices)
    private final int axesVao, axesVbo;

    public GridRenderer() {
        shader = new ShaderProgram(VERT_SRC, FRAG_SRC);

        // ── Grid (XZ plane, Y = 0) ────────────────────────────────────────────
        //   Each line: 2 vertices × 3 floats. Lines parallel to X and lines
        //   parallel to Z.
        int lineCount     = (HALF_STEPS * 2 + 1) * 2; // along-X + along-Z
        int vCount        = lineCount * 2;
        FloatBuffer grid  = MemoryUtil.memAllocFloat(vCount * 3);
        try {
            float extent = HALF_STEPS; // in "grid units"; we scale via the MVP
            for (int i = -HALF_STEPS; i <= HALF_STEPS; i++) {
                float f = i;
                // Line parallel to X-axis at z = f
                grid.put(new float[] {-extent, 0, f,  extent, 0, f});
                // Line parallel to Z-axis at x = f
                grid.put(new float[] {f, 0, -extent,  f, 0, extent});
            }
            grid.flip();

            gridVao = GL30.glGenVertexArrays();
            gridVbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(gridVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, gridVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, grid, GL15.GL_STATIC_DRAW);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0L);
            GL20.glEnableVertexAttribArray(0);
            GL30.glBindVertexArray(0);
        } finally {
            MemoryUtil.memFree(grid);
        }
        gridVertexCount = vCount;

        // ── Axes (X=red, Y=green, Z=blue, length = 1 grid unit, scaled later) ─
        //   Origin to end of each axis = 6 vertices total.
        FloatBuffer axes = MemoryUtil.memAllocFloat(6 * 3);
        try {
            axes.put(new float[] {
                0, 0, 0,  1, 0, 0,  // X axis
                0, 0, 0,  0, 1, 0,  // Y axis
                0, 0, 0,  0, 0, 1   // Z axis
            }).flip();

            axesVao = GL30.glGenVertexArrays();
            axesVbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(axesVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, axesVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, axes, GL15.GL_STATIC_DRAW);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0L);
            GL20.glEnableVertexAttribArray(0);
            GL30.glBindVertexArray(0);
        } finally {
            MemoryUtil.memFree(axes);
        }
    }

    // ── Draw API ──────────────────────────────────────────────────────────────

    /**
     * Draws the grid and axes scaled to {@code gridScale} (e.g., the model's
     * bounding-sphere radius).
     *
     * @param mvp       column-major MVP matrix from {@link CameraOrbit#buildMVP()}
     * @param gridScale world-space size of one grid cell; the grid spans
     *                  {@code ±HALF_STEPS × gridScale}
     */
    public void draw(float[] mvp, float gridScale, boolean showGrid, boolean showAxes) {
        shader.use();

        // Build a scaled MVP: scale the grid by gridScale.
        float[] scaledMvp = scaleMvp(mvp, gridScale);

        if (showGrid) {
            shader.setMat4("uMVP", scaledMvp);
            shader.setVec3("uColor", 0.30f, 0.30f, 0.30f); // dark gray
            GL11.glLineWidth(1.0f);
            GL30.glBindVertexArray(gridVao);
            GL11.glDrawArrays(GL11.GL_LINES, 0, gridVertexCount);
            GL30.glBindVertexArray(0);
        }

        if (showAxes) {
            // Scale axes to be clearly visible (2× grid cell)
            float[] axesMvp = scaleMvp(mvp, gridScale * 2f);
            shader.setMat4("uMVP", axesMvp);
            GL11.glLineWidth(2.0f);
            GL30.glBindVertexArray(axesVao);

            // X axis — red
            shader.setVec3("uColor", 0.85f, 0.20f, 0.20f);
            GL11.glDrawArrays(GL11.GL_LINES, 0, 2);

            // Y axis — green
            shader.setVec3("uColor", 0.20f, 0.85f, 0.20f);
            GL11.glDrawArrays(GL11.GL_LINES, 2, 2);

            // Z axis — blue
            shader.setVec3("uColor", 0.20f, 0.40f, 0.85f);
            GL11.glDrawArrays(GL11.GL_LINES, 4, 2);

            GL30.glBindVertexArray(0);
            GL11.glLineWidth(1.0f);
        }
    }

    /**
     * Draws a bounding-box outline given min/max corners.
     */
    public void drawBoundingBox(float[] mvp,
                                float minX, float minY, float minZ,
                                float maxX, float maxY, float maxZ) {
        // Build 12 edges of an AABB as line pairs.
        float[] v = buildBoxLines(minX, minY, minZ, maxX, maxY, maxZ);

        int tempVao = GL30.glGenVertexArrays();
        int tempVbo = GL15.glGenBuffers();
        FloatBuffer buf = MemoryUtil.memAllocFloat(v.length);
        try {
            buf.put(v).flip();
            GL30.glBindVertexArray(tempVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, tempVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STREAM_DRAW);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0L);
            GL20.glEnableVertexAttribArray(0);

            shader.use();
            shader.setMat4("uMVP", mvp);
            shader.setVec3("uColor", 0.95f, 0.75f, 0.20f); // yellow
            GL11.glLineWidth(1.5f);
            GL11.glDrawArrays(GL11.GL_LINES, 0, v.length / 3);
            GL11.glLineWidth(1.0f);

            GL30.glBindVertexArray(0);
        } finally {
            MemoryUtil.memFree(buf);
            GL30.glDeleteVertexArrays(tempVao);
            GL15.glDeleteBuffers(tempVbo);
        }
    }

    @Override
    public void close() {
        shader.close();
        GL30.glDeleteVertexArrays(gridVao);
        GL15.glDeleteBuffers(gridVbo);
        GL30.glDeleteVertexArrays(axesVao);
        GL15.glDeleteBuffers(axesVbo);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Produces a new MVP that pre-multiplies a uniform scale by {@code s}.
     * Equivalent to MVP × Scale(s), achieved by scaling the first three
     * columns of the column-major matrix (the model-space basis vectors).
     */
    private static float[] scaleMvp(float[] mvp, float s) {
        float[] m = mvp.clone();
        for (int col = 0; col < 3; col++) {
            m[col * 4]     *= s;
            m[col * 4 + 1] *= s;
            m[col * 4 + 2] *= s;
            m[col * 4 + 3] *= s;
        }
        return m;
    }

    private static float[] buildBoxLines(float x0, float y0, float z0,
                                         float x1, float y1, float z1) {
        // 12 edges = 24 vertices = 72 floats
        return new float[] {
            // Bottom face
            x0,y0,z0, x1,y0,z0,   x1,y0,z0, x1,y0,z1,
            x1,y0,z1, x0,y0,z1,   x0,y0,z1, x0,y0,z0,
            // Top face
            x0,y1,z0, x1,y1,z0,   x1,y1,z0, x1,y1,z1,
            x1,y1,z1, x0,y1,z1,   x0,y1,z1, x0,y1,z0,
            // Vertical edges
            x0,y0,z0, x0,y1,z0,   x1,y0,z0, x1,y1,z0,
            x0,y0,z1, x0,y1,z1,   x1,y0,z1, x1,y1,z1,
        };
    }
}
