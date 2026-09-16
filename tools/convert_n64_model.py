#!/usr/bin/env python3
"""Convert the Sketchfab "N64 Controller" glTF into the mesh used by the Quest 3D controller.

Usage: tools/convert_n64_model.py <folder with scene.gltf> <output .bin>

Output format (little endian): magic "N64M", uint32 version (1), uint32 vertex count, then per vertex
9 float32: position xyz (meters), normal xyz, uv, part id. Triangles, not indexed.

Model space: X right, Y out of the button face, Z towards the player (the grips), origin at the
center of the controller body. Part ids must match controller_model.cpp.
"""
import json
import math
import struct
import sys
from pathlib import Path

# Real N64 controller width in meters
TARGET_WIDTH = 0.155

PART_BODY, PART_DPAD_R, PART_DPAD_L, PART_DPAD_D, PART_DPAD_U = 0, 1, 2, 3, 4
PART_START, PART_Z, PART_B, PART_A = 5, 6, 7, 8
PART_C_R, PART_C_L, PART_C_D, PART_C_U = 9, 10, 11, 12
PART_R, PART_L = 13, 14
PART_STICK, PART_STICK_WELL, PART_DPAD_CENTER = 15, 16, 17

MESH_PARTS = {
    "ControllerBase": PART_BODY,
    "Cartridge": PART_BODY,
    "Z": PART_Z,
    "BlueButton": PART_A,
    "GreenButton": PART_B,
    "MiddleButton": PART_START,
    # C buttons are named after their position: X left, B right, Y down, A up
    "X": PART_C_L,
    "B": PART_C_R,
    "Y": PART_C_D,
    "A": PART_C_U,
    "Joystick_grip": PART_STICK,
    "JoySTICK": PART_STICK,
    "Joystick_base": PART_STICK_WELL,
    "L_R": None,    # split by side
    "D_pad": None,  # split by direction
}


def mat_mul(a, b):
    return [sum(a[k * 4 + row] * b[col * 4 + k] for k in range(4)) for col in range(4) for row in range(4)]


def node_matrix(node):
    if "matrix" in node:
        return node["matrix"]
    tx, ty, tz = node.get("translation", [0, 0, 0])
    x, y, z, w = node.get("rotation", [0, 0, 0, 1])
    sx, sy, sz = node.get("scale", [1, 1, 1])
    rotation = [1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0,
                2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0,
                2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0,
                0, 0, 0, 1]
    scale = [sx, 0, 0, 0, 0, sy, 0, 0, 0, 0, sz, 0, 0, 0, 0, 1]
    translation = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, tx, ty, tz, 1]
    return mat_mul(translation, mat_mul(rotation, scale))


def normal_matrix(m):
    """Inverse transpose of the upper 3x3, as a function."""
    a = [[m[0], m[4], m[8]], [m[1], m[5], m[9]], [m[2], m[6], m[10]]]
    det = (a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1])
           - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
           + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]))
    # Cofactor matrix divided by det equals the inverse transpose
    c = [[(a[1][1] * a[2][2] - a[1][2] * a[2][1]), -(a[1][0] * a[2][2] - a[1][2] * a[2][0]), (a[1][0] * a[2][1] - a[1][1] * a[2][0])],
         [-(a[0][1] * a[2][2] - a[0][2] * a[2][1]), (a[0][0] * a[2][2] - a[0][2] * a[2][0]), -(a[0][0] * a[2][1] - a[0][1] * a[2][0])],
         [(a[0][1] * a[1][2] - a[0][2] * a[1][1]), -(a[0][0] * a[1][2] - a[0][2] * a[1][0]), (a[0][0] * a[1][1] - a[0][1] * a[1][0])]]

    def apply(n):
        v = [sum(c[r][k] * n[k] for k in range(3)) / det for r in range(3)]
        length = math.sqrt(sum(x * x for x in v)) or 1.0
        return [x / length for x in v]
    return apply


