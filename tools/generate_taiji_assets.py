#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Generate a new 16px black-and-white taiji, rotating once per eight seconds.

All 32 frames are evaluated from circles and inverse rotation on a pixel grid.
No downloaded images, fonts, Minecraft textures, or AI-generated art are used.
The vertical PNG strip and animation metadata drive the native item atlas, so
the inventory, held tool, and selection-screen item all use the same animation.
"""

import argparse
import hashlib
import json
import math
import struct
import sys
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SIZE = 16
FRAMES = 32
FRAME_TICKS = 5
TRANSPARENT = (0, 0, 0, 0)
BLACK = (0, 0, 0, 255)
WHITE = (255, 255, 255, 255)


def chunk(kind, content):
    return (struct.pack(">I", len(content)) + kind + content
            + struct.pack(">I", zlib.crc32(kind + content) & 0xFFFFFFFF))


def frame_pixels(frame):
    # Reverse the existing frame sequence while preserving frame zero exactly.
    angle = ((FRAMES - frame) % FRAMES) * math.tau / FRAMES
    cosine, sine = math.cos(angle), math.sin(angle)
    radius = 7.5
    rows = []
    for y in range(SIZE):
        row = []
        for x in range(SIZE):
            dx, dy = x + 0.5 - SIZE / 2, y + 0.5 - SIZE / 2
            distance = dx * dx + dy * dy
            if distance > radius * radius:
                color = TRANSPARENT
            else:
                rx = dx * cosine + dy * sine
                ry = -dx * sine + dy * cosine
                color = BLACK if rx < 0 else WHITE
                if rx * rx + (ry + radius / 2) ** 2 <= (radius / 2) ** 2:
                    color = WHITE
                if rx * rx + (ry - radius / 2) ** 2 <= (radius / 2) ** 2:
                    color = BLACK
                if rx * rx + (ry + radius / 2) ** 2 <= 1.3 ** 2:
                    color = BLACK
                if rx * rx + (ry - radius / 2) ** 2 <= 1.3 ** 2:
                    color = WHITE
                # The dark rim keeps the light half visible in light inventories.
                if distance > (radius - 0.65) ** 2:
                    color = BLACK
            row.append(color)
        rows.append(row)
    return rows


def taiji_png():
    frames = [frame_pixels(frame) for frame in range(FRAMES)]
    assert len({bytes(channel for row in frame for pixel in row for channel in pixel)
                for frame in frames}) == FRAMES, "旋轉影格不得重複或停留不動"
    raw = b"".join(b"\0" + bytes(channel for pixel in row for channel in pixel)
                   for frame in frames for row in frame)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE * FRAMES, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


def encode_json(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def outputs(root):
    assets = root / "src/main/resources/assets/yigua"
    return {
        assets / "textures/item/taiji_tool.png": taiji_png(),
        assets / "textures/item/taiji_tool.png.mcmeta": encode_json({"animation": {
            "width": SIZE, "height": SIZE, "frametime": FRAME_TICKS, "interpolate": False,
            "frames": list(range(FRAMES)),
        }}),
        assets / "models/item/taiji_tool.json": encode_json({
            "parent": "minecraft:item/generated", "textures": {"layer0": "yigua:item/taiji_tool"},
        }),
        assets / "items/taiji_tool.json": encode_json({
            "model": {"type": "minecraft:model", "model": "yigua:item/taiji_tool"},
        }),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="只核對，不改寫資源")
    parser.add_argument("--root", type=Path, default=ROOT)
    arguments = parser.parse_args()
    generated = outputs(arguments.root.resolve())
    for path, content in generated.items():
        if arguments.check:
            if not path.is_file() or path.read_bytes() != content:
                print(f"太極資源不一致：{path.relative_to(arguments.root.resolve())}", file=sys.stderr)
                return 1
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    texture = next(content for path, content in generated.items() if path.suffix == ".png")
    print(f"太極資源{'核對' if arguments.check else '生成'}完成：{len(generated)} 個檔案，"
          f"{SIZE}×{SIZE * FRAMES} PNG，{FRAMES} 個不同影格，每圈 {FRAMES * FRAME_TICKS / 20:g} 秒。")
    print("PNG SHA-256：" + hashlib.sha256(texture).hexdigest())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
