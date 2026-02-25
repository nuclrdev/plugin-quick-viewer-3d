package dev.nuclr.plugin.core.assimp.model;

import java.util.Collections;
import java.util.List;

import dev.nuclr.plugin.core.assimp.ModelStats;

/**
 * Result of one Assimp import pass, produced on a background thread by
 * {@code AssimpModelReader} and consumed on the EDT / GL thread.
 *
 * <p>Either {@code error} is non-null (import failed) or {@code meshes} is
 * non-empty (import succeeded).  {@code stats} is always populated.
 */
public final class ModelData {

    /** null on success; human-readable error message on failure. */
    public final String error;

    /** Extracted metadata (mesh count, vertex count, AABB, textures, …). */
    public final ModelStats stats;

    /** Per-mesh GPU-ready buffers; empty on error. */
    public final List<MeshData> meshes;

    /** Bounding-sphere centre — used to position the orbit-camera target. */
    public final float centerX, centerY, centerZ;

    /**
     * Bounding-sphere radius — used to set camera distance so the whole model
     * fits in the viewport.
     */
    public final float boundingRadius;

    /** Success path. */
    public ModelData(ModelStats stats, List<MeshData> meshes,
                     float centerX, float centerY, float centerZ,
                     float boundingRadius) {
        this.error          = null;
        this.stats          = stats;
        this.meshes         = Collections.unmodifiableList(meshes);
        this.centerX        = centerX;
        this.centerY        = centerY;
        this.centerZ        = centerZ;
        this.boundingRadius = boundingRadius;
    }

    /** Failure path — no renderable data. */
    public ModelData(String error, ModelStats stats) {
        this.error          = error;
        this.stats          = stats;
        this.meshes         = List.of();
        this.centerX        = 0;
        this.centerY        = 0;
        this.centerZ        = 0;
        this.boundingRadius = 1;
    }

    public boolean hasError() {
        return error != null;
    }
}