def main():
    source = Path(sys.argv[1])
    output = Path(sys.argv[2])
    gltf = json.loads((source / "scene.gltf").read_text())
    buffer_path = source / gltf["buffers"][0]["uri"]
    if not buffer_path.exists():
        # The Sketchfab archive ships "cene.bin" while the glTF references "scene.bin"
        buffer_path = next(source.glob("*.bin"))
    data = buffer_path.read_bytes()

    def accessor(index):
        acc = gltf["accessors"][index]
        view = gltf["bufferViews"][acc["bufferView"]]
        size = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[acc["type"]]
        offset = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
        stride = view.get("byteStride", 4 * size)
        fmt = {5126: "f", 5123: "H", 5125: "I", 5121: "B"}[acc["componentType"]] * size
        step = stride if acc["componentType"] == 5126 else struct.calcsize("<" + fmt)
        return [struct.unpack_from("<" + fmt, data, offset + i * step) for i in range(acc["count"])]

    triangles = []  # (part, [(pos, normal, uv) x3])

    def walk(node_index, parent):
        node = gltf["nodes"][node_index]
        matrix = mat_mul(parent, node_matrix(node))
        if "mesh" in node:
            mesh = gltf["meshes"][node["mesh"]]
            base_name = mesh["name"].split("_standardSurface")[0]
            if base_name not in MESH_PARTS:
                raise SystemExit(f"Unknown mesh {mesh['name']}")
            transform_normal = normal_matrix(matrix)
            for primitive in mesh["primitives"]:
                attributes = primitive["attributes"]
                positions = accessor(attributes["POSITION"])
                normals = accessor(attributes["NORMAL"])
                uvs = accessor(attributes["TEXCOORD_0"])
                indices = [i[0] for i in accessor(primitive["indices"])] if "indices" in primitive else list(range(len(positions)))
                for t in range(0, len(indices), 3):
                    corners = []
                    for i in indices[t:t + 3]:
                        p = positions[i]
                        world = [matrix[0] * p[0] + matrix[4] * p[1] + matrix[8] * p[2] + matrix[12],
                                 matrix[1] * p[0] + matrix[5] * p[1] + matrix[9] * p[2] + matrix[13],
                                 matrix[2] * p[0] + matrix[6] * p[1] + matrix[10] * p[2] + matrix[14]]
                        corners.append((world, transform_normal(normals[i]), uvs[i]))
                    triangles.append((base_name, corners))
        for child in node.get("children", []):
            walk(child, matrix)

    identity = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
    for root in gltf["scenes"][gltf.get("scene", 0)]["nodes"]:
        walk(root, identity)

    body = [c[0] for name, corners in triangles if name == "ControllerBase" for c in corners]
    minimum = [min(p[i] for p in body) for i in range(3)]
    maximum = [max(p[i] for p in body) for i in range(3)]
    center = [(minimum[i] + maximum[i]) / 2 for i in range(3)]
    scale = TARGET_WIDTH / (maximum[0] - minimum[0])

    dpad = [c[0] for name, corners in triangles if name == "D_pad" for c in corners]
    dpad_center = [(min(p[i] for p in dpad) + max(p[i] for p in dpad)) / 2 for i in range(2)]
    dpad_half = (max(p[0] for p in dpad) - min(p[0] for p in dpad)) / 2

    def part_for(name, corners):
        part = MESH_PARTS[name]
        if part is not None:
            return part
        cx = sum(c[0][0] for c in corners) / 3
        cy = sum(c[0][1] for c in corners) / 3
        if name == "L_R":
            return PART_L if cx < 0 else PART_R
        dx, dy = cx - dpad_center[0], cy - dpad_center[1]
        if max(abs(dx), abs(dy)) < dpad_half * 0.3:
            return PART_DPAD_CENTER
        if abs(dx) > abs(dy):
            return PART_DPAD_R if dx > 0 else PART_DPAD_L
        # glTF +Y is the far edge of the controller, i.e. "up" on the D-pad
        return PART_DPAD_U if dy > 0 else PART_DPAD_D

    # glTF: X right, Y towards the far edge, Z out of the face -> ours: (x, z, -y)
    def convert(v, translate):
        x, y, z = (v[i] - center[i] for i in range(3)) if translate else v
        return (x, z, -y)

    out = bytearray(b"N64M")
    out += struct.pack("<II", 1, len(triangles) * 3)
    for name, corners in triangles:
        part = part_for(name, corners)
        for position, normal, uv in corners:
            px, py, pz = convert(position, True)
            nx, ny, nz = convert(normal, False)
            out += struct.pack("<9f", px * scale, py * scale, pz * scale, nx, ny, nz, uv[0], uv[1], float(part))

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(out)
    print(f"{len(triangles)} triangles, scale {scale:.4f}, wrote {output} ({len(out)} bytes)")


if __name__ == "__main__":
    main()
