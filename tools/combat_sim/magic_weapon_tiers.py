"""Magic weapon tiers (staves and other magic weapons) per grade, best first, plus a rough caster progression table
(weapon grade x robe grade). Rough first pass: weapons rank by M.Atk (P.Atk breaks ties); damage robes come from robe_tiers.csv.
Output: magic_weapon_tiers.csv/.md and caster_progression.csv/.md."""
import csv, glob, os, collections
import xml.etree.ElementTree as ET
import l2data as L
from item_sources import sources, droppers, quests, quest_ids

GR = {"NONE": "NG"}; ORDER = ["NG", "D", "C", "B", "A", "S"]
rows = []
for path in glob.glob(os.path.join(L.DATA, "stats/items/*.xml")):
    for it in ET.parse(path).getroot().iter("item"):
        if it.get("type") != "Weapon": continue
        s = {x.get("name"): x.get("val") for x in it.findall("set")}
        st = {x.get("type"): float(x.text) for x in it.findall("stats/stat")}
        iid = int(it.get("id")); name = it.get("name")
        wt = s.get("weapon_type")
        if wt not in ("BLUNT", "SWORD", "DAGGER") or s.get("is_magic_weapon") != "true" or st.get("mAtk", 0) <= 0: continue
        if name.startswith(("_", "Monster", "Shadow Item", "Event", "For NPC", "Chrono")) or name.strip().isdigit(): continue
        if s.get("for_npc") == "true": continue
        if s.get("is_tradable") == "false" and s.get("is_dropable") == "false": continue
        src = set(sources.get(iid, ()))
        if iid in quest_ids: src.add("quest")
        rows.append(dict(id=iid, name=name, base=name.split(" - ")[0], wtype=wt, hands="2H" if s.get("bodypart") == "lrhand" else "1H",
                         grade=GR.get(s.get("crystal_type", "NONE"), s.get("crystal_type", "NONE")), pAtk=st.get("pAtk", 0), mAtk=st["mAtk"],
                         sa=" - " in name, src=src, notrade=s.get("is_tradable") == "false"))
fam = collections.defaultdict(list)
for r in rows: fam[(r["base"], r["wtype"], r["hands"], r["grade"], r["mAtk"], r["pAtk"])].append(r)
rows = []
for k, g in fam.items():
    src = set().union(*(r["src"] for r in g))
    if not src or (all(r["notrade"] for r in g) and not (src & {"drop", "recipe"})): continue
    r = dict((([x for x in g if not x["sa"]]) or g)[0])
    ids = [x["id"] for x in g]
    lab = {"recipe": "craft", "multisell": "exchange"}
    r["src"] = "/".join(sorted({lab.get(x, x) for x in src}))
    dr = sorted({d for i in ids for d in droppers.get(i, ())}); qs = sorted({q for i in ids for q in quests.get(i, ())})
    det = []
    if dr: det.append("drop: " + ", ".join(f"{n} L{l}" for l, n in dr[:3]) + (f" +{len(dr)-3}" if len(dr) > 3 else ""))
    if qs: det.append("quest: " + ", ".join(qs[:2]))
    r["detail"] = "; ".join(det); r["name"] = r["base"]; rows.append(r)
groups = collections.defaultdict(list)
for r in rows: groups[(r["wtype"], r["hands"], r["grade"])].append(r)

out, md = [], ["# Magic weapon tiers (obtainable, base weapons only)\n", "Ranked best to worst within each type, hands and grade by M.Atk; ties broken by P.Atk. First pass: special effects (MP cost, cast speed on the weapon's skill) are not scored.\n"]
best = {}      # (hands, grade) -> best M.Atk
for key in sorted(groups, key=lambda k: (k[0], k[1], ORDER.index(k[2]))):
    g = sorted(groups[key], key=lambda r: (-r["mAtk"], -r["pAtk"], r["name"]))
    md.append(f"\n## {key[0]} {key[1]} {key[2]}\n\n| # | Weapon | id | M.Atk | P.Atk | source | details |\n|---|---|---|---|---|---|---|")
    for i, r in enumerate(g, 1):
        md.append(f"| {i} | {r['name']} | {r['id']} | {r['mAtk']:g} | {r['pAtk']:g} | {r['src']} | {r['detail']} |")
        out.append(dict(weapon_type=key[0], hands=key[1], grade=key[2], rank=i, **{k: r[k] for k in ("id", "name", "mAtk", "pAtk", "src", "detail")}))
    if key[0] == "BLUNT":
        best[(key[1], key[2])] = max(best.get((key[1], key[2]), 0), g[0]["mAtk"])
with open("magic_weapon_tiers.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(out[0])); w.writeheader(); w.writerows(out)
open("magic_weapon_tiers.md", "w").write("\n".join(md) + "\n")

# ---- rough progression: best staff of a grade x best damage robe of a grade, relative to NG staff + NG robe
robe = {}
for r in csv.DictReader(open("robe_tiers.csv")):
    if r["role"].startswith("Damage") and r["rank"] == "1": robe[r["grade"]] = (float(r["damage"]), r["set"])
base = best.get(("2H", "NG")) or min(v for (h, g), v in best.items() if h == "2H")
prog = []
for wh in ("2H", "1H"):
    for wg in ORDER:
        for rg in ORDER:
            if (wh, wg) in best and rg in robe:
                prog.append(dict(hands=wh, weapon_grade=wg, robe_grade=rg, staff_matk=best[(wh, wg)], robe_damage=robe[rg][0], robe=robe[rg][1],
                                 index=round(best[(wh, wg)] / base * robe[rg][0], 2)))
with open("caster_progression.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(prog[0])); w.writeheader(); w.writerows(prog)
md = ["# Caster progression (rough)\n", "Damage index = (best staff M.Atk / NG staff) x (best damage robe's damage multiplier). 1.00 = NG staff and no robe bonus. Read a row for the weapon grade, a column for the robe grade; the diagonal is 'everything this grade'.\n"]
for wh in ("2H", "1H"):
    gs = [g for g in ORDER if (wh, g) in best]
    if not gs: continue
    md.append(f"\n## {wh} magic weapon (blunt/staff)\n\n| weapon \\ robe | " + " | ".join(f"{g} ({robe[g][1]})" for g in ORDER if g in robe) + " |\n|---|" + "---|" * len(robe))
    for wg in gs:
        cells = [next(str(p['index']) for p in prog if p['hands'] == wh and p['weapon_grade'] == wg and p['robe_grade'] == rg) for rg in ORDER if rg in robe]
        md.append(f"| {wg} ({best[(wh, wg)]:g} M.Atk) | " + " | ".join(cells) + " |")
open("caster_progression.md", "w").write("\n".join(md) + "\n")
print(len(out), "magic weapons in", len({(o['weapon_type'], o['hands'], o['grade']) for o in out}), "lists")
