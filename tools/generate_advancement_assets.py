#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Generate original six-line icons and Minecraft-native advancement resources.

The 64 icon masks and names are read from CastingEngine's exact arrays, not a
second hand-maintained hexagram table. Bit 0 is the bottom line; a set bit draws
one solid yang stroke, an unset bit draws two yin segments. These PNGs are new
program-generated diagrams, with no font, downloaded art or existing PNG input.

An advancement icon is minecraft:paper with its minecraft:item_model component
pointing at assets/yigua/items/hexagram_XX.json. It does not register a new item.
Minecraft 26.3 DisplayInfo uses ItemStackTemplate and DataComponents.ITEM_MODEL
stores Identifier.CODEC, so the model component is a namespaced string.

Each collectible criterion is minecraft:impossible, named "obtained". The server
grants it only for formal gameplay; all 64 hexagrams are direct children of one
root, so completion never requires a previous hexagram. The root's tick criterion
opens the tab without granting a collectible. Native visibility then includes all
64 uncollected slots; the narrowly scoped layout mixin places them in an 8x8 grid
in King Wen order, independent of Minecraft's unordered child set.
Secret challenge nodes have hidden=true and no rewards or extra client packets.
"""

import argparse
import hashlib
import json
import re
import struct
import sys
import zlib
from pathlib import Path

from generate_effect_assets import PROFILES, encode_json, read_json, require, validate_json


ROOT = Path(__file__).resolve().parent.parent
SIZE = 32
TRANSPARENT = (0, 0, 0, 0)
PAPER = (238, 229, 201, 255)
BORDER = (159, 137, 99, 255)
INK = (56, 45, 32, 255)

# All ordinary effect nodes have different original-game item icons. These are
# references only; no Minecraft item textures are copied into this repository.
EFFECT_ICONS = {
    "travel": "feather", "caution": "leather_boots",
    "focus": "iron_pickaxe", "fatigue": "wooden_pickaxe",
    "strength": "iron_sword", "restraint": "wooden_sword",
    "guard": "iron_chestplate", "frail": "leather_chestplate",
    "quick": "golden_sword", "slowhand": "cobweb",
    "luck": "emerald", "illluck": "poisonous_potato",
    "lightness": "rabbit_foot", "heaviness": "anvil",
    "landing": "hay_block", "fallrisk": "pointed_dripstone",
    "vitality": "apple", "depletion": "rotten_flesh",
    "leap": "slime_ball", "heavyfeet": "iron_boots",
    "steady": "shield", "exposed": "glass_pane",
    "fireward": "magma_cream", "embers": "blaze_powder",
    "reach": "fishing_rod", "nearreach": "stick",
    "breath": "turtle_helmet", "deepwork": "prismarine_shard",
    "drownedwork": "kelp", "closecombat": "trident",
    "direction": "compass",
}
EXTRA_EFFECTS = {
    "airloss": ("不宜戲水", "sponge", False, "正式起卦取得「不宜戲水」的遊戲效果。"),
    "secret_divine": ("神光降臨", "nether_star", True, "正式起卦遇見神光，獲得特殊裝備。"),
    "secret_thunder": ("天雷之劫", "lightning_rod", True, "正式起卦遇見罕見的致命天雷。"),
    "secret_undying": ("生生不息", "totem_of_undying", True, "正式起卦取得短暫的不死守護。"),
    "secret_five_qi": ("五氣朝元", "prismarine_crystals", True, "正式起卦遇見五色氣流匯聚的罕見異象。"),
    "secret_flowers": ("三花聚頂", "flowering_azalea", True, "正式起卦遇見三花聚頂的罕見異象。"),
    "secret_birds": ("百鳥朝鳳", "parrot_spawn_egg", True, "正式起卦遇見鳳凰飛舞、雙翼振動的罕見異象。"),
    "secret_rapport": ("萬物親和", "wheat", True, "正式起卦取得短暫的萬物親和。"),
    "secret_sea": ("分海", "heart_of_the_sea", True, "正式起卦取得短暫的分海之力。"),
    "secret_obstruction": ("山重阻路", "stone_bricks", True, "正式起卦遇見前路受阻的罕見異象。"),
}


def core_hexagrams(root):
    source = (root / "src/main/java/art/yigua/core/CastingEngine.java").read_text(encoding="utf-8")
    masks = re.search(r'private static final int\[\] MASKS\s*=\s*\{([^}]+)\}', source)
    names = re.search(r'private static final String\[\] NAMES\s*=\s*\{([^}]+)\}', source)
    require(masks is not None and names is not None, "CastingEngine 卦象表格式已變更，請核對圖示產生器。")
    values = [int(value.strip()) for value in masks.group(1).split(",") if value.strip()]
    labels = re.findall(r'"([^\"]+)"', names.group(1))
    require(len(values) == 64 and set(values) == set(range(64)) and len(labels) == 64,
            "CastingEngine 卦象或卦名不是完整六十四卦。")
    texts = read_json(root / "src/main/resources/data/yigua/texts/wuying-lines.json")
    text_names = {entry["number"]: entry["name"] for entry in texts["hexagrams"]}
    require(all(text_names.get(number) == name for number, name in enumerate(labels, 1)),
            "古籍資料的卦名與 CastingEngine 不一致。")
    return list(zip(values, labels))


def png_chunk(kind, content):
    return struct.pack(">I", len(content)) + kind + content + struct.pack(">I", zlib.crc32(kind + content) & 0xFFFFFFFF)


def hexagram_png(mask):
    pixels = [[TRANSPARENT for _ in range(SIZE)] for _ in range(SIZE)]

    def rectangle(left, top, right, bottom, color):
        for y in range(top, bottom + 1):
            for x in range(left, right + 1):
                pixels[y][x] = color

    rectangle(2, 2, 29, 29, BORDER)
    rectangle(3, 3, 28, 28, PAPER)
    for bottom_line in range(6):
        y = 24 - 4 * bottom_line
        if mask & (1 << bottom_line):
            rectangle(4, y, 27, y + 1, INK)
        else:
            rectangle(4, y, 12, y + 1, INK)
            rectangle(19, y, 27, y + 1, INK)
    raw = b"".join(b"\0" + bytes(channel for pixel in row for channel in pixel) for row in pixels)
    return (b"\x89PNG\r\n\x1a\n"
            + png_chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
            + png_chunk(b"IDAT", zlib.compress(raw, 9))
            + png_chunk(b"IEND", b""))


def advancement(title, description, icon, parent=None, hidden=False, root=False):
    display = {
        "icon": icon,
        "title": {"text": title},
        "description": {"text": description},
        "frame": "challenge" if hidden else "task",
        "show_toast": not root,
        "announce_to_chat": False,
        "hidden": hidden,
    }
    if root:
        display["background"] = "minecraft:gui/advancements/backgrounds/stone"
    result = {
        "display": display,
        "criteria": {"obtained": {"trigger": "minecraft:impossible"}},
        "requirements": [["obtained"]],
        "sends_telemetry_event": False,
    }
    if parent:
        result["parent"] = parent
    return result


def make_outputs(root):
    assets = root / "src/main/resources/assets/yigua"
    data = root / "src/main/resources/data/yigua/advancement"
    outputs = {}
    for number, (mask, name) in enumerate(core_hexagrams(root), 1):
        key = f"hexagram_{number:02}"
        outputs[assets / f"textures/item/{key}.png"] = hexagram_png(mask)
        outputs[assets / f"models/item/{key}.json"] = encode_json({
            "parent": "minecraft:item/generated", "textures": {"layer0": f"yigua:item/{key}"},
        })
        outputs[assets / f"items/{key}.json"] = encode_json({
            "model": {"type": "minecraft:model", "model": f"yigua:item/{key}"},
        })
        parent = "yigua:hexagrams/root"
        outputs[data / f"hexagrams/{number:02}.json"] = encode_json(advancement(
            f"第 {number} 卦・{name}", f"正式起卦取得本卦「{name}」，收入個人卦象圖鑑。",
            {"id": "minecraft:paper", "components": {"minecraft:item_model": f"yigua:{key}"}}, parent))
    hexagram_root = advancement(
        "一卦・六十四卦", "依文王卦序由左到右、由上到下排列；拖曳查看後續卦象。未取得亦保留位置，正式起卦才解鎖。",
        {"id": "minecraft:book"}, root=True)
    hexagram_root["criteria"]["obtained"]["trigger"] = "minecraft:tick"
    outputs[data / "hexagrams/root.json"] = encode_json(hexagram_root)
    outputs[data / "effects/root.json"] = encode_json(advancement(
        "一卦・遊戲效果", "記錄正式起卦遇過的效果。各項效果是易經題材的遊戲改編。",
        {"id": "minecraft:amethyst_shard"}, root=True))
    # airloss already has its separately named node in EXTRA_EFFECTS; do not add
    # a second node when its registered multiplier family joins PROFILES.
    ordinary_profiles = {key: value for key, value in PROFILES.items() if key not in EXTRA_EFFECTS}
    require(set(EFFECT_ICONS) == set(ordinary_profiles)
            and len(set(EFFECT_ICONS.values())) == len(ordinary_profiles),
            "普通效果圖示必須完整對應 profile，且每個物品圖示不同。")
    keys = list(ordinary_profiles)
    for index, key in enumerate(keys):
        title = PROFILES[key][0]
        parent = "yigua:effects/root" if index % 4 == 0 else f"yigua:effects/{keys[index - 1]}"
        outputs[data / f"effects/{key}.json"] = encode_json(advancement(
            title, f"正式起卦取得「{title}」的遊戲效果。", {"id": f"minecraft:{EFFECT_ICONS[key]}"}, parent))
    for key, (title, item, hidden, description) in EXTRA_EFFECTS.items():
        outputs[data / f"effects/{key}.json"] = encode_json(advancement(
            title, description, {"id": f"minecraft:{item}"}, "yigua:effects/root", hidden))
    return outputs


def existing_png_digests(root):
    return {path: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in (root / "src/main/resources").rglob("*.png")
            if not re.fullmatch(r"hexagram_\d{2}\.png", path.name)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="唯讀核對成就與卦象圖示")
    parser.add_argument("--root", type=Path, default=ROOT, help="模組原始碼根目錄")
    args = parser.parse_args()
    root = args.root.resolve()
    original_pngs = existing_png_digests(root)
    outputs = make_outputs(root)
    changed = [path for path, content in outputs.items() if not path.exists() or path.read_bytes() != content]
    if args.check and changed:
        for path in changed:
            print(f"需重新產生：{path.relative_to(root)}", file=sys.stderr)
        return 1
    if not args.check:
        for path in changed:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(outputs[path])
    require(existing_png_digests(root) == original_pngs, "原有 PNG 執行期間變動，請檢查其他寫入者。")
    json_count = validate_json(root)
    mode = "核對通過" if args.check else "產生完成"
    advancement_count = sum("advancement" in path.parts for path in outputs)
    hidden_count = sum(entry[2] for entry in EXTRA_EFFECTS.values())
    print(f"{mode}：64 張原生六爻圖示、64 組紙張物品模型、{advancement_count} 個成就（含 2 個根與 {hidden_count} 個隱藏挑戰）；"
          f"{json_count} 份 JSON 可解析；原有 {len(original_pngs)} 張 PNG 未變動。")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, IndexError, KeyError) as exc:
        raise SystemExit(f"成就資源未完成：{exc}") from exc
