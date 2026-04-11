package dev.nuclr.plugin.core.assimp.gl;

import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.geom.AffineTransform;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.lwjgl.opengl.*;
import org.lwjgl.opengl.awt.AWTGLCanvas;
import org.lwjgl.opengl.awt.GLData;

import dev.nuclr.plugin.core.assimp.model.ModelData;
import dev.nuclr.plugin.core.assimp.model.MeshData;
import dev.nuclr.plugin.core.assimp.model.TextureData;
import lombok.extern.slf4j.Slf4j;

/**
 * AWT canvas that renders a 3D model using LWJGL OpenGL (3.3 core profile).
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Constructor starts the Swing Timer (but render attempts are no-ops
 *       until the canvas has a native peer).</li>
 *   <li>{@link #addNotify()} is called by AWT when the canvas gains a native
 *       peer (i.e., is added to a visible window).  We mark dirty here to
 *       guarantee the first real render fires on the very next timer tick.</li>
 *   <li>The timer calls {@link #render()} <em>directly</em> — not
 *       {@code repaint()} — bypassing AWT paint-coalescing.</li>
 *   <li>{@link #removeNotify()} stops the timer and releases GL resources
 *       before the native peer is destroyed.</li>
 * </ol>
 *
 * <h3>Keyboard shortcuts (click the viewport first to grab focus)</h3>
 * T textures · W wireframe · G grid · X axes · L lit/unlit · B bounding box ·
 * F frame model · R reset camera
 */
@Slf4j
public final class ModelViewportCanvas extends AWTGLCanvas {

    // ── Shaders ───────────────────────────────────────────────────────────────

    private static final String MODEL_VERT =
        "#version 330 core\n"
        + "layout(location = 0) in vec3 aPos;\n"
        + "layout(location = 1) in vec3 aNormal;\n"
        + "layout(location = 2) in vec2 aTexCoord;\n"
        + "uniform mat4 uMVP;\n"
        + "out vec3 vNormal;\n"
        + "out vec2 vTexCoord;\n"
        + "void main() {\n"
        + "    vNormal     = aNormal;\n"
        + "    vTexCoord   = aTexCoord;\n"
        + "    gl_Position = uMVP * vec4(aPos, 1.0);\n"
        + "}\n";

    /**
     * Two-sided Lambert: {@code abs(dot(n,L))} so models with inverted normals
     * still appear lit.  Toggle with the L key.
     * When {@code uHasTexture != 0} the diffuse texture is sampled instead of
     * the flat palette colour.  Toggle textures with the T key.
     */
    private static final String MODEL_FRAG =
        "#version 330 core\n"
        + "in  vec3      vNormal;\n"
        + "in  vec2      vTexCoord;\n"
        + "uniform vec3      uColor;\n"
        + "uniform int       uLit;\n"
        + "uniform int       uHasTexture;\n"
        + "uniform sampler2D uTexture;\n"
        + "out vec4 FragColor;\n"
        + "void main() {\n"
        + "    vec3 base = (uHasTexture != 0)\n"
        + "                ? texture(uTexture, vTexCoord).rgb\n"
        + "                : uColor;\n"
        + "    if (uLit != 0) {\n"
        + "        vec3  L    = normalize(vec3(0.5, 1.0, 0.8));\n"
        + "        float diff = abs(dot(normalize(vNormal), L));\n"
        + "        float amb  = 0.25;\n"
        + "        FragColor  = vec4(base * (amb + (1.0 - amb) * diff), 1.0);\n"
        + "    } else {\n"
        + "        FragColor = vec4(base, 1.0);\n"
        + "    }\n"
        + "}\n";

    // ── Viewport toggles ──────────────────────────────────────────────────────

    private boolean showTextures  = true;
    private boolean showWireframe = false;
    private boolean showGrid      = true;
    private boolean showAxes      = true;
    private boolean litMode       = true;
    private boolean showBbox      = false;

    // ── Camera ────────────────────────────────────────────────────────────────

    private final CameraOrbit camera = new CameraOrbit();

    // ── Mouse state ───────────────────────────────────────────────────────────

    private int     lastMX, lastMY;
    private boolean lmbDown, mmbDown;

    // ── GL resources ──────────────────────────────────────────────────────────

    private ShaderProgram         modelShader;
    private GridRenderer          gridRenderer;
    private final List<GlMesh>    glMeshes    = new ArrayList<>();
    private final List<GlTexture> glTextures  = new ArrayList<>();
    private boolean               glReady     = false;

    // ── Model data ────────────────────────────────────────────────────────────

    /** Set on the EDT by {@link #setModelData}; consumed in {@link #paintGL}. */
    private volatile ModelData pendingModel;
    private volatile ModelData currentModel;

