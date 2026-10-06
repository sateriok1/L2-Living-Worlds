"""Compact view of a rotations json: consecutive levels with the same best (priority, weapon, armor) at 10/60/120 s are merged.
Usage: python3 summarize.py rotations_<slug>[_set].json"""
import json, sys
d = json.load(open(sys.argv[1])); prev = None; rows = []
for lv in sorted(d, key=int):
    cells = []
    for m in ("10", "60", "120"):
        b = max(d[lv], key=lambda r: r["windows"][m]["dps"]); w = b["windows"][m]
        cells.append((" + ".join(w["order"]) or "autos", b["weapon"], b["armor"], round(w["dps"])))
    sig = tuple(c[:3] for c in cells)
    if rows and rows[-1][0] == sig: rows[-1][1].append((lv, cells[1][3]))
    else: rows.append([sig, [(lv, cells[1][3])]])
for sig, lvs in rows:
    a, b = lvs[0][0], lvs[-1][0]; dps = f"{lvs[0][1]}-{lvs[-1][1]}"
    s10, s60, s120 = sig
    print(f"L{a}" + (f"-{b}" if a != b else "") + f"  [{dps} dps@60s]\n    10s: {s10[0]}\n    60s: {s60[0]}  | {s60[1]} + {s60[2]}" + (f"\n   120s: {s120[0]}" if s120[0] != s60[0] else ""))
