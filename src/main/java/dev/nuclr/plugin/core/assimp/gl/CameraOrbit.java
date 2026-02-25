package dev.nuclr.plugin.core.assimp.gl;

/**
 * Orbit (arc-ball) camera around a configurable target point.
 *
 * <p>All matrix math is pure Java using column-major {@code float[16]} arrays
 * (OpenGL convention). No external math library is required.
 *
 * <p>Not thread-safe — must be used from the EDT / GL thread only.
 */
public final class CameraOrbit {

    // ── Spherical coordinates ─────────────────────────────────────────────────

    private float yaw   = (float) Math.toRadians(30.0);   // horizontal angle
    private float pitch = (float) Math.toRadians(25.0);   // vertical angle
    private float distance = 5.0f;

    // ── Target (orbit centre) ─────────────────────────────────────────────────

    private float targetX, targetY, targetZ;

    // ── Projection ────────────────────────────────────────────────────────────

    private float fovY   = (float) Math.toRadians(45.0);
    private float aspect = 1.0f;
    private float zNear  = 0.01f;
    private float zFar   = 10_000f;

    // ── Clamps ────────────────────────────────────────────────────────────────

    private static final float MIN_PITCH    = (float) Math.toRadians(-89.0);
    private static final float MAX_PITCH    = (float) Math.toRadians(89.0);
    private static final float MIN_DISTANCE = 0.001f;

    // ── Input ─────────────────────────────────────────────────────────────────

    /** Rotate the camera by {@code dYaw} and {@code dPitch} radians. */
    public void orbit(float dYaw, float dPitch) {
        yaw   += dYaw;
        pitch  = clamp(pitch + dPitch, MIN_PITCH, MAX_PITCH);
    }

    /**
     * Pan the target in the camera's local XY plane.
     * {@code dx}/{@code dy} are screen-space pixel deltas.
     */
    public void pan(float dx, float dy) {
        float[] right = cameraRight();
        float[] up    = cameraUp();
        // Scale pan speed so it feels proportional to model size.
        float scale = distance * 0.0015f;
        targetX += (-dx * right[0] + dy * up[0]) * scale;
        targetY += (-dx * right[1] + dy * up[1]) * scale;
        targetZ += (-dx * right[2] + dy * up[2]) * scale;
    }

    /**
     * Zoom by a signed scroll {@code delta}.
     * Positive delta = zoom in (distance shrinks).
     */
    public void zoom(float delta) {
        distance *= (1.0f - delta * 0.12f);
        distance  = Math.max(distance, MIN_DISTANCE);
    }

    /**
     * Frame the model by positioning the camera so the bounding sphere is
     * fully visible.
     */
    public void frameModel(float cx, float cy, float cz, float radius) {
        targetX  = cx;
        targetY  = cy;
        targetZ  = cz;
        // Distance needed so sphere fits inside the vertical FOV with some margin.
        float d  = radius / (float) Math.tan(fovY * 0.5f);
        distance = Math.max(d * 1.25f, MIN_DISTANCE);
        yaw      = (float) Math.toRadians(30.0);
        pitch    = (float) Math.toRadians(25.0);
        updateNearFar(radius);
    }

    /** Reset orientation; preserve target and distance. */
    public void resetView() {
        yaw   = (float) Math.toRadians(30.0);
        pitch = (float) Math.toRadians(25.0);
    }

    // ── Matrix output ─────────────────────────────────────────────────────────

    /**
     * Returns a column-major MVP matrix {@code (Projection × View)}.
     * No model matrix — vertices are in world space.
     */
    public float[] buildMVP() {
        return mul4(buildProjection(), buildView());
    }

    /** Column-major view matrix. */
    public float[] buildView() {
        float[] eye = eyePosition();
        float[] tgt = {targetX, targetY, targetZ};
        float[] up  = {0, 1, 0};
        return lookAt(eye, tgt, up);
    }

    /** Column-major perspective projection matrix. */
    public float[] buildProjection() {
        return perspective(fovY, aspect, zNear, zFar);
    }

