"""Headless Blender -> GLB converter for the Nuclr 3D quick-viewer plugin.

Invoked as::

    blender --background --factory-startup --disable-autoexec \
            --python nuclr_export_glb.py -- <mode> <source> <destination>

``<mode>`` is one of ``blend``, ``usd`` or ``abc``.

The output is a binary glTF with textures embedded, so the converted file is
self-contained: Assimp resolves every texture from the GLB itself and never has
to look back at the source folder, which may not even be on this machine.

Prints ``NUCLR_EXPORT_OK`` on success. On failure prints
``NUCLR_EXPORT_ERROR: <message>`` and exits non-zero.
"""

import sys
import traceback

import bpy

OK_MARKER = "NUCLR_EXPORT_OK"
ERROR_MARKER = "NUCLR_EXPORT_ERROR"

# Object types the glTF exporter can evaluate into meshes.
GEOMETRY_TYPES = {"MESH", "CURVE", "SURFACE", "META", "FONT"}

# Everything we would like to hand the exporter. The property set of
# export_scene.gltf shifts between Blender releases, so this is filtered against
# the operator's actual RNA properties before the call rather than assumed.
EXPORT_OPTIONS = {
    "export_format": "GLB",        # one self-contained file, textures included
    "export_apply": True,          # bake modifiers: preview what the artist sees
    "export_yup": True,            # Blender is Z-up, glTF is Y-up
    "export_materials": "EXPORT",
    "export_cameras": False,
    "export_lights": False,
    "export_animations": False,    # the viewer renders a single static pose
    "export_extras": False,
    "export_skins": False,
    # Assimp cannot read Draco-compressed primitives. Never let this default on.
    "export_draco_mesh_compression_enable": False,
}


def fail(message):
    print("%s: %s" % (ERROR_MARKER, message))
    sys.stdout.flush()
    sys.exit(1)


def script_args():
    argv = sys.argv
    if "--" not in argv:
        fail("no arguments passed after '--'")
    args = argv[argv.index("--") + 1:]
    if len(args) != 3:
        fail("expected <mode> <source> <destination>, got %d argument(s)" % len(args))
    return args


def load_scene(mode, source):
    if mode == "blend":
        # load_ui=False keeps the saved window layout out of a headless run.
        bpy.ops.wm.open_mainfile(filepath=source, load_ui=False)
        return

    # Imported formats land in an empty scene; the factory startup file would
    # otherwise contribute its default cube, camera and light to the preview.
    bpy.ops.wm.read_factory_settings(use_empty=True)

    if mode == "usd":
        importer = getattr(bpy.ops.wm, "usd_import", None)
        if importer is None:
            fail("this Blender build has no USD importer")
        importer(filepath=source)
    elif mode == "abc":
        importer = getattr(bpy.ops.wm, "alembic_import", None)
        if importer is None:
            fail("this Blender build has no Alembic importer")
        importer(filepath=source)
    else:
        fail("unknown mode '%s'" % mode)


def geometry_count():
    return sum(1 for obj in bpy.data.objects if obj.type in GEOMETRY_TYPES)


def supported_options(operator, options):
    """Drop the options this Blender's exporter does not declare."""
    try:
        declared = set(operator.get_rna_type().properties.keys())
    except Exception:
        return {}
    return {key: value for key, value in options.items() if key in declared}


def main():
    mode, source, destination = script_args()
    load_scene(mode, source)

    if geometry_count() == 0:
        fail("the scene contains no exportable geometry")

    options = supported_options(bpy.ops.export_scene.gltf, EXPORT_OPTIONS)
    options["filepath"] = destination

    result = bpy.ops.export_scene.gltf(**options)
    if "FINISHED" not in result:
        fail("glTF export returned %s" % sorted(result))

    print(OK_MARKER)
    sys.stdout.flush()


try:
    main()
except SystemExit:
    raise
except BaseException as exc:
    # The full trace goes to stderr, which the caller merges into its debug log.
    # The marker line carries only the exception message: a failed Blender
    # operator reads as "RuntimeError: Error: File format is not supported ...",
    # which is the part worth putting in front of someone previewing a file.
    traceback.print_exc()
    detail = " ".join(traceback.format_exception_only(type(exc), exc)).strip()
    fail(detail.replace("\n", " ") or repr(exc))
