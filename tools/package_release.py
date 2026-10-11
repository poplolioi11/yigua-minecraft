#!/usr/bin/env python3
"""Package only reviewed mod sources and the built mod; never include a game instance."""
from pathlib import Path
import hashlib
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent.parent
properties = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines()
                  if "=" in line and not line.startswith("#"))
VERSION = properties["version"]
NAME = f"yigua-minecraft-{VERSION}"
OUT = ROOT / "deliverables" / NAME
FILES = ["README.md", "CHANGELOG.md", "LICENSE", "PROVENANCE.md", ".gitignore",
         "build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat"]
DIRS = ["src", "tools", "docs", "gradle"]


def main():
    jar = ROOT / "build/libs" / f"{NAME}.jar"
    if not jar.is_file():
        raise SystemExit("請先完成 ./gradlew build。")
    with zipfile.ZipFile(jar) as built:
        names = built.namelist()
        original_icons = {
            f"assets/yigua/textures/item/{name}.png"
            for name in ("copper_coin", "three_coins", "yarrow_stalks", "wooden_coin", "wooden_three_coins", "iron_coin", "iron_three_coins", "gold_coin", "gold_three_coins", "green_copper_coin", "green_copper_three_coins")
        }
        original_icons.update(f"assets/yigua/textures/item/hexagram_{number:02}.png" for number in range(1, 65))
        original_icons.add("assets/yigua/textures/item/taiji_tool.png")
        if any(n.startswith("net/minecraft/") or n.endswith(".ogg")
               or (n.endswith(".png") and n not in original_icons) for n in names):
            raise SystemExit("安裝檔含非預期遊戲程式／媒體，停止封裝。")
        if not original_icons.issubset(names):
            raise SystemExit("安裝檔缺少一卦原創物品圖示。")
        if "fabric.mod.json" not in names:
            raise SystemExit("找不到模組描述。")
    sources = [ROOT / name for name in FILES]
    for directory in DIRS:
        sources += sorted(p for p in (ROOT / directory).rglob("*")
                          if p.is_file() and "__pycache__" not in p.parts)
    for path in sources:
        if path.is_symlink() or not path.is_file():
            raise SystemExit(f"非預期來源檔案：{path.relative_to(ROOT)}")
        if path.suffix not in {".jar"}:
            data = path.read_bytes()
            if re.search(rb'/Users/[A-Za-z0-9][^\s"\x27]+/', data) or re.search(
                    rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", data):
                raise SystemExit(f"來源檔案含本機路徑或私密內容：{path.relative_to(ROOT)}")
    OUT.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(jar, OUT / jar.name)
    archive = OUT / f"{NAME}-source.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as source_zip:
        for path in sources:
            source_zip.write(path, Path(NAME) / path.relative_to(ROOT))
    for name in ["README.md", "LICENSE", "PROVENANCE.md"]:
        shutil.copyfile(ROOT / name, OUT / name)
    checksums = []
    for name in [jar.name, archive.name, "README.md", "LICENSE", "PROVENANCE.md"]:
        checksums.append(f"{hashlib.sha256((OUT / name).read_bytes()).hexdigest()}  {name}")
    (OUT / "SHA256SUMS").write_text("\n".join(checksums) + "\n")
    with zipfile.ZipFile(archive) as source_zip:
        assert source_zip.testzip() is None
        assert all("/run/" not in n and "/.gradle/" not in n and "/build/" not in n
                   for n in source_zip.namelist())
    print(f"完成 {NAME}：模組 JAR、{len(sources)} 個白名單原始檔、授權及 SHA256SUMS。")


if __name__ == "__main__":
    main()
