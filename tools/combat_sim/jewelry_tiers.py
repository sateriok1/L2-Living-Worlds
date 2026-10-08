"""Jewelry tier lists (necklaces, earrings, rings) per grade, ordered by M.Def. Only obtainable items. Output: jewelry_tiers.csv / jewelry_tiers.md."""
import csv, glob, os
import xml.etree.ElementTree as ET
import l2data as L
from item_sources import sources, droppers, quests, quest_ids

GRADE = {"NONE": "NG"}; ORDER = ["NG", "D", "C", "B", "A", "S"]
SLOT = {"neck": "Necklace", "rear;lear": "Earring", "rfinger;lfinger": "Ring"}
LAB = {"recipe": "craft", "multisell": "exchange"}
rows = []
for p in glob.glob(os.path.join(L.DATA, "stats/items/*.xml")):
    for it in ET.parse(p).getroot().iter("item"):
        if it.get("type") != "Armor": continue
        s = {x.get("name"): x.get("val") for x in it.findall("set")}
        if s.get("bodypart") not in SLOT: continue
        name = it.get("name"); iid = int(it.get("id"))
        if name.startswith(("_", "Shadow Item", "Event", "For NPC")) or s.get("for_npc") == "true" or "Pledge" in name: continue
        if s.get("is_tradable") == "false" and s.get("is_dropable") == "false": continue
        st = {x.get("type"): float(x.text) for x in it.findall("stats/stat")}
        if st.get("mDef", 0) <= 0: continue
        src = set(sources.get(iid, ()))
        if iid in quest_ids: src.add("quest")
        if not src or (s.get("is_tradable") == "false" and not (src & {"drop", "recipe"})): continue
        dr = sorted(droppers.get(iid, ())); qs = sorted(quests.get(iid, ()))
        det = []
        if dr: det.append("drop: " + ", ".join(f"{n} L{l}" for l, n in dr[:3]) + (f" +{len(dr)-3}" if len(dr) > 3 else ""))
        if qs: det.append("quest: " + ", ".join(qs[:2]) + (f" +{len(qs)-2}" if len(qs) > 2 else ""))
        rows.append(dict(slot=SLOT[s["bodypart"]], grade=GRADE.get(s.get("crystal_type", "NONE"), s.get("crystal_type")), id=iid, name=name, mDef=st["mDef"],
                         maxMp=st.get("maxMp", 0), src="/".join(sorted({LAB.get(x, x) for x in src})), detail="; ".join(det)))
out = []; md = ["# Jewelry tiers (obtainable, ordered by M.Def)\n", "Best to worst within each slot and grade by M.Def; max MP bonus (if any) breaks ties.\n"]
for slot in ("Necklace", "Earring", "Ring"):
    md.append(f"\n# {slot}s")
    for g in ORDER:
        grp = sorted([r for r in rows if r["slot"] == slot and r["grade"] == g], key=lambda r: (-r["mDef"], -r["maxMp"], r["name"]))
        if not grp: continue
        md.append(f"\n## {slot} {g}\n\n| # | Item | id | M.Def | max MP | source | details |\n|---|---|---|---|---|---|---|")
        for n, r in enumerate(grp, 1):
            md.append(f"| {n} | {r['name']} | {r['id']} | {r['mDef']:g} | {r['maxMp']:g} | {r['src']} | {r['detail']} |")
            out.append(dict(slot=slot, grade=g, rank=n, id=r["id"], name=r["name"], mDef=r["mDef"], maxMp=r["maxMp"], source=r["src"], detail=r["detail"]))
with open("jewelry_tiers.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(out[0])); w.writeheader(); w.writerows(out)
open("jewelry_tiers.md", "w").write("\n".join(md) + "\n")
print(len(out), "jewelry items")
