# 3D Model Quick Viewer

A [Nuclr Commander](https://nuclr.dev) plugin that renders a rich read-only statistics panel for 3D model files directly in the Quick View pane — powered by [LWJGL Assimp](https://www.lwjgl.org/) with no external tools or network access required.

---

## Preview

| Section | What you see |
|---|---|
| **Model Statistics** | Mesh count, total vertex count, total face count (triangulated), material count, file size, last-modified timestamp |
| **Bounding Box** | Per-axis Min / Max / Size, computed by iterating all vertices |
| **Textures** | Deduplicated list of every texture path referenced across all materials (diffuse, normal, specular, PBR channels, …) |
| **Warnings** | Import errors, size-limit notices, missing native library message |

---

## Supported formats

Assimp supports [dozens of formats](https://assimp.org/index.php/downloads). The plugin activates for the following extensions by default:

| Extension | Format |
|---|---|
| `.fbx` | Autodesk FBX |
| `.obj` | Wavefront OBJ |
| `.gltf` / `.glb` | glTF 2.0 (text and binary) |
| `.dae` | Collada |
| `.3ds` | Autodesk 3DS Max |
| `.ply` | Stanford PLY |
| `.stl` | Stereolithography |

---

## Installation

Copy the signed plugin archive and its detached signature into the Nuclr Commander `plugins/` directory:

```
quick-view-3d-1.0.0.zip
quick-view-3d-1.0.0.zip.sig
```

Nuclr Commander verifies the RSA-SHA256 signature against `nuclr-cert.pem` on load. The plugin is active immediately — no restart required.

> **Native libraries** — LWJGL extracts the correct Assimp native (`.dll` / `.so` / `.dylib`) from the bundled JARs into a system temp directory on first use. No manual installation is needed. If extraction fails the panel shows a clear message instead of crashing.

---

## Building

Prerequisites: **Java 21+**, **Maven 3.9+**, and the `plugins-sdk` installed locally (`mvn install` in `plugins-sdk/`).

```bash
# Compile, test, package and sign
mvn clean verify -Djarsigner.storepass=<keystore-password>

# Artifacts produced in target/
#   quick-view-3d-1.0.0.zip      — plugin archive
#   quick-view-3d-1.0.0.zip.sig  — detached RSA-SHA256 signature
```

The signing step requires the keystore at `C:/nuclr/key/nuclr-signing.p12` with alias `nuclr`.

### Quick deploy to local commander

```bat
deploy.bat
```

Runs `mvn clean verify` then copies both artifacts into `C:\nuclr\sources\commander\plugins\`.

---

## How it works

### Assimp import

The file is parsed with the following post-processing flags:

| Flag | Effect |
|---|---|
| `aiProcess_Triangulate` | Converts all polygons to triangles; face count reflects triangle count |
| `aiProcess_JoinIdenticalVertices` | Deduplicates vertices; reported vertex count reflects unique positions |
| `aiProcess_SortByPType` | Separates mixed-primitive meshes so counts are consistent |

### Statistics extraction

- **Mesh / vertex / face counts** — accumulated over all meshes after post-processing.
- **Bounding box** — computed by iterating every vertex across all meshes and tracking per-axis min/max. Skipped automatically when the model contains more than 10 000 meshes.
- **Texture references** — `aiGetMaterialTextureCount` + `aiGetMaterialTexture` is called for every texture type on every material. Paths are deduplicated (insertion-ordered) across the whole scene.

### Safety limits

| Guard | Limit |
|---|---|
| Maximum file size | 250 MB — larger files show a warning; Assimp is never called |
| Maximum mesh count for bounding box | 10 000 — exceeded models still report counts but skip per-vertex AABB |
| Native library unavailable | Panel shows a friendly message; host application is never crashed |

### Asynchronous loading

All I/O and Assimp work runs on a **virtual thread** (`Thread.ofVirtual()`). The Swing EDT is never blocked. The panel shows a "Loading…" state immediately; a generation counter ensures that only the most recent request can update the UI, so rapid file switching never produces stale results.

`aiReleaseImport` is always called in a `finally` block — native scene memory is freed even if parsing fails mid-way.

---

## Plugin manifest

```json
{
  "id":      "dev.nuclr.plugin.core.quickviewer.3d",
  "name":    "3D Model Quick Viewer",
  "version": "1.0.0",
  "type":    "Official",
  "quickViewProviders": [
    "dev.nuclr.plugin.core.assimp.AssimpModelQuickViewProvider"
  ]
}
```

---

## Source layout

```
src/
├── main/java/dev/nuclr/plugin/core/assimp/
│   ├── AssimpModelQuickViewProvider.java  # QuickViewProvider entry point
│   ├── AssimpModelPanel.java              # Swing UI panel + async load logic
│   └── ModelStats.java                   # Parsed statistics DTO
└── main/resources/
    └── plugin.json
```

---

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| `org.lwjgl:lwjgl` | 3.3.4 | LWJGL core runtime |
| `org.lwjgl:lwjgl-assimp` | 3.3.4 | Java bindings for Assimp |
| lwjgl natives | 3.3.4 | Platform natives for Windows x64, Linux x64, macOS x64, macOS ARM64 |
| `dev.nuclr:plugins-sdk` | 1.0.0 | Nuclr plugin interfaces |

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).
