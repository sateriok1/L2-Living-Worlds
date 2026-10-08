"""Weapon tier lists for the phantoms: every obtainable non-magic weapon, best to worst, per weapon type / hands / grade.
Obtainable = appears in a (non-custom) NPC drop or spoil list, a recipe or multisell result, a shop buylist, or a quest script.
Special-ability variants are folded into their base weapon (SA is not covered yet). Output: weapon_tiers.csv and weapon_tiers.md."""
import csv, glob, os, re, collections
import xml.etree.ElementTree as ET
import l2data as L

D = L.DATA
GR = {"NONE": "NG"}
ORDER = ["NG", "D", "C", "B", "A", "S"]
sources = collections.defaultdict(set)

for p in glob.glob(os.path.join(D, "stats/npcs/*.xml")):          # custom/ is a subfolder and is not read
    for npc in ET.parse(p).getroot().iter("npc"):
        for tag in ("dropLists", "spoilLists"):
            for el in npc.iter(tag):
                for it in el.iter("item"):
                    sources[int(it.get("id"))].add("drop" if tag == "dropLists" else "spoil")
for rec in ET.parse(os.path.join(D, "Recipes.xml")).getroot().iter("item"):
    for pr in rec.iter("production"):
        sources[int(pr.get("id"))].add("recipe")
for p in glob.glob(os.path.join(D, "buylists/*.xml")):
    for it in ET.parse(p).getroot().iter("item"):
        sources[int(it.get("id"))].add("shop")
for p in glob.glob(os.path.join(D, "multisell/*.xml")):
    if os.path.basename(p) in ("DelevelManager.xml", "NoblesseMaster.xml", "SchemeBuffer.xml"): continue
    for it in ET.parse(p).getroot().iter("production"):
        sources[int(it.get("id"))].add("multisell")
quest_text = {}
for p in glob.glob(os.path.join(D, "scripts/quests/**/*.java"), recursive=True):
    t = open(p, encoding="utf-8", errors="ignore").read()
    if "giveItems" in t or "rewardItems" in t:
        quest_text[p] = set(int(n) for n in re.findall(r"\b\d{2,5}\b", t))
quest_ids = set().union(*quest_text.values())

rows = []
for path in glob.glob(os.path.join(D, "stats/items/*.xml")):
    for it in ET.parse(path).getroot().iter("item"):
        if it.get("type") != "Weapon": continue
        s = {x.get("name"): x.get("val") for x in it.findall("set")}
        st = {x.get("type"): float(x.text) for x in it.findall("stats/stat")}
        iid = int(it.get("id")); name = it.get("name")
        wt = s.get("weapon_type")
        if wt not in ("SWORD", "BLUNT", "DAGGER", "BOW", "POLE", "DUAL", "DUALFIST", "FIST"): continue
        if s.get("is_magic_weapon") == "true" or st.get("pAtk", 0) <= 0 or st.get("pAtk", 0) > 600: continue
        if name.startswith(("_", "Monster", "Shadow Item", "Event", "For NPC", "Chrono")) or name.strip().isdigit(): continue
        if s.get("for_npc") == "true": continue                                   # NPC-only copies
        if s.get("is_tradable") == "false" and s.get("is_dropable") == "false": continue   # hero, cursed and other non-circulating weapons
        sa = it.find("skills/skill")
        src = set(sources.get(iid, ()))
        if iid in quest_ids: src.add("quest")
        rows.append(dict(id=iid, name=name, base=name.split(" - ")[0], wtype=wt, hands="2H" if s.get("bodypart") == "lrhand" else "1H",
                         grade=GR.get(s.get("crystal_type", "NONE"), s.get("crystal_type", "NONE")), pAtk=st.get("pAtk", 0), spd=st.get("pAtkSpd", 0),
                         crit=st.get("critRate", 0), acc=st.get("accCombat", 0), sa=" - " in name, src=src, notrade=s.get("is_tradable") == "false"))

# fold special-ability variants into their base weapon: keep the plain one, else the strongest
fam = collections.defaultdict(list)
for r in rows: fam[(r["base"], r["wtype"], r["hands"], r["grade"], r["pAtk"], r["spd"], r["crit"])].append(r)
rows = []
for k, g in fam.items():
    src = set().union(*(r["src"] for r in g))
    if not src or (all(r["notrade"] for r in g) and not (src & {"drop", "recipe"})): continue                                  # nothing in the game gives it out
    plain = [r for r in g if not r["sa"]] or g
    r = dict(max(plain, key=lambda x: x["pAtk"]))
    r["src"] = "/".join(sorted(src)); r["name"] = r["base"]; rows.append(r)
for r in rows:    # auto-attack damage index: hits per second x damage x crit; the pAtk column ranks skill damage
    r["index"] = round(r["pAtk"] * r["spd"] * (1 + r["crit"] / 100) / 1000, 2)
groups = collections.defaultdict(list)
for r in rows: groups[(r["wtype"], r["hands"], r["grade"])].append(r)

out = []
md = ["# Weapon tiers (non-magic, obtainable, base weapons only)\n",
      "Ranked best to worst within each type, hands and grade by auto-attack index = pAtk x atk speed x (1 + crit%) / 1000; ties broken by pAtk.\n"]
for key in sorted(groups, key=lambda k: (k[0], k[1], ORDER.index(k[2]))):
    g = sorted(groups[key], key=lambda r: (-r["index"], -r["pAtk"], r["name"]))
    md.append(f"\n## {key[0]} {key[1]} {key[2]}\n\n| # | Weapon | id | pAtk | speed | crit | index | source |\n|---|---|---|---|---|---|---|---|")
    for i, r in enumerate(g, 1):
        md.append(f"| {i} | {r['name']} | {r['id']} | {r['pAtk']:g} | {r['spd']:g} | {r['crit']:g} | {r['index']} | {r['src']} |")
        out.append(dict(weapon_type=key[0], hands=key[1], grade=key[2], rank=i, **{k: r[k] for k in ("id", "name", "pAtk", "spd", "crit", "index", "src")}))
with open("weapon_tiers.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(out[0])); w.writeheader(); w.writerows(out)
open("weapon_tiers.md", "w").write("\n".join(md) + "\n")
print(len(out), "weapons in", len(groups), "lists")
for key in sorted(groups, key=lambda k: (k[0], k[1], ORDER.index(k[2]))):
    g = sorted(groups[key], key=lambda r: (-r["index"], -r["pAtk"]))
    print(key, len(g), [(r["name"], r["pAtk"]) for r in g[:3]])
