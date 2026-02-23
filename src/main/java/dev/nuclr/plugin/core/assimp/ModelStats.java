package dev.nuclr.plugin.core.assimp;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * Immutable-ish DTO holding statistics extracted from a 3D model via Assimp.
 * Populated on a background thread; read on the EDT only after the load completes.
 */
@Data
public class ModelStats {

    private int meshCount;
    private long totalVertices;
    private long totalFaces;
    private int materialCount;

    /** Deduplicated, ordered list of referenced texture paths. */
    private final List<String> textures = new ArrayList<>();

    // Bounding box — only valid when hasBoundingBox == true
    private float minX = Float.MAX_VALUE;
    private float minY = Float.MAX_VALUE;
    private float minZ = Float.MAX_VALUE;
    private float maxX = -Float.MAX_VALUE;
    private float maxY = -Float.MAX_VALUE;
    private float maxZ = -Float.MAX_VALUE;
    private boolean hasBoundingBox;

    /** Non-fatal warnings collected during parsing (missing textures, size limits, etc.). */
    private final List<String> warnings = new ArrayList<>();
}
