package dev.nuclr.plugin.core.assimp;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.Assimp;
import org.lwjgl.system.MemoryStack;

import dev.nuclr.plugin.QuickViewItem;
import dev.nuclr.plugin.core.assimp.model.MeshData;
import dev.nuclr.plugin.core.assimp.model.ModelData;
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
 * </ul>
 */
@Slf4j
public final class AssimpModelReader {

    // ── Limits ────────────────────────────────────────────────────────────────

    static final long MAX_FILE_BYTES  = 250L * 1024 * 1024; // 250 MB
    static final long MAX_VERTICES    = 5_000_000L;
    static final long MAX_INDICES     = 15_000_000L;
    static final int  MAX_MESH_COUNT  = 10_000;

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
     * @param gen       generation counter; used by caller to detect stale results
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
            return buildModelData(scene, stats, cancelled);
        } finally {
            Assimp.aiReleaseImport(scene); // always release native memory
        }
    }

    // ── Model data extraction ─────────────────────────────────────────────────

    private static ModelData buildModelData(AIScene scene, ModelStats stats,
                                            AtomicBoolean cancelled) {
        int meshCount = scene.mNumMeshes();
        stats.setMeshCount(meshCount);
        stats.setMaterialCount(scene.mNumMaterials());

        boolean skipBBox = meshCount > MAX_MESH_COUNT;
        if (skipBBox) {
            stats.getWarnings().add(String.format(
                    "Mesh count (%,d) exceeds limit (%,d); bounding box and 3D view skipped.",
                    meshCount, MAX_MESH_COUNT));
        }

        // ── Per-mesh extraction ───────────────────────────────────────────────
        List<MeshData> meshes    = new ArrayList<>(Math.min(meshCount, MAX_MESH_COUNT));
        long totalVertices       = 0;
        long totalIndices        = 0;
        boolean vertexLimitHit   = false;
        boolean indexLimitHit    = false;

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

                MeshData md = extractMesh(aiMesh, nv, nf, i);
                if (md != null) {
                    meshes.add(md);
                }
            }

            if (!skipBBox && meshCount > 0 && stats.getMinX() != Float.MAX_VALUE) {
                stats.setHasBoundingBox(true);
                stats.computeBoundingSphere();
            }
        }

        // ── Material / texture extraction ─────────────────────────────────────
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

        return new ModelData(stats, meshes, cx, cy, cz, radius);
    }

    /** Extracts positions, normals, and triangle indices into Java arrays. */
    private static MeshData extractMesh(AIMesh aiMesh, int nv, int nf, int meshIndex) {
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
        return new MeshData(positions, normals, indices, nv, nf,
                            col[0], col[1], col[2]);
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
