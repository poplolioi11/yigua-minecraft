#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Generate namespaced GUI aliases and labels for every registered effect scale.

Run from any directory with ``python3 tools/generate_effect_assets.py``. Use
``--check`` to verify the committed resources without writing them.

Protocol to keep synchronized when Java changes:
* YiguaMod.registerEffect: distinct CastingMaterial multipliers, rarity factors,
  and environment factors below form the complete registration cross product.
* YiguaMod.effectKey: Math.round(scale * 10000); 10000 uses the bare profile ID,
  every other value uses ``<profile>_x<fixed>``. Fractions reproduce positive
  half-up rounding without Python round()'s ties-to-even or binary drift.
* Library.profileName: all existing language files intentionally use the same
  Traditional Chinese effect labels. Other translation keys remain unchanged.
* SpecialEvents.init: SINGLE_EFFECTS are standalone registrations; they get one
  bare alias and label each, never a multiplier family or registerEffect match.

The gui atlas lives in assets/minecraft so Minecraft merges these single-sprite
aliases into its GUI atlas. Every output sprite ID is under yigua:mob_effect/;
the source texture stays in Minecraft. No vanilla sprite is replaced or copied,
and this tool never writes a PNG. Existing unrelated atlas sources are retained.
"""

import argparse
import hashlib
import json
import re
import sys
from fractions import Fraction
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
MATERIAL_FACTORS = tuple(map(Fraction, ("0.5", "0.75", "1", "1.25", "1.5")))
RARITY_FACTORS = tuple(map(Fraction, ("1", "2", "2.5", "3", "3.5", "4")))
ENVIRONMENT_FACTORS = tuple(Fraction(value, 100) for value in range(90, 111, 2))
KEY_PRECISION = 10000

# profile: (Library.profileName label, existing minecraft:mob_effect sprite)
# Reusing a sprite communicates a related action; it does not add its vanilla
# potion effect. Keep the original eight mappings stable when extending this.
PROFILES = {
    "travel": ("行旅", "speed"),
    "caution": ("行路受阻", "slowness"),
    "focus": ("專注", "haste"),
    "fatigue": ("休整", "mining_fatigue"),
    "strength": ("健行", "strength"),
    "restraint": ("收斂", "weakness"),
    "guard": ("守護", "resistance"),
    "frail": ("謹慎", "weakness"),
    "quick": ("振作", "haste"),
    "slowhand": ("遲滯", "mining_fatigue"),
    "luck": ("得宜", "luck"),
    "illluck": ("失宜", "unluck"),
    "lightness": ("輕身", "slow_falling"),
    "heaviness": ("沉重", "slowness"),
    "landing": ("安落", "resistance"),
    "fallrisk": ("失足", "weakness"),
    "airloss": ("涉水受阻", "water_breathing"),
    "vitality": ("生機", "health_boost"),
    "depletion": ("衰耗", "hunger"),
    "leap": ("躍升", "jump_boost"),
    "heavyfeet": ("重足", "slowness"),
    "steady": ("穩立", "resistance"),
    "exposed": ("易退", "weakness"),
    "fireward": ("避灼", "fire_resistance"),
    "embers": ("餘燼", "fire_resistance"),
    "reach": ("延展", "luck"),
    "nearreach": ("近取", "weakness"),
    "breath": ("深息", "water_breathing"),
    "deepwork": ("潛作", "dolphins_grace"),
    "drownedwork": ("潛阻", "mining_fatigue"),
    "closecombat": ("近接", "strength"),
    "direction": ("順方", "speed"),
}
SINGLE_EFFECTS = {
    "undying": ("暫護", "resistance"),
    "rapport": ("親和", "hero_of_the_village"),
    "sea": ("分海", "dolphins_grace"),
}
SCALED_LANG_KEY = re.compile(r"^effect\.yigua\.[a-z0-9_]+_x\d+$")
SCALED_SPRITE = re.compile(r"^yigua:mob_effect/[a-z0-9_]+_x\d+$")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"重複 JSON 欄位：{key}")
        result[key] = value
    return result


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)


def encode_json(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def fixed_scales():
    return sorted({
        int(material * rarity * environment * KEY_PRECISION + Fraction(1, 2))
        for material in MATERIAL_FACTORS
        for rarity in RARITY_FACTORS
        for environment in ENVIRONMENT_FACTORS
    })


def effect_key(profile, fixed):
    return profile if fixed == KEY_PRECISION else f"{profile}_x{fixed}"


def verify_java_protocol(root):
    """Fail before writing resources if the Java contract has drifted."""
    package = root / "src/main/java/art/yigua/minecraft"
    mod = (package / "YiguaMod.java").read_text(encoding="utf-8")
    material = (package / "CastingMaterial.java").read_text(encoding="utf-8")
    library = (package / "Library.java").read_text(encoding="utf-8")
    registered = re.findall(r'registerEffect\("([a-z0-9_]+)"', mod)
    require(len(registered) == len(PROFILES) and set(registered) == set(PROFILES),
            "Java 效果種類已改變，請同步 PROFILES。")
    actual_materials = {
        Fraction(value) for value in re.findall(
            r'^\s*[A-Z][A-Z0-9_]*\(([0-9]+(?:\.[0-9]+)?),', material, re.MULTILINE)
    }
    require(actual_materials == set(MATERIAL_FACTORS),
            "Java 材質倍率已改變，請同步 MATERIAL_FACTORS。")
    for name, expected in (("rarity", RARITY_FACTORS), ("environment", ENVIRONMENT_FACTORS)):
        match = re.search(
            rf'for\s*\(double\s+{name}\s*:\s*new\s+double\[\]\s*\{{([^}}]+)\}}\)', mod)
        require(match is not None, f"找不到 Java {name} 註冊範圍，請核對產生器。")
        values = tuple(Fraction(value.strip()) for value in match.group(1).split(","))
        require(values == expected, f"Java {name} 倍率已改變，請同步對應常數。")
    compact = re.sub(r"\s+", "", mod)
    require("Math.round(material.multiplier()*rarity*environment*10000)" in compact,
            "Java 註冊倍率的四捨五入協定已改變。")
    require("Math.round(scale*10000)" in compact
            and 'fixed==10000?profile:profile+"_x"+fixed' in compact,
            "Java effectKey 協定已改變，請同步 KEY_PRECISION／effect_key。")
    names = library.split("public static String profileName(String profile)", 1)[1]
    names = names.split("public static String effectSummary", 1)[0]
    labels = dict(re.findall(r'case\s+"([a-z0-9_]+)"\s*->\s*"([^"]+)"', names))
    require(all(labels.get(key) == label for key, (label, _) in PROFILES.items()),
            "Java 效果名稱已改變，請同步 PROFILES 的繁中名稱。")


def managed_source(source):
    sprite = source.get("sprite") if isinstance(source, dict) else None
    return isinstance(sprite, str) and (
        sprite in {f"yigua:mob_effect/{profile}" for profile in (*PROFILES, *SINGLE_EFFECTS)}
        or SCALED_SPRITE.fullmatch(sprite) is not None)


def make_outputs(root, scales):
    assets = root / "src/main/resources/assets"
    aliases = []
    labels = {}
    for profile, (label, resource) in PROFILES.items():
        for fixed in scales:
            key = effect_key(profile, fixed)
            aliases.append({
                "type": "minecraft:single",
                "resource": f"minecraft:mob_effect/{resource}",
                "sprite": f"yigua:mob_effect/{key}",
            })
            labels[f"effect.yigua.{key}"] = label
    for key, (label, resource) in SINGLE_EFFECTS.items():
        aliases.append({
            "type": "minecraft:single",
            "resource": f"minecraft:mob_effect/{resource}",
            "sprite": f"yigua:mob_effect/{key}",
        })
        labels[f"effect.yigua.{key}"] = label

    atlas_path = assets / "minecraft/atlases/gui.json"
    atlas = read_json(atlas_path) if atlas_path.exists() else {"sources": []}
    require(isinstance(atlas, dict) and isinstance(atlas.get("sources"), list),
            "GUI atlas 格式不符合 sources 清單。")
    atlas["sources"] = [source for source in atlas["sources"] if not managed_source(source)] + aliases
    outputs = {atlas_path: encode_json(atlas)}
    language_paths = sorted((assets / "yigua/lang").glob("*.json"))
    require(language_paths, "找不到既有語系檔，請先建立基本物品語系。")
    for path in language_paths:
        previous = read_json(path)
        require(isinstance(previous, dict), f"語系格式錯誤：{path.name}")
        base_keys = {f"effect.yigua.{profile}" for profile in (*PROFILES, *SINGLE_EFFECTS)}
        language = {key: value for key, value in previous.items()
                    if key not in base_keys and SCALED_LANG_KEY.fullmatch(key) is None}
        language.update(labels)
        outputs[path] = encode_json(language)
    return outputs, len(aliases), len(language_paths)


def png_digests(root):
    return {path: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in (root / "src/main/resources").rglob("*.png")}


def validate_json(root):
    paths = sorted((root / "src/main/resources").rglob("*.json"))
    for path in paths:
        read_json(path)
    return len(paths)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="唯讀核對產出是否已同步")
    parser.add_argument("--root", type=Path, default=ROOT, help="模組原始碼根目錄")
    args = parser.parse_args()
    root = args.root.resolve()
    verify_java_protocol(root)
    validate_json(root)
    before_png = png_digests(root)
    scales = fixed_scales()
    require(KEY_PRECISION in scales, "註冊範圍缺少不帶 _x 的基礎效果。")
    outputs, aliases, languages = make_outputs(root, scales)
    changed = [path for path, content in outputs.items()
               if not path.exists() or path.read_bytes() != content]
    if args.check and changed:
        for path in changed:
            print(f"需重新產生：{path.relative_to(root)}", file=sys.stderr)
        return 1
    if not args.check:
        for path in changed:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(outputs[path])
    json_count = validate_json(root)
    require(before_png == png_digests(root), "PNG 檔案於執行期間變動，請檢查其他寫入者。")
    mode = "核對通過" if args.check else "產生完成"
    print(f"{mode}：{len(PROFILES)} 種效果 × {len(scales)} 個唯一倍率 = "
          f"{len(PROFILES) * len(scales)} 個系列別名，另 {len(SINGLE_EFFECTS)} 個單一別名；"
          f"{languages} 個語系各 {aliases} 個效果鍵；{json_count} 份 JSON 可解析；"
          f"{len(before_png)} 張 PNG 未改動。")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, IndexError, KeyError) as exc:
        raise SystemExit(f"效果資源未完成：{exc}") from exc
