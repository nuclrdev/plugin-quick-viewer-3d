package dev.nuclr.plugin.core.assimp;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;

import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AITexel;
import org.lwjgl.assimp.AITexture;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.Assimp;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import dev.nuclr.plugin.QuickViewItem;
import dev.nuclr.plugin.core.assimp.model.MeshData;
import dev.nuclr.plugin.core.assimp.model.ModelData;
import dev.nuclr.plugin.core.assimp.model.TextureData;
import lombok.extern.slf4j.Slf4j;

/**
 * Imports a 3D model file via LWJGL Assimp and produces a {@link ModelData}
 * containing both GPU-ready mesh buffers and a statistics DTO.
 *
 * <p>All methods are static and safe to call from any thread.  The caller is
 * responsible for checking the {@link AtomicBoolean} cancellation token and
 * discarding stale results.
 *
 * <h3>Limits</h3>
 * <ul>
 *   <li>File size: 250 MB</li>
 *   <li>Total vertices: 5 000 000</li>
 *   <li>Total indices: 15 000 000</li>
 *   <li>Mesh count: 10 000 (bounding-box skipped above this)</li>
 *   <li>Texture dimensions: 4096 × 4096 (larger images are down-scaled)</li>
 * </ul>
 */
@Slf4j
public final class AssimpModelReader {

    // ── Limits ────────────────────────────────────────────────────────────────

    static final long MAX_FILE_BYTES  = 250L * 1024 * 1024; // 250 MB
    static final long MAX_VERTICES    = 5_000_000L;
    static final long MAX_INDICES     = 15_000_000L;
    static final int  MAX_MESH_COUNT  = 10_000;
    private static final int MAX_TEX_DIM = 4096;

    // ── Mesh colour palette ───────────────────────────────────────────────────

    // Pleasant flat colours; cycles if the mesh count exceeds the palette size.
    private static final float[][] PALETTE = {
        {0.62f, 0.73f, 0.84f}, // steel blue
        {0.84f, 0.62f, 0.62f}, // soft red
        {0.62f, 0.84f, 0.66f}, // soft green
        {0.84f, 0.76f, 0.54f}, // warm tan
        {0.76f, 0.62f, 0.84f}, // lavender
        {0.84f, 0.84f, 0.54f}, // pale yellow
        {0.54f, 0.84f, 0.84f}, // cyan
        {0.84f, 0.68f, 0.84f}, // pink
    };

    // ── Texture types to scan for texture-path extraction ─────────────────────

    private static final int[] TEXTURE_TYPES = {
        Assimp.aiTextureType_DIFFUSE,
        Assimp.aiTextureType_SPECULAR,
        Assimp.aiTextureType_AMBIENT,
        Assimp.aiTextureType_EMISSIVE,
        Assimp.aiTextureType_HEIGHT,
        Assimp.aiTextureType_NORMALS,
        Assimp.aiTextureType_SHININESS,
        Assimp.aiTextureType_DISPLACEMENT,
        Assimp.aiTextureType_LIGHTMAP,
        Assimp.aiTextureType_BASE_COLOR,
        Assimp.aiTextureType_METALNESS,
        Assimp.aiTextureType_DIFFUSE_ROUGHNESS,
        Assimp.aiTextureType_AMBIENT_OCCLUSION,
        Assimp.aiTextureType_UNKNOWN,
    };

