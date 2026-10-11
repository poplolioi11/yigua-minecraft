#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Verify the bundled source snapshot and bounded game rules without dependencies."""

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path
from urllib.parse import urlparse


SOURCE_COMMIT = "019e807802de926fecdaa91017b125487c2a274e"
YICHUAN_SHA256 = "89f10c7ac7bfa0b58a4d52539dafa39f57fbbcc83d011f29b7e0578d29e5d65c"
SOURCE_SHA256 = "184d9b6fdeb2fbce08384b917e42d2ce75b74c46210a69496870476d6f53a732"
PROFILES = {
    "travel": (0, 0.1),
    "caution": (-0.1, 0),
    "focus": (0, 0.1),
    "fatigue": (-0.1, 0),
    "strength": (0, 1),
    "restraint": (-1, 0),
    "guard": (0, 1),
    "frail": (-1, 0),
    "quick": (0, 0.1),
    "slowhand": (-0.1, 0),
    "luck": (0, 1),
    "illluck": (-1, 0),
    "lightness": (0, 0.1),
    "heaviness": (-0.1, 0),
    "landing": (0, 0.1),
    "fallrisk": (-0.1, 0),
    "airloss": (-0.1, 0),
    "vitality": (0, 2),
    "depletion": (-2, 0),
    "leap": (0, 0.1),
    "heavyfeet": (-0.1, 0),
    "steady": (0, 0.1),
    "exposed": (-0.1, 0),
    "fireward": (0, 0.1),
    "embers": (-0.1, 0),
    "reach": (0, 0.5),
    "nearreach": (-0.5, 0),
    "breath": (0, 1),
    "deepwork": (0, 0.1),
    "drownedwork": (-0.1, 0),
    "closecombat": (0, 0.5),
    "direction": (0, 0.1),
    "neutral": (0, 0),
}
EXPECTED_OVERRIDES = {
    (27, 6): ("上九：由頤厲吉利涉大川", "travel"),
    (39, 3): ("九三：往蹇來反", "caution"),
    (1, 6): ("上九：亢龍有悔", "restraint"),
    (60, 1): ("初九：不出戶庭无咎", "guard"),
    (60, 2): ("九二：不出門庭凶", "frail"),
    (64, 3): ("六三：未濟征凶利涉大川", "travel"),
    (25, 3): ("六三：无妄之災或繫之牛行人之得邑人之災", "illluck"),
    (10, 4): ("九四：履虎尾愬愬終吉", "landing"),
    (28, 6): ("上六：過涉滅頂凶无咎", "airloss"),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value):
    return isinstance(value, int) and not isinstance(value, bool)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"重複 JSON 欄位：{key}")
        result[key] = value
    return result


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_url(value, context):
    require(isinstance(value, str), f"{context} 缺少來源網址")
    parsed = urlparse(value)
    require(parsed.scheme == "https" and parsed.hostname in {
        "nrs.harvard.edu", "nrs.lib.harvard.edu", "m-archives.meiji.jp"
    }, f"{context} 來源網址不符合既有館藏來源")


def effect(entry, context):
    profile = entry.get("profile")
    require(profile in PROFILES, f"{context} 未知效果 profile：{profile}")
    amount = entry.get("amount")
    require(isinstance(amount, (int, float)) and not isinstance(amount, bool)
            and math.isfinite(amount), f"{context} amount 必須是有限數值")
    lower, upper = PROFILES[profile]
    require(lower <= amount <= upper, f"{context} 數值超出 {profile} 的幅度與方向限制")
    require(profile == "neutral" or amount != 0, f"{context} 非中性效果不得為零")
    require(amount == (lower if lower < 0 else upper), f"{context} 必須保留 {profile} 的一倍基準數值")
    require(integer(entry.get("duration_ticks")) and entry["duration_ticks"] == 12000,
            f"{context} 持續時間必須是 12000 遊戲刻")
    rationale = entry.get("rationale")
    require(isinstance(rationale, str) and rationale.startswith("遊戲改編：")
            and len(rationale) > 6, f"{context} 必須有明示遊戲改編的說明")