    /**
     * Returns the upper-left 3×3 of the view matrix as a {@code float[9]}
     * (column-major).  Used as the normal matrix when the model matrix is
     * the identity (no non-uniform scale).
     */
    public float[] buildNormalMatrix3x3() {
        float[] v = buildView();
        return new float[] {
            v[0], v[1], v[2],
            v[4], v[5], v[6],
            v[8], v[9], v[10]
        };
    }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setAspect(float aspect) {
        this.aspect = Math.max(aspect, 0.001f);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private float[] eyePosition() {
        float cosP = (float) Math.cos(pitch);
        float sinP = (float) Math.sin(pitch);
        float cosY = (float) Math.cos(yaw);
        float sinY = (float) Math.sin(yaw);
        return new float[] {
            targetX + distance * cosP * sinY,
            targetY + distance * sinP,
            targetZ + distance * cosP * cosY
        };
    }

    private float[] cameraRight() {
        float[] v = buildView();
        // Right = first row of view: (v[0], v[4], v[8])
        return normalize3(new float[] {v[0], v[4], v[8]});
    }

    private float[] cameraUp() {
        float[] v = buildView();
        return normalize3(new float[] {v[1], v[5], v[9]});
    }

    private void updateNearFar(float radius) {
        zNear = Math.max(radius * 0.001f, 0.001f);
        zFar  = Math.max(radius * 200f, zNear + 1.0f);
    }

    // ── Pure matrix / vector math ─────────────────────────────────────────────

    /**
     * Column-major lookAt:
     * <pre>
     * | r.x  r.y  r.z  -dot(r,e) |
     * | u.x  u.y  u.z  -dot(u,e) |
     * | -f.x -f.y -f.z  dot(f,e) |
     * | 0    0    0     1         |
     * </pre>
     */
    private static float[] lookAt(float[] eye, float[] center, float[] up) {
        float[] f = normalize3(sub3(center, eye));
        float[] r = normalize3(cross3(f, up));
        float[] u = cross3(r, f);
        float[] m = new float[16];
        // column 0
        m[0] = r[0]; m[1] = u[0]; m[2] = -f[0]; m[3] = 0;
        // column 1
        m[4] = r[1]; m[5] = u[1]; m[6] = -f[1]; m[7] = 0;
        // column 2
        m[8] = r[2]; m[9] = u[2]; m[10] = -f[2]; m[11] = 0;
        // column 3 (translation)
        m[12] = -dot3(r, eye);
        m[13] = -dot3(u, eye);
        m[14] =  dot3(f, eye);
        m[15] = 1;
        return m;
    }

    /**
     * Standard OpenGL perspective matrix (column-major).
     * Maps z to NDC with clip planes [zNear, zFar].
     */
    private static float[] perspective(float fovY, float aspect,
                                       float zNear, float zFar) {
        float f  = 1.0f / (float) Math.tan(fovY * 0.5f);
        float[] m = new float[16]; // all zeros
        m[0]  = f / aspect;
        m[5]  = f;
        m[10] = (zFar + zNear) / (zNear - zFar);
        m[11] = -1.0f;
        m[14] = (2.0f * zFar * zNear) / (zNear - zFar);
        // m[15] stays 0 (projective w)
        return m;
    }

    /** Column-major 4×4 matrix multiply: r = a × b. */
    private static float[] mul4(float[] a, float[] b) {
        float[] r = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float s = 0;
                for (int k = 0; k < 4; k++) {
                    s += a[k * 4 + row] * b[col * 4 + k];
                }
                r[col * 4 + row] = s;
            }
        }
        return r;
    }

    private static float[] sub3(float[] a, float[] b) {
        return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float dot3(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float[] cross3(float[] a, float[] b) {
        return new float[] {
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        };
    }

    private static float[] normalize3(float[] v) {
        float len = (float) Math.sqrt(dot3(v, v));
        if (len < 1e-10f) return new float[] {0, 1, 0};
        return new float[] {v[0] / len, v[1] / len, v[2] / len};
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