    private AssimpModelReader() {}

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Runs a full Assimp import and returns a {@link ModelData}.
     * Never throws; errors are captured in {@link ModelData#error}.
     *
     * @param item      item to import
     * @param cancelled token; returns early if set
     */
    public static ModelData read(QuickViewItem item, AtomicBoolean cancelled) {
        ModelStats stats = new ModelStats();

        if (item.path() == null) {
            return fail("Assimp requires a real file on disk; stream-only items are not supported.", stats);
        }

        long sizeBytes = item.sizeBytes();
        if (sizeBytes > MAX_FILE_BYTES) {
            return fail(String.format(
                    "File too large (%.1f MB); limit is 250 MB.",
                    sizeBytes / 1_048_576.0), stats);
        }

        int flags = Assimp.aiProcess_Triangulate
                  | Assimp.aiProcess_JoinIdenticalVertices
                  | Assimp.aiProcess_SortByPType
                  | Assimp.aiProcess_GenSmoothNormals  // no-op if normals present
                  | Assimp.aiProcess_PreTransformVertices; // bake node transforms into vertex data

        AIScene scene = Assimp.aiImportFile(
                item.path().toAbsolutePath().toString(), flags);

        if (scene == null) {
            String err = Assimp.aiGetErrorString();
            return fail("Assimp import failed: " +
                    (err != null && !err.isBlank() ? err : "unknown error"), stats);
        }

        try {
            Path modelDir = item.path().getParent();
            if (modelDir == null) modelDir = item.path();
            return buildModelData(scene, stats, cancelled, modelDir);
        } finally {
            Assimp.aiReleaseImport(scene); // always release native memory
        }
    }

    // ── Model data extraction ─────────────────────────────────────────────────

    private static ModelData buildModelData(AIScene scene, ModelStats stats,
                                            AtomicBoolean cancelled, Path modelDir) {
        int meshCount = scene.mNumMeshes();
        stats.setMeshCount(meshCount);
        stats.setMaterialCount(scene.mNumMaterials());

        boolean skipBBox = meshCount > MAX_MESH_COUNT;
        if (skipBBox) {
            stats.getWarnings().add(String.format(
                    "Mesh count (%,d) exceeds limit (%,d); bounding box and 3D view skipped.",
                    meshCount, MAX_MESH_COUNT));
        }

        // ── Pre-compute material → diffuse texture path ───────────────────────
        String[] materialTexPaths = buildMaterialTexturePaths(scene);

        // ── Texture loading state ─────────────────────────────────────────────
        List<TextureData>    textures      = new ArrayList<>();
        Map<String, Integer> texIndexByPath = new HashMap<>();

        // ── Per-mesh extraction ───────────────────────────────────────────────
        List<MeshData> meshes       = new ArrayList<>(Math.min(meshCount, MAX_MESH_COUNT));
        long totalVertices          = 0;
        long totalIndices           = 0;
        boolean vertexLimitHit      = false;
        boolean indexLimitHit       = false;

        var meshBuf = scene.mMeshes();
        if (meshBuf != null) {
            for (int i = 0; i < meshCount; i++) {
                if (cancelled.get()) {
                    return fail("Cancelled", stats);
                }

                AIMesh aiMesh = AIMesh.create(meshBuf.get(i));

                int nv = aiMesh.mNumVertices();
                int nf = aiMesh.mNumFaces();
                stats.setTotalVertices(stats.getTotalVertices() + nv);
                stats.setTotalFaces(stats.getTotalFaces() + nf);

                totalVertices += nv;
                totalIndices  += (long) nf * 3;

                if (!skipBBox) {
                    updateBBox(aiMesh, stats);
                }

                // Skip GPU upload if size limits are already exceeded.
                if (vertexLimitHit || indexLimitHit) continue;
                if (totalVertices > MAX_VERTICES) {
                    vertexLimitHit = true;
                    stats.getWarnings().add(String.format(
                            "Total vertex count (%,d) exceeds limit (%,d). "
                            + "3D view is limited to meshes before this point.",
                            totalVertices, MAX_VERTICES));
                    continue;
                }
                if (totalIndices > MAX_INDICES) {
                    indexLimitHit = true;
                    stats.getWarnings().add(String.format(
                            "Total index count (%,d) exceeds limit (%,d). "
                            + "3D view is limited to meshes before this point.",
                            totalIndices, MAX_INDICES));
                    continue;
                }

                // Resolve diffuse texture for this mesh's material.
                int matIdx       = aiMesh.mMaterialIndex();
                String texPath   = (matIdx >= 0 && matIdx < materialTexPaths.length)
                                   ? materialTexPaths[matIdx] : null;
                int textureIndex = -1;
                if (texPath != null) {
                    textureIndex = resolveTexture(
                            texPath, scene, modelDir, textures, texIndexByPath);
                }

                MeshData md = extractMesh(aiMesh, nv, nf, i, textureIndex);
                if (md != null) {
                    meshes.add(md);
                }
            }

            if (!skipBBox && meshCount > 0 && stats.getMinX() != Float.MAX_VALUE) {
                stats.setHasBoundingBox(true);
                stats.computeBoundingSphere();
            }
        }

        // ── Material / texture extraction (for stats display) ─────────────────
        var matBuf = scene.mMaterials();
        if (matBuf != null) {
            Set<String> seenPaths = new LinkedHashSet<>();
            for (int i = 0; i < scene.mNumMaterials(); i++) {
                if (cancelled.get()) break;
                AIMaterial mat = AIMaterial.create(matBuf.get(i));
                extractTexturePaths(mat, seenPaths);
            }
            stats.getTextures().addAll(seenPaths);
        }

        // ── Build result ──────────────────────────────────────────────────────
        float cx = stats.getCenterX(), cy = stats.getCenterY(), cz = stats.getCenterZ();
        float radius = Math.max(stats.getBoundingRadius(), 0.01f);

        if (!stats.isHasBoundingBox()) {
            // No valid AABB (e.g., model too large for bbox scan).
            cx = cy = cz = 0f;
            radius = 1f;
        }

        return new ModelData(stats, meshes, textures, cx, cy, cz, radius);
    }

    // ── Texture helpers ───────────────────────────────────────────────────────

    /**
     * Builds a per-material array of diffuse texture paths.
     * Index corresponds to Assimp material index; value is {@code null} if
     * the material has no diffuse texture.
     */
    private static String[] buildMaterialTexturePaths(AIScene scene) {
        int matCount = scene.mNumMaterials();
        String[] paths = new String[matCount];
        var matBuf = scene.mMaterials();
        if (matBuf == null) return paths;

        for (int i = 0; i < matCount; i++) {
            AIMaterial mat = AIMaterial.create(matBuf.get(i));
            int count = Assimp.aiGetMaterialTextureCount(mat, Assimp.aiTextureType_DIFFUSE);
            if (count <= 0) continue;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                AIString pathStr = AIString.calloc(stack);
                int rc = Assimp.aiGetMaterialTexture(
                        mat, Assimp.aiTextureType_DIFFUSE, 0, pathStr,
                        (IntBuffer) null, null, null, null, null, null);
                if (rc == Assimp.aiReturn_SUCCESS) {
                    String tex = pathStr.dataString();
                    if (tex != null && !tex.isBlank()) paths[i] = tex;
                }
            }
        }
        return paths;
    }