    // ── Dispose / lifecycle ───────────────────────────────────────────────────

    private volatile boolean disposeRequested = false;
    private boolean          removeNotifyCalled = false;

    // ── Render timer ──────────────────────────────────────────────────────────

    private final Timer renderTimer;
    private volatile boolean dirty = true;
    private int  idleFrames   = 0;

    /**
     * Counts timer ticks where {@code render()} was attempted but
     * {@link #glReady} is still false.  Used to detect a silent GL-context
     * creation failure and show the user an error rather than a black screen.
     */
    private final AtomicInteger noGlReadyTicks = new AtomicInteger(0);
    /** After this many failed ticks (~2 s) we give up and report an error. */
    private static final int NO_GL_READY_TIMEOUT = 125;

    // ── Error / ready callbacks ────────────────────────────────────────────────

    private final Consumer<String> onGlError;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ModelViewportCanvas(Consumer<String> onGlError) {
        super(buildGlData());
        this.onGlError = onGlError;
        setupInputListeners();

        renderTimer = new Timer(16, e -> timerTick());
        renderTimer.setCoalesce(true);
        renderTimer.start();
    }

    private static GLData buildGlData() {
        GLData d = new GLData();
        d.majorVersion = 3;
        d.minorVersion = 3;
        d.profile      = GLData.Profile.CORE;
        d.doubleBuffer = true;
        d.depthSize    = 24;
        d.samples      = 0; // no MSAA — maximises driver compatibility
        return d;
    }

    // ── AWT peer lifecycle ────────────────────────────────────────────────────

    /**
     * Called by AWT when the canvas gains a native peer — i.e., it has been
     * added to a showing window and is now "displayable".
     *
     * <p>lwjgl3-awt creates the platform GL context inside
     * {@code super.addNotify()}.  We mark the scene dirty immediately after so
     * the timer triggers {@link #initGL} + {@link #paintGL} on its very next
     * tick, rather than waiting for the idle fallback.
     */
    @Override
    public void addNotify() {
        super.addNotify(); // ← lwjgl3-awt creates the platform GL context here
        log.debug("ModelViewportCanvas.addNotify — canvas is now displayable");
        dirty = true;      // force a render now that we have a real peer
    }

    /**
     * Called by AWT just before the native peer is destroyed.
     * Stops the timer and releases GL resources while the context is still valid.
     */
    @Override
    public void removeNotify() {
        if (removeNotifyCalled) {
            return;
        }
        removeNotifyCalled = true;
        renderTimer.stop();
        if (glReady) {
            disposeRequested = true;
            try {
                render(); // paintGL sees disposeRequested → cleanupGlResources()
            } catch (Exception ex) {
                log.warn("GL cleanup on removeNotify threw", ex);
            }
        }
        try {
            super.removeNotify(); // ← lwjgl3-awt destroys the GL context here
        } catch (NullPointerException ex) {
            // JAWT drawing surface was never acquired (canvas removed before first render).
            // lwjgl3-awt's PlatformWin32GLCanvas.dispose() calls JAWT_FreeDrawingSurface(ds)
            // without a null-check; swallow the NPE here since there is nothing to free.
            log.warn("AWTGLCanvas.removeNotify threw (canvas disposed before GL context was acquired)");
        }
    }

    // ── Public API (EDT) ──────────────────────────────────────────────────────

    /**
     * Delivers a freshly-parsed model; GPU upload happens in the next
     * {@link #paintGL} call.  Pass {@code null} to clear the viewport.
     */
    public void setModelData(ModelData data) {
        pendingModel = data;
        if (data != null && !data.hasError()) {
            camera.frameModel(data.centerX, data.centerY, data.centerZ,
                              data.boundingRadius);
        }
        dirty = true;
    }

    /**
     * Explicit disposal — call from the EDT before removing the panel.
     * {@link #removeNotify()} also handles this as a safety net.
     */
    public void dispose() {
        renderTimer.stop();
        if (!glReady) return;
        disposeRequested = true;
        try {
            render();
        } catch (Exception ex) {
            log.warn("GL dispose threw", ex);
        }
    }

    // ── Timer tick ────────────────────────────────────────────────────────────

