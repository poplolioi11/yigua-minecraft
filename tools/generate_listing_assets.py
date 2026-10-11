#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Reproduce an original 400px listing icon using only Python's standard library.

The six strokes use CastingEngine's Tai hexagram (bit 0 is the bottom line).
Coins and stalks are evaluated as simple original geometric pixel shapes.
There are no input images, fonts, downloaded media, Minecraft brand elements,
or image-generation model calls. The script and composition were authored
with generative AI; programmatic rendering does not mean human-drawn art or
platform approval. The asset is a CurseForge/Nexus submission candidate.
"""

import argparse
import hashlib
import json
import re
import struct
import sys
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SIZE = 400
GRID = 40
SCALE = SIZE // GRID
HEXAGRAM_NUMBER = 11
BACKGROUND = (22, 33, 28, 255)
PANEL = (30, 46, 37, 255)
INK = (243, 231, 198, 255)
STALK = (117, 137, 85, 255)
COPPER_EDGE = (139, 79, 43, 255)
COPPER = (183, 113, 57, 255)
COPPER_LIGHT = (222, 158, 85, 255)


def core_hexagram(root):
    source = (root / "src/main/java/art/yigua/core/CastingEngine.java").read_text(encoding="utf-8")
    masks = re.search(r'private static final int\[\] MASKS\s*=\s*\{([^}]+)\}', source)
    names = re.search(r'private static final String\[\] NAMES\s*=\s*\{([^}]+)\}', source)
    if masks is None or names is None:
        raise ValueError("核心六爻表格式已改變，請先核對發布圖示。")
    values = [int(value.strip()) for value in masks.group(1).split(",") if value.strip()]
    labels = re.findall(r'"([^\"]+)"', names.group(1))
    if len(values) != 64 or set(values) != set(range(64)) or len(labels) != 64:
        raise ValueError("核心不是完整六十四卦。")
    mask, name = values[HEXAGRAM_NUMBER - 1], labels[HEXAGRAM_NUMBER - 1]
    if (mask, name) != (7, "泰"):
        raise ValueError("第十一卦與既有泰卦筆畫不一致。")
    return mask, name


def icon_pixels(mask):
    pixels = [[BACKGROUND for _ in range(GRID)] for _ in range(GRID)]
    for y in range(GRID):
        for x in range(GRID):
            # A restrained inset pixel border, including stepped corners.
            if 2 <= x <= 37 and 2 <= y <= 37 and not (
                    (x <= 3 or x >= 36) and (y <= 3 or y >= 36)):
                pixels[y][x] = PANEL

    # The six-line diagram keeps the same solid/broken rule as game icons.
    for bottom_line in range(6):
        top = 29 - bottom_line * 4
        for y in range(top, top + 2):
            for x in range(6, 24):
                if mask & (1 << bottom_line) or not 13 <= x <= 16:
                    pixels[y][x] = INK

    # Three slender stalks slant together behind the three coins.
    for y in range(7, 34):
        for stalk in range(3):
            center = 27 + 1.8 * stalk + (33 - y) * 0.15
            for x in range(25, 36):
                if abs(x + 0.5 - center) <= 0.55:
                    pixels[y][x] = STALK

    for center_y in (12.5, 20.5, 28.5):
        center_x = 31.5
        for y in range(GRID):
            for x in range(GRID):
                dx, dy = x + 0.5 - center_x, y + 0.5 - center_y
                distance = dx * dx + dy * dy
                if distance > 3.7 ** 2:
                    continue
                color = COPPER_EDGE if distance > 3.15 ** 2 else COPPER_LIGHT
                if distance < 2.45 ** 2:
                    color = COPPER
                if abs(dx) <= 1 and abs(dy) <= 1:
                    color = PANEL
                pixels[y][x] = color
    return pixels


def chunk(kind, content):
    return (struct.pack(">I", len(content)) + kind + content
            + struct.pack(">I", zlib.crc32(kind + content) & 0xFFFFFFFF))


def encode_png(pixels, scale):
    rows = []
    for row in pixels:
        expanded = bytes(channel for pixel in row for _ in range(scale) for channel in pixel)
        rows.extend([b"\0" + expanded] * scale)
    size = len(pixels) * scale
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b""))


def verify_png(data, expected_size):
    """Read the encoded file, including every CRC and actual pixel row length."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("不是 PNG。")
    offset, compressed, header, kinds = 8, bytearray(), None, []
    while offset < len(data):
        length = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        content = data[offset + 8:offset + 8 + length]
        crc = struct.unpack_from(">I", data, offset + 8 + length)[0]
        if zlib.crc32(kind + content) & 0xFFFFFFFF != crc:
            raise ValueError("PNG 區塊校驗失敗。")
        kinds.append(kind)
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", content)
        elif kind == b"IDAT":
            compressed.extend(content)
        offset += length + 12
    if offset != len(data) or kinds != [b"IHDR", b"IDAT", b"IEND"]:
        raise ValueError("非預期 PNG 內容。")
    if header != (expected_size, expected_size, 8, 6, 0, 0, 0):
        raise ValueError("PNG 尺寸或像素格式錯誤。")
    rows = zlib.decompress(compressed)
    stride = 1 + expected_size * 4
    if len(rows) != stride * expected_size or any(rows[y * stride] != 0 for y in range(expected_size)):
        raise ValueError("PNG 列資料錯誤。")


def outputs(root):
    mask, name = core_hexagram(root)
    pixels = icon_pixels(mask)
    icon = encode_png(pixels, SCALE)
    preview = encode_png(pixels, 1)
    verify_png(icon, SIZE)
    verify_png(preview, GRID)
    manifest = {
        "schemaVersion": 1,
        "asset": "project-icon-400.png",
        "width": SIZE,
        "height": SIZE,
        "logicalGrid": [GRID, GRID],
        "rendering": "Original geometric pixels; nearest-neighbor integer enlargement; no input image or font.",
        "hexagram": {"number": HEXAGRAM_NUMBER, "name": name, "mask": mask, "bottomLineBit": 0},
        "authorship": "Composition and generator authored with generative AI; pixels computed deterministically. Not claimed as human-drawn.",
        "license": "MIT",
        "attribution": "poplolioi11 / Yigua yigua.art",
        "intendedUse": "CurseForge and Nexus submission candidate; no platform approval or Modrinth eligibility claimed.",
        "sha256": hashlib.sha256(icon).hexdigest(),
        "previewSha256": hashlib.sha256(preview).hexdigest(),
    }
    media = root / "docs/media"
    return {
        media / "project-icon-400.png": icon,
        media / "project-icon-40.png": preview,
        media / "listing-assets.json": (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="只核對已保存素材，不改寫")
    parser.add_argument("--root", type=Path, default=ROOT)
    arguments = parser.parse_args()
    root = arguments.root.resolve()
    generated = outputs(root)
    for path, content in generated.items():
        if arguments.check:
            if not path.is_file() or path.read_bytes() != content:
                print(f"發布素材不一致：{path.relative_to(root)}", file=sys.stderr)
                return 1
            if path.suffix == ".png":
                verify_png(path.read_bytes(), SIZE if "400" in path.stem else GRID)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    print(f"發布素材{'核對' if arguments.check else '生成'}完成：400×400 圖示、40×40 預覽與來源 manifest。")
    print("PNG SHA-256：" + hashlib.sha256(next(iter(generated.values()))).hexdigest())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
