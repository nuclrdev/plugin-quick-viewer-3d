package dev.nuclr.plugin.core.assimp.gl;

import java.util.HashMap;
import java.util.Map;

import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import lombok.extern.slf4j.Slf4j;

/**
 * Compiles and links a GLSL vertex + fragment shader pair.
 * Caches uniform locations on first access.
 *
 * <p>Must be created and used only while the GL context is current on the
 * calling thread (i.e., inside {@code initGL()} or {@code paintGL()}).
 */
@Slf4j
public final class ShaderProgram implements AutoCloseable {

    private final int programId;
    private final Map<String, Integer> uniformCache = new HashMap<>();

    /**
     * @throws RuntimeException if shader compilation or linking fails.
     */
    public ShaderProgram(String vertSrc, String fragSrc) {
        int vert = compile(GL20.GL_VERTEX_SHADER,   vertSrc);
        int frag = compile(GL20.GL_FRAGMENT_SHADER, fragSrc);

        programId = GL20.glCreateProgram();
        GL20.glAttachShader(programId, vert);
        GL20.glAttachShader(programId, frag);
        GL20.glLinkProgram(programId);

        // Shaders are no longer needed once linked.
        GL20.glDetachShader(programId, vert);
        GL20.glDetachShader(programId, frag);
        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);

        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL20.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(programId);
            GL20.glDeleteProgram(programId);
            throw new RuntimeException("Shader link error:\n" + log);
        }
    }

    public void use() {
        GL20.glUseProgram(programId);
    }

    // ── Uniform setters ───────────────────────────────────────────────────────

    public void setMat4(String name, float[] m) {
        int loc = location(name);
        if (loc < 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20.glUniformMatrix4fv(loc, false, stack.floats(m));
        }
    }

    public void setMat3(String name, float[] m) {
        int loc = location(name);
        if (loc < 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20.glUniformMatrix3fv(loc, false, stack.floats(m));
        }
    }

    public void setVec3(String name, float x, float y, float z) {
        int loc = location(name);
        if (loc >= 0) GL20.glUniform3f(loc, x, y, z);
    }

    public void setFloat(String name, float v) {
        int loc = location(name);
        if (loc >= 0) GL20.glUniform1f(loc, v);
    }

    public void setInt(String name, int v) {
        int loc = location(name);
        if (loc >= 0) GL20.glUniform1i(loc, v);
    }

    public void setBool(String name, boolean v) {
        setInt(name, v ? 1 : 0);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        GL20.glDeleteProgram(programId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int location(String name) {
        return uniformCache.computeIfAbsent(name,
                n -> GL20.glGetUniformLocation(programId, n));
    }

    private static int compile(int type, String src) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL20.GL_FALSE) {
            String infoLog = GL20.glGetShaderInfoLog(id);
            GL20.glDeleteShader(id);
            String typeName = (type == GL20.GL_VERTEX_SHADER) ? "vertex" : "fragment";
            throw new RuntimeException(typeName + " shader compile error:\n" + infoLog);
        }
        return id;
    }
}