    private void timerTick() {
        if (disposeRequested) return;

        boolean shouldRender;
        if (dirty) {
            dirty        = false;
            idleFrames   = 0;
            shouldRender = true;
        } else {
            idleFrames++;
            // Idle: one maintenance render every ~6 ticks (~96 ms) so the
            // viewport stays live for resize / expose events.
            shouldRender = (idleFrames > 60) && (idleFrames % 6 == 0);
        }

        if (!shouldRender) return;

        try {
            render(); // makes GL context current on the EDT → paintGL()
        } catch (Exception ex) {
            log.warn("render() threw: {}", ex.getMessage(), ex);
        }

        // ── Detect silent GL-context creation failure ─────────────────────────
        // If render() runs without error but initGL() never set glReady=true,
        // lwjgl3-awt likely failed to create the platform GL context without
        // calling our initGL().  After ~2 s, report it to the user.
        if (!glReady) {
            int n = noGlReadyTicks.incrementAndGet();
            if (n == NO_GL_READY_TIMEOUT) {
                String msg = "3D preview unavailable: OpenGL 3.3 context could "
                    + "not be created. Check your graphics drivers or run with "
                    + "-Dorg.lwjgl.util.Debug=true for details.";
                log.warn(msg);
                SwingUtilities.invokeLater(() -> onGlError.accept(msg));
            }
        } else {
            noGlReadyTicks.set(0);
        }
    }

    // ── AWTGLCanvas callbacks ─────────────────────────────────────────────────

