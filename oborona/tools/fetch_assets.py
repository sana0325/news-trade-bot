#!/usr/bin/env python3
"""Завантажує CC0-ассети з Poly Haven (через офіційний API) у oborona/assets/ і пише CREDITS.md.

Запускається в GitHub Actions (там є інтернет):  python3 tools/fetch_assets.py
Вибір — найпопулярніший ассет, що відповідає ключовим словам слоту.
"""
import json
import os
import sys
import urllib.request

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
ASSETS = os.path.join(ROOT, "assets")
API = "https://api.polyhaven.com"
UA = {"User-Agent": "oborona-asset-fetcher/1.0"}

# слот -> (тип, ключові слова; перше слово обов'язкове, решта додають балів)
TEXTURES = {
    "asphalt": ["asphalt", "road", "cracked", "damaged"],
    "brick": ["brick", "wall", "dirty", "old"],
    "concrete": ["concrete", "wall", "dirty", "stained"],
    "plaster": ["plaster", "wall", "damaged", "painted"],
    "dirt": ["dirt", "ground", "rocks", "gravel"],
    "rust": ["rust", "metal", "painted", "rusty"],
    "fabric": ["fabric", "burlap", "rough"],
    "pavement": ["paving", "pavement", "tiles", "concrete"],
}
HDRI = ["overcast", "urban", "cloudy", "city"]
MODELS = {
    "barrel": ["barrel", "metal", "oil"],
    "tire": ["tyre", "tire", "wheel"],
    "crate": ["crate", "wooden", "box"],
    "jerrycan": ["jerrycan", "canister", "fuel"],
    "pallet": ["pallet", "wooden"],
    "cinder_block": ["cinder", "block", "concrete"],
}

credits = []


def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def get_json(url):
    return json.loads(get(url))


def save(url, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data = get(url)
    with open(path, "wb") as f:
        f.write(data)
    print(f"  {os.path.relpath(path, ROOT)}  {len(data) // 1024} KB")


def pick(assets, words):
    best, best_score = None, -1
    must = words[0]
    for aid, info in assets.items():
        hay = " ".join([aid, info.get("name", "")] + info.get("tags", []) + info.get("categories", [])).lower()
        if must not in hay:
            continue
        score = sum(3 for w in words[1:] if w in hay) + min(info.get("download_count", 0) / 20000.0, 5)
        if score > best_score:
            best, best_score = aid, score
    return best


def find_map(files, names):
    low = {k.lower(): v for k, v in files.items()}
    for n in names:
        if n.lower() in low:
            return low[n.lower()]
    return None


def credit(kind, slot, aid, info):
    authors = ", ".join((info.get("authors") or {}).keys()) or "Poly Haven"
    credits.append(f"| {kind} | `{slot}` | {info.get('name', aid)} | {authors} | "
                   f"[polyhaven.com/a/{aid}](https://polyhaven.com/a/{aid}) | CC0 |")


def fetch_textures():
    assets = get_json(f"{API}/assets?t=textures")
    for slot, words in TEXTURES.items():
        aid = pick(assets, words)
        if not aid:
            print("no match for", slot)
            continue
        print(f"[texture] {slot} <- {aid}")
        files = get_json(f"{API}/files/{aid}")
        maps = {
            "albedo": ["Diffuse", "diff"],
            "normal": ["nor_gl", "Normal"],
            "roughness": ["Rough", "rough"],
            "ao": ["AO", "ao"],
        }
        for out, keys in maps.items():
            m = find_map(files, keys)
            if not m:
                continue
            res = m.get("1k") or m.get("2k")
            fmt = res.get("jpg") or res.get("png")
            ext = "jpg" if "jpg" in res else "png"
            save(fmt["url"], os.path.join(ASSETS, "textures", slot, f"{slot}_{out}.{ext}"))
        credit("Текстура", slot, aid, assets[aid])


def fetch_hdri():
    assets = get_json(f"{API}/assets?t=hdris")
    aid = pick(assets, HDRI)
    print(f"[hdri] sky <- {aid}")
    files = get_json(f"{API}/files/{aid}")
    res = files["hdri"].get("2k") or files["hdri"]["1k"]
    save(res["hdr"]["url"], os.path.join(ASSETS, "sky", "sky.hdr"))
    credit("HDRI-небо", "sky", aid, assets[aid])


def fetch_models():
    assets = get_json(f"{API}/assets?t=models")
    for slot, words in MODELS.items():
        aid = pick(assets, words)
        if not aid:
            print("no model for", slot)
            continue
        print(f"[model] {slot} <- {aid}")
        files = get_json(f"{API}/files/{aid}")
        g = files.get("gltf", {})
        res = g.get("1k") or g.get("2k")
        if not res:
            continue
        entry = res["gltf"]
        base = os.path.join(ASSETS, "models", slot)
        save(entry["url"], os.path.join(base, f"{slot}.gltf"))
        # gltf посилається на файли за відносними шляхами — зберігаємо їх так само
        orig_name = os.path.basename(entry["url"])
        for rel, inc in entry.get("include", {}).items():
            save(inc["url"], os.path.join(base, rel))
        # перейменовуємо посилання на .bin, якщо gltf його називає за оригінальною назвою
        _ = orig_name
        credit("Модель", slot, aid, assets[aid])


def write_credits():
    path = os.path.join(ROOT, "CREDITS.md")
    head = """# Автори та ліцензії ассетів

Процедурні текстури в `textures/` створені скриптом `tools/gen_textures.py` (власна робота, CC0).
Ассети в `assets/` завантажено автоматично скриптом `tools/fetch_assets.py` з Poly Haven.
Poly Haven публікує всі ассети під ліцензією CC0 (суспільне надбання) — https://polyhaven.com/license

| Тип | Слот | Назва | Автор(и) | Джерело | Ліцензія |
|---|---|---|---|---|---|
"""
    manual = """
## Додані вручну

Ассети, які потрібно завантажити вручну (зброя, руки, персонажі), — див. `assets/README.md`.
Після додавання допишіть сюди рядок: тип, файл, назва, автор, посилання, ліцензія.

| Тип | Файл | Назва | Автор | Джерело | Ліцензія |
|---|---|---|---|---|---|
"""
    with open(path, "w", encoding="utf-8") as f:
        f.write(head + "\n".join(credits) + "\n" + manual)
    print("CREDITS.md written,", len(credits), "entries")


if __name__ == "__main__":
    for step in (fetch_textures, fetch_hdri, fetch_models):
        try:
            step()
        except Exception as e:  # один збій не має зупиняти решту
            print("ERROR in", step.__name__, e, file=sys.stderr)
    write_credits()