def verify(text_path, rules_path, original_path=None):
    require(digest(text_path) == SOURCE_SHA256, "封裝古籍資料 SHA256 與固定公開快照不符")
    if original_path is not None:
        require(original_path.is_file(), f"找不到指定來源檔：{original_path}")
        require(digest(original_path) == SOURCE_SHA256, "唯讀來源 SHA256 與固定公開快照不符")
        require(text_path.read_bytes() == original_path.read_bytes(), "封裝檔與唯讀公開檔並非逐位元組相同")

    texts = read_json(text_path)
    hexagrams = texts.get("hexagrams")
    require(isinstance(hexagrams, list) and len(hexagrams) == 64, "古籍資料必須有 64 卦")
    require([h.get("number") for h in hexagrams] == list(range(1, 65)), "古籍卦序必須為 1 至 64")
    source_count = 0
    for h in hexagrams:
        context = f"第 {h['number']} 卦"
        require(integer(h["number"]), f"{context} 卦序必須是整數")
        require(isinstance(h.get("name"), str) and h["name"], f"{context} 缺少卦名")
        require(isinstance(h.get("lines"), list) and len(h["lines"]) == 6, f"{context} 必須有六爻")
        require(isinstance(h.get("lineSources"), list) and len(h["lineSources"]) == 6,
                f"{context} 必須有六則逐爻來源")
        for line, url in zip(h["lines"], h["lineSources"]):
            require(isinstance(line, str) and "：" in line, f"{context} 爻辭必須保留爻位與原文")
            source_url(url, context)
            source_count += 1
    require(source_count == 384, "逐爻來源數量必須是 384")
    require(hexagrams[0].get("special") == {"label": "用九", "text": "見羣龍无首吉"}, "用九原文不符")
    require(hexagrams[1].get("special") == {"label": "用六", "text": "利永貞"}, "用六原文不符")

    rules = read_json(rules_path)
    metadata = rules.get("meta", {})
    require(metadata.get("schema_version") == 1, "不支援的效果資料結構版本")
    require(metadata.get("source_sha256") == SOURCE_SHA256, "規則 source_sha256 不符")
    require(metadata.get("source_commit") == SOURCE_COMMIT, "規則來源版本不符")
    require(metadata.get("source_resource") == "data/yigua/texts/wuying-lines.json", "來源資源路徑不符")
    require("遊戲改編" in metadata.get("disclaimer", ""), "缺少總體遊戲改編聲明")
    require(metadata.get("general_profile_count") == 32, "一般效果種類必須明示為32種，不包含中性與隱藏事件")
    yichuan_path = text_path.parent / "yichuan-guaci.json"
    require(digest(yichuan_path) == YICHUAN_SHA256, "伊川快照 SHA256 不符")
    yichuan = read_json(yichuan_path)
    require(yichuan["meta"]["status"] == "初錄稿，未經二校", "伊川初稿狀態不可省略")
    require(metadata.get("yichuan_source_sha256") == YICHUAN_SHA256, "規則伊川快照不符")
    require(len(yichuan["hexagrams"]) == 64, "伊川必須有完整64卦資料")
    base = rules.get("hexagrams")
    require(isinstance(base, list) and len(base) == 64, "必須有 64 卦基礎效果")
    require([h.get("number") for h in base] == list(range(1, 65)), "效果表卦序必須為 1 至 64，且不可重複")
    for rule, h in zip(base, hexagrams):
        require(integer(rule.get("number")) and rule.get("name") == h["name"], f"第 {h['number']} 卦編號／名稱不一致")
        effect(rule, f"第 {h['number']} 卦基礎效果")
        ref = yichuan["hexagrams"][h["number"] - 1]
        require(ref["no"] == h["number"], "伊川卦序不符")
        full = ref["xu"] + "".join(segment["zhuan"] for segment in ref["segments"])
        excerpt = rule.get("source_excerpt", "")
        guaci = "；".join(segment["jing"] for segment in ref["segments"])
        evidence = "《伊川易傳》卦辭：" + guaci + "\n程傳節錄：" + excerpt + "\n四庫本初錄稿，未經全文二校。"
        require(excerpt and excerpt in full and rule.get("orig_text") == evidence, "伊川節錄或卦辭逐字不符")
        require(rule.get("source_url") == ref["image_links"][0] and rule.get("source_pages") == ref["pages"], "伊川頁碼來源不符")
        require(rule.get("source_status") == "初錄稿，未經全文二校", "基礎規則不可宣稱全文已校對")

    overrides = rules.get("line_overrides")
    require(isinstance(overrides, list) and len(overrides) == len(EXPECTED_OVERRIDES), "必須包含九則逐字核對的動爻覆寫")
    seen = set()
    ids = set()
    for rule in overrides:
        n, line = rule.get("number"), rule.get("line")
        require(integer(n) and integer(line) and 1 <= n <= 64 and 1 <= line <= 6, "逐爻覆寫編號不合法")
        key = (n, line)
        require(key in EXPECTED_OVERRIDES and key not in seen, f"未核對或重複的逐爻覆寫：{key}")
        seen.add(key)
        id_ = f"hexagram_{n:02d}_line_{line}"
        require(rule.get("id") == id_ and id_ not in ids, f"覆寫識別名稱不合法：{id_}")
        ids.add(id_)
        h = hexagrams[n - 1]
        original, profile = EXPECTED_OVERRIDES[key]
        require(rule.get("orig_text") == h["lines"][line - 1] == original, f"{id_} 原文逐字比對失敗")
        require(rule.get("source_url") == h["lineSources"][line - 1], f"{id_} 來源與原資料不一致")
        require(rule.get("profile") == profile, f"{id_} 與第一版指定遊戲主題不一致")
        require(integer(rule.get("priority")) and rule["priority"] == 20, f"{id_} 優先序必須為 20")
        source_url(rule["source_url"], id_)
        effect(rule, id_)
    require(seen == set(EXPECTED_OVERRIDES), "逐爻覆寫有遺漏")
    available_profiles = {rule["profile"] for rule in base + overrides} - {"neutral"}
    require(available_profiles == set(PROFILES) - {"neutral"}, "32種一般效果必須全部能由自然卦或動爻取得")

    special = rules.get("special_rules")
    require(isinstance(special, list) and len(special) == 2, "必須保留用九與用六兩則特別規則")
    by_id = {rule.get("id"): rule for rule in special}
    require(set(by_id) == {"use9", "use6"}, "特別規則識別名稱必須是 use9 和 use6")
    for id_, number, profile in [("use9", 1, "guard"), ("use6", 2, "neutral")]:
        rule = by_id[id_]
        source = hexagrams[number - 1]["special"]
        require(integer(rule.get("number")) and rule["number"] == number, f"{id_} 卦序不符")
        require(rule.get("label") == source["label"] and rule.get("orig_text") == source["text"], f"{id_} 原文不符")
        require(rule.get("profile") == profile, f"{id_} 指定效果不符")
        require(rule.get("trigger") == "all_six_moving_lines", f"{id_} 必須明示六爻全動觸發")
        require(integer(rule.get("priority")) and rule["priority"] == 30, f"{id_} 優先序必須為 30")
        require(rule.get("source_url") is None and "獨立" in rule.get("source_note", ""), f"{id_} 必須保留獨立頁碼缺口")
        effect(rule, id_)
    resolution = rules.get("resolution", {})
    require(resolution.get("stack_effects") is False, "Library基礎／覆寫選取本身不得疊加")
    require("基礎效果" in resolution.get("base_rule", ""), "必須明示沒有動爻覆寫匹配時回退基礎效果")
    require("Library" in resolution.get("stack_scope", ""), "必須區分古籍規則選取與遊戲動爻追加")
    additions = resolution.get("moving_additions", {})
    require(additions.get("no_moving") == 0 and additions.get("one_or_two") == 1
            and additions.get("three_to_five_max") == 2
            and additions.get("all_six") == "existing_rare_two_or_three", "動爻追加數量契約不符")
    require("不再抽籤" in additions.get("selection", "") and "去重" in additions.get("selection", ""), "動爻追加須明示確定選取與去重")
    require("不延長" in additions.get("scaling", "") and "遊戲規則" in additions.get("interpretation", ""), "動爻追加須保留時長與解釋界線")
    return len(hexagrams), source_count, len(overrides), len(special)


def main():
    project = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--texts", type=Path, default=project / "src/main/resources/data/yigua/texts/wuying-lines.json")
    parser.add_argument("--rules", type=Path, default=project / "src/main/resources/data/yigua/rules/effects.json")
    parser.add_argument("--source", type=Path, help="額外逐位元組比對的唯讀公開資料檔")
    args = parser.parse_args()
    source = args.source
    local_source = project.parent / "github-public/yigua-wuyingdian-lines/wuying-lines.json"
    if source is None and local_source.is_file():
        source = local_source
    try:
        counts = verify(args.texts, args.rules, source)
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as error:
        print(f"資料驗證失敗：{error}", file=sys.stderr)
        return 1
    print(f"資料驗證通過：{counts[0]} 卦、{counts[1]} 條爻辭／來源、{counts[2]} 則逐爻覆寫、{counts[3]} 則用辭特例。")
    print(f"公開來源 commit：{SOURCE_COMMIT}")
    print(f"古籍封裝 SHA256：{SOURCE_SHA256}")
    if source is not None:
        print("封裝檔與唯讀公開來源逐位元組相同。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