    @Override
    public void initGL() {
        try {
            GL.createCapabilities();

            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LESS);
            // Distinct background — easier to confirm GL is running vs pure black
            GL11.glClearColor(0.20f, 0.20f, 0.23f, 1.0f);

            modelShader  = new ShaderProgram(MODEL_VERT, MODEL_FRAG);
            gridRenderer = new GridRenderer();

            // Bind uTexture to texture unit 0 (once, never changes).
            modelShader.use();
            modelShader.setInt("uTexture", 0);

            glReady = true;
            log.info("ModelViewportCanvas GL ready — renderer: {}  version: {}",
                     GL11.glGetString(GL11.GL_RENDERER),
                     GL11.glGetString(GL11.GL_VERSION));
        } catch (Throwable t) {
            String msg = "OpenGL init failed: " + t.getMessage();
            log.warn(msg, t);
            SwingUtilities.invokeLater(() -> onGlError.accept(msg));
        }
    }

    @Override
    public void paintGL() {
        // ── Dispose path ──────────────────────────────────────────────────────
        if (disposeRequested) {
            cleanupGlResources();
            return; // do NOT swap — context is being torn down
        }

        if (!glReady) return; // initGL() failed or not yet called

        // ── Upload pending model ──────────────────────────────────────────────
        ModelData snap = pendingModel; // read volatile once
        if (snap != null) {
            pendingModel = null;       // consume before upload so re-entry is safe
            currentModel = snap;
            uploadModel(snap);
            log.info("ModelViewportCanvas: {} mesh(es) / {} texture(s) uploaded",
                     glMeshes.size(), glTextures.size());
        }

        // ── Frame ─────────────────────────────────────────────────────────────
        // On HiDPI displays (e.g. Windows 125% scaling), getWidth()/getHeight()
        // return logical pixels while lwjgl3-awt's underlying HWND and the GL
        // framebuffer are in physical pixels.  glViewport must use physical
        // pixels, otherwise the scene renders only in the bottom-left corner.
        int w = getWidth();
        int h = getHeight();
        GraphicsConfiguration gc = getGraphicsConfiguration();
        if (gc != null) {
            AffineTransform tx = gc.getDefaultTransform();
            w = (int)(w * tx.getScaleX());
            h = (int)(h * tx.getScaleY());
        }
        w = Math.max(w, 1);
        h = Math.max(h, 1);
        camera.setAspect((float) w / h);
        GL11.glViewport(0, 0, w, h);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        float[] mvp = camera.buildMVP();

        // ── Grid + axes ───────────────────────────────────────────────────────
        if (showGrid || showAxes) {
            float scale = (currentModel != null && !currentModel.hasError())
                          ? Math.max(currentModel.boundingRadius * 0.15f, 0.01f)
                          : 0.1f;
            gridRenderer.draw(mvp, scale, showGrid, showAxes);
        }

        // ── Bounding box ──────────────────────────────────────────────────────
        if (showBbox && currentModel != null && !currentModel.hasError()
                && currentModel.stats.isHasBoundingBox()) {
            var s = currentModel.stats;
            gridRenderer.drawBoundingBox(mvp,
                    s.getMinX(), s.getMinY(), s.getMinZ(),
                    s.getMaxX(), s.getMaxY(), s.getMaxZ());
        }

        // ── Model meshes ──────────────────────────────────────────────────────
        if (!glMeshes.isEmpty()) {
            modelShader.use();
            modelShader.setMat4("uMVP", mvp);
            modelShader.setInt("uLit", litMode ? 1 : 0);

            for (GlMesh mesh : glMeshes) {
                if (showWireframe) {
                    modelShader.setInt("uHasTexture", 0);
                    modelShader.setVec3("uColor",
                            mesh.colorR * 0.45f,
                            mesh.colorG * 0.45f,
                            mesh.colorB * 0.45f);
                    modelShader.setInt("uLit", 0);
                    mesh.draw();
                    modelShader.setVec3("uColor", 0.82f, 0.88f, 0.94f);
                    mesh.drawWireframe();
                    modelShader.setInt("uLit", litMode ? 1 : 0);
                } else {
                    // Use texture when enabled, available, and successfully uploaded.
                    boolean useTexture = showTextures
                            && mesh.textureIndex >= 0
                            && mesh.textureIndex < glTextures.size()
                            && glTextures.get(mesh.textureIndex) != null;
                    if (useTexture) {
                        glTextures.get(mesh.textureIndex).bind(0);
                        modelShader.setInt("uHasTexture", 1);
                    } else {
                        modelShader.setInt("uHasTexture", 0);
                        modelShader.setVec3("uColor", mesh.colorR, mesh.colorG, mesh.colorB);
                    }
                    mesh.draw();
                    if (useTexture) {
                        // Unbind so other draw calls don't accidentally sample this texture.
                        GL13.glActiveTexture(GL13.GL_TEXTURE0);
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                    }
                }
            }
        }

        swapBuffers();
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    private void setupInputListeners() {
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                lastMX  = e.getX();
                lastMY  = e.getY();
                lmbDown = SwingUtilities.isLeftMouseButton(e);
                mmbDown = e.getButton() == MouseEvent.BUTTON2;
                requestFocusInWindow();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) lmbDown = false;
                if (e.getButton() == MouseEvent.BUTTON2)  mmbDown = false;
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - lastMX;
                int dy = e.getY() - lastMY;
                lastMX = e.getX();
                lastMY = e.getY();
                boolean pan = mmbDown
                        || (lmbDown && (e.getModifiersEx()
                                        & MouseEvent.SHIFT_DOWN_MASK) != 0);
                if (pan)          camera.pan(dx, dy);
                else if (lmbDown) camera.orbit(
                        (float) Math.toRadians(dx * 0.5),
                        (float) Math.toRadians(dy * 0.5));
                dirty = true;
            }
        });

        addMouseWheelListener(e -> {
            camera.zoom(-(float) e.getPreciseWheelRotation());
            dirty = true;
        });

        addKeyListener(new KeyAdapter() {
            @Override public void keyTyped(KeyEvent e) {
                switch (e.getKeyChar()) {
                    case 't','T' -> { showTextures  = !showTextures;  dirty = true; }
                    case 'w','W' -> { showWireframe = !showWireframe; dirty = true; }
                    case 'g','G' -> { showGrid      = !showGrid;      dirty = true; }
                    case 'x','X' -> { showAxes      = !showAxes;      dirty = true; }
                    case 'l','L' -> { litMode       = !litMode;       dirty = true; }
                    case 'b','B' -> { showBbox      = !showBbox;      dirty = true; }
                    case 'r','R' -> { camera.resetView();             dirty = true; }
                    case 'f','F' -> {
                        ModelData m = currentModel;
                        if (m != null && !m.hasError()) {
                            camera.frameModel(m.centerX, m.centerY, m.centerZ,
                                             m.boundingRadius);
                            dirty = true;
                        }
                    }
                }
            }
        });

        setFocusable(true);
    }

    // ── GL helpers ────────────────────────────────────────────────────────────

    private void uploadModel(ModelData data) {
        disposeGlMeshes();
        if (data == null || data.hasError()) return;

        // Upload textures first so mesh textureIndex references are valid.
        for (TextureData td : data.textures) {
            try {
                glTextures.add(new GlTexture(td));
            } catch (Exception ex) {
                log.warn("Failed to upload texture: {}", ex.getMessage(), ex);
                glTextures.add(null); // keep list indices aligned with ModelData.textures
            }
        }

        for (MeshData md : data.meshes) {
            try {
                glMeshes.add(new GlMesh(md));
            } catch (Exception ex) {
                log.warn("Failed to upload mesh: {}", ex.getMessage(), ex);
            }
        }
    }

    private void disposeGlMeshes() {
        for (GlMesh m : glMeshes) m.close();
        glMeshes.clear();
        for (GlTexture t : glTextures) { if (t != null) t.close(); }
        glTextures.clear();
    }

    private void cleanupGlResources() {
        disposeGlMeshes();
        if (modelShader  != null) { modelShader.close();  modelShader  = null; }
        if (gridRenderer != null) { gridRenderer.close(); gridRenderer = null; }
        glReady = false;
        log.debug("ModelViewportCanvas: GL resources released");
    }
}