    /**
     * Returns the index of {@code path} in {@code textures}, loading it if
     * not already cached.  Returns {@code -1} if loading fails.
     */
    private static int resolveTexture(String path, AIScene scene, Path modelDir,
                                      List<TextureData> textures,
                                      Map<String, Integer> texIndexByPath) {
        if (texIndexByPath.containsKey(path)) return texIndexByPath.get(path);

        TextureData td = null;
        if (path.startsWith("*")) {
            // Embedded texture — path is "*N" where N is the index.
            try {
                int embIdx = Integer.parseInt(path.substring(1));
                td = loadEmbeddedTexture(scene, embIdx);
            } catch (Exception e) {
                log.warn("Failed to load embedded texture '{}': {}", path, e.getMessage());
            }
        } else {
            try {
                Path texPath = modelDir.resolve(path.replace('\\', '/'));
                td = loadFileTexture(texPath);
            } catch (Exception e) {
                log.warn("Failed to load texture file '{}': {}", path, e.getMessage());
            }
        }

        int idx;
        if (td != null) {
            idx = textures.size();
            textures.add(td);
        } else {
            idx = -1;
        }
        texIndexByPath.put(path, idx);
        return idx;
    }

    private static TextureData loadFileTexture(Path path) throws Exception {
        BufferedImage img = ImageIO.read(path.toFile());
        if (img == null) throw new IOException("ImageIO could not decode: " + path);
        return decodeBufferedImage(img);
    }

