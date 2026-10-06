"""Infinite vs finite mana: python3 mana_diff.py Titan <buffset>"""
import json, sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rotations as R
slug = sys.argv[1].lower(); b = sys.argv[2]
inf = json.load(open(R.rot_file(slug, b))); fin = json.load(open(R.rot_file(slug, b).replace(".json", "_finitemp.json")))
print(f"[{b}] finite vs infinite mana, best gear per cell")
print("lvl  dps loss 10s/30s/60s/120s        order@60 infinite -> finite (mp used/pool @120s)")
worst = []
for lv in sorted(inf, key=int):
    row = []; 
    def best(d, m): return max(d[lv], key=lambda r: r["windows"][m]["dps"])
    for m in ("10", "30", "60", "120"):
        a, f = best(inf, m), best(fin, m)
        row.append(1 - f["windows"][m]["dps"] / a["windows"][m]["dps"])
    a, f = best(inf, "60"), best(fin, "60"); f120 = best(fin, "120")
    chg = "" if a["windows"]["60"]["order"] == f["windows"]["60"]["order"] else "CHANGED"
    print(f"{lv:>3}  " + " ".join(f"{x*100:5.1f}%" for x in row) + f"   {a['windows']['60']['order']} -> {f['windows']['60']['order']} ({f120['windows']['120']['mp_used']:.0f}/{f120['stats']['mp_max']:.0f}) {chg}")
