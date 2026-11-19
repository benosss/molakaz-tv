#!/usr/bin/env python3
"""Checks every app/app/src/main/assets/i18n/<lang>.json against tools/i18n/keys.json:
missing or extra keys, placeholders ({n}, {time}...) that don't match, empty values. Exit 1 on any problem."""
import json, re, sys, pathlib
root = pathlib.Path(__file__).resolve().parents[2]
keys = json.load(open(root / "tools/i18n/keys.json"))
bad = 0
for f in sorted((root / "app/app/src/main/assets/i18n").glob("*.json")):
    t = json.load(open(f))
    miss = [k for k in keys if not t.get(k, "").strip()]
    extra = [k for k in t if k not in keys]
    ph = [k for k in keys if k in t and sorted(re.findall(r"\{\w+\}", k)) != sorted(re.findall(r"\{\w+\}", t[k]))]
    if miss or extra or ph: bad += 1
    print(f"{f.name:12s} {len(t):4d} strings  missing {len(miss):3d}  extra {len(extra):2d}  placeholder errors {len(ph):2d}")
    for k in ph[:5]: print("   placeholders:", k, "→", t[k])
sys.exit(1 if bad else 0)