    private static TextureData loadEmbeddedTexture(AIScene scene, int idx) throws Exception {
        int numTextures = scene.mNumTextures();
        if (idx < 0 || idx >= numTextures)
            throw new IOException("Embedded texture index out of range: " + idx);
        var texBuf = scene.mTextures();
        if (texBuf == null) throw new IOException("Scene has no embedded textures");

        AITexture tex = AITexture.create(texBuf.get(idx));

        if (tex.mHeight() == 0) {
            // Compressed image (PNG/JPG/…) — mWidth() is the byte count.
            int byteCount = tex.mWidth();
            ByteBuffer rawBuf = MemoryUtil.memByteBuffer(tex.pcData().address(), byteCount);
            byte[] bytes = new byte[byteCount];
            rawBuf.get(bytes);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null)
                throw new IOException("ImageIO could not decode embedded compressed texture " + idx);
            return decodeBufferedImage(img);
        } else {
            // Uncompressed BGRA texels.
            int w = tex.mWidth(), h = tex.mHeight();
            AITexel.Buffer pcData = tex.pcData();
            byte[] pixels = new byte[w * h * 4];
            for (int row = 0; row < h; row++) {
                int srcRow = h - 1 - row; // flip vertically
                for (int col = 0; col < w; col++) {
                    AITexel texel = pcData.get(srcRow * w + col);
                    int base = (row * w + col) * 4;
                    pixels[base]     = texel.r();
                    pixels[base + 1] = texel.g();
                    pixels[base + 2] = texel.b();
                    pixels[base + 3] = texel.a();
                }
            }
            return new TextureData(w, h, pixels);
        }
    }

    /**
     * Converts a {@link BufferedImage} to a bottom-row-first RGBA byte array
     * suitable for {@code glTexImage2D}.  Caps dimensions at {@link #MAX_TEX_DIM}.
     */
    private static TextureData decodeBufferedImage(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();

        // Scale down if either dimension exceeds the limit.
        if (w > MAX_TEX_DIM || h > MAX_TEX_DIM) {
            float scale = Math.min((float) MAX_TEX_DIM / w, (float) MAX_TEX_DIM / h);
            int nw = Math.max(1, (int)(w * scale));
            int nh = Math.max(1, (int)(h * scale));
            BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(img, 0, 0, nw, nh, null);
            g2.dispose();
            img = scaled;
            w   = nw;
            h   = nh;
        }

        // Extract RGBA bytes, flipping vertically (row 0 = bottom in OpenGL).
        byte[] pixels = new byte[w * h * 4];
        for (int row = 0; row < h; row++) {
            int srcRow = h - 1 - row;
            for (int col = 0; col < w; col++) {
                int argb = img.getRGB(col, srcRow);
                int base = (row * w + col) * 4;
                pixels[base]     = (byte)((argb >> 16) & 0xFF); // R
                pixels[base + 1] = (byte)((argb >>  8) & 0xFF); // G
                pixels[base + 2] = (byte)( argb        & 0xFF); // B
                pixels[base + 3] = (byte)((argb >> 24) & 0xFF); // A
            }
        }
        return new TextureData(w, h, pixels);
    }

    // ── Mesh extraction ───────────────────────────────────────────────────────

    /** Extracts positions, normals, UVs, and triangle indices into Java arrays. */
    private static MeshData extractMesh(AIMesh aiMesh, int nv, int nf,
                                        int meshIndex, int textureIndex) {
        AIVector3D.Buffer posBuf  = aiMesh.mVertices();
        AIVector3D.Buffer normBuf = aiMesh.mNormals();

        if (posBuf == null || nv == 0 || nf == 0) return null;

        float[] positions = new float[nv * 3];
        float[] normals   = new float[nv * 3];

        for (int v = 0; v < nv; v++) {
            AIVector3D p = posBuf.get(v);
            int base = v * 3;
            positions[base]     = p.x();
            positions[base + 1] = p.y();
            positions[base + 2] = p.z();

            if (normBuf != null) {
                AIVector3D n = normBuf.get(v);
                normals[base]     = n.x();
                normals[base + 1] = n.y();
                normals[base + 2] = n.z();
            } else {
                // Fallback: flat up-normal (aiProcess_GenSmoothNormals should prevent this)
                normals[base + 1] = 1f;
            }
        }

        // UV channel 0 — Assimp uses vec3 for UVs; we only need x,y.
        AIVector3D.Buffer uvBuf = aiMesh.mTextureCoords(0);
        float[] uvs = null;
        if (uvBuf != null) {
            uvs = new float[nv * 2];
            for (int v = 0; v < nv; v++) {
                AIVector3D uv = uvBuf.get(v);
                uvs[v * 2]     = uv.x();
                uvs[v * 2 + 1] = uv.y();
            }
        }

        // Face indices — Assimp guarantees triangles after aiProcess_Triangulate.
        int[] indices  = new int[nf * 3];
        var   faceBuf  = aiMesh.mFaces();
        int   idxWrite = 0;
        for (int f = 0; f < nf; f++) {
            var face = faceBuf.get(f);
            if (face.mNumIndices() != 3) continue; // skip degenerate faces
            var idxBuf = face.mIndices();
            indices[idxWrite++] = idxBuf.get(0);
            indices[idxWrite++] = idxBuf.get(1);
            indices[idxWrite++] = idxBuf.get(2);
        }

        float[] col = PALETTE[meshIndex % PALETTE.length];
        return new MeshData(positions, normals, uvs, indices, nv, nf,
                            col[0], col[1], col[2], textureIndex);
    }

    private static void updateBBox(AIMesh mesh, ModelStats stats) {
        AIVector3D.Buffer verts = mesh.mVertices();
        if (verts == null) return;
        int n = mesh.mNumVertices();
        for (int v = 0; v < n; v++) {
            AIVector3D vtx = verts.get(v);
            float x = vtx.x(), y = vtx.y(), z = vtx.z();
            if (x < stats.getMinX()) stats.setMinX(x);
            if (y < stats.getMinY()) stats.setMinY(y);
            if (z < stats.getMinZ()) stats.setMinZ(z);
            if (x > stats.getMaxX()) stats.setMaxX(x);
            if (y > stats.getMaxY()) stats.setMaxY(y);
            if (z > stats.getMaxZ()) stats.setMaxZ(z);
        }
    }

    private static void extractTexturePaths(AIMaterial mat, Set<String> out) {
        for (int type : TEXTURE_TYPES) {
            int count = Assimp.aiGetMaterialTextureCount(mat, type);
            for (int j = 0; j < count; j++) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    AIString pathStr = AIString.calloc(stack);
                    int rc = Assimp.aiGetMaterialTexture(
                            mat, type, j, pathStr,
                            (IntBuffer) null, null, null, null, null, null);
                    if (rc == Assimp.aiReturn_SUCCESS) {
                        String tex = pathStr.dataString();
                        if (tex != null && !tex.isBlank()) {
                            out.add(tex);
                        }
                    }
                }
            }
        }
    }

    private static ModelData fail(String msg, ModelStats stats) {
        stats.getWarnings().add(msg);
        return new ModelData(msg, stats);
    }
}
