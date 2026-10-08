"""Robe armor set tier lists for the phantoms, per grade: one list for damage casters / summoners, one for healers.
Damage casters: damage gained from the set first (M.Atk, cast speed, INT/WIT bonuses), then mana gained.
Healers: cast speed first, then mana. Only sets whose every piece can be obtained in game. Output: robe_tiers.csv / robe_tiers.md."""
import csv, glob, os, collections
import xml.etree.ElementTree as ET
import l2data as L, stats_model as S
from item_sources import sources, droppers, quests, quest_ids

REF = dict(INT=45, WIT=40, MEN=40)              # typical caster stats; the ranking is re-checked over a range below
GRADE = {"NONE": "NG"}; ORDER = ["NG", "D", "C", "B", "A", "S"]
items = {}
for p in glob.glob(os.path.join(L.DATA, "stats/items/*.xml")):
    for it in ET.parse(p).getroot().iter("item"): items[int(it.get("id"))] = it
def sv(it): return {s.get("name"): s.get("val") for s in it.findall("set")}
def where(i):
    src = set(sources.get(i, ()))
    if i in quest_ids: src.add("quest")
    lab = {"recipe": "craft", "multisell": "exchange"}
    return "/".join(sorted({lab.get(x, x) for x in src}))

def evaluate(bonus, ents, maxmp_pieces, ref):
    d = lambda k: bonus.get(k, 0)
    int_r = (S.bonus("INT", ref["INT"] + d("int")) / S.bonus("INT", ref["INT"])) ** 2     # M.Atk scales with INT bonus squared
    wit_r = S.bonus("WIT", ref["WIT"] + d("wit")) / S.bonus("WIT", ref["WIT"])            # cast speed scales with the WIT bonus
    men_r = S.bonus("MEN", ref["MEN"] + d("men")) / S.bonus("MEN", ref["MEN"])
    matk = spd = reg = 1.0; mp = maxmp_pieces
    for fn, k, v in ents:
        if k == "mAtk" and fn == "mul": matk *= v
        if k == "mAtkSpd" and fn == "mul": spd *= v
        if k == "regMp" and fn == "mul": reg *= v
        if k == "maxMp" and fn == "add": mp += v
    cast = spd * wit_r
    return dict(damage=matk * int_r * cast, cast=cast, mana=mp * reg * men_r, mp=mp, matk=matk, int_r=int_r, wit_r=wit_r, reg=reg * men_r)

sets = []
for path in sorted(glob.glob(os.path.join(L.DATA, "stats/armorsets/*.xml"))):
    if "clan" in path: continue
    for st in ET.parse(path).getroot().iter("set"):
        ch = st.find("chest")
        if ch is None or int(ch.get("id")) not in items: continue
        ci = items[int(ch.get("id"))]; cs = sv(ci)
        if cs.get("armor_type") != "MAGIC": continue
        pieces = [(t.tag, int(t.get("id"))) for t in st if t.tag in ("chest", "legs", "head", "gloves", "feet") and int(t.get("id")) in items]
        if not all(where(i) for _, i in pieces): continue                      # a piece nothing gives out: the set is not obtainable
        bonus = {t: int(st.find(t).get("val")) for t in ("str", "dex", "con", "int", "wit", "men") if st.find(t) is not None}
        ents = []
        for sk in st.findall("skill"):
            if sk.get("id") == "3006": continue
            ents += S.passive_entries(int(sk.get("id")), int(sk.get("level")), "BLUNT", "2H")
        mpp = sum(float(x.text) for _, i in pieces for x in items[i].findall("stats/stat") if x.get("type") == "maxMp")
        sets.append(dict(grade=GRADE.get(cs.get("crystal_type", "NONE"), cs.get("crystal_type")), name=ci.get("name"), pieces=pieces, bonus=bonus, ents=ents, mpp=mpp))
sets.append(dict(grade="ALL", name="(no set bonus)", pieces=[], bonus={}, ents=[], mpp=0))

def label(s):
    bits = []
    for fn, k, v in s["ents"]:
        if k in ("mAtk", "mAtkSpd", "regMp") and fn == "mul": bits.append(f"{k} x{v:g}")
        if k == "maxMp" and fn == "add": bits.append(f"maxMp +{v:g}")
    bits += [f"{k.upper()} {v:+d}" for k, v in s["bonus"].items() if k in ("int", "wit", "men")]
    return ", ".join(bits) or "-"

def rank(group, key, ref):
    for s in group: s["ev"] = evaluate(s["bonus"], s["ents"], s["mpp"], ref)
    return sorted(group, key=lambda s: (-round(s["ev"][key[0]], 3), -s["ev"]["mana"]))

out = []; md = ["# Robe armor set tiers (obtainable sets only)\n",
    "Damage casters / summoners: ranked by damage index = M.Atk x INT bonus^2 x cast speed (set multiplier x WIT bonus), then mana.",
    "Healers: ranked by cast speed index (set multiplier x WIT bonus), then mana. Mana = max MP gained (set + pieces) x MP regen (set x MEN bonus).",
    f"Reference stats INT {REF['INT']}, WIT {REF['WIT']}, MEN {REF['MEN']}. Indexes are relative to wearing no set (1.00). A 'stable' note means the order is the same for INT 35-55 and WIT 30-50.\n"]
base = [s for s in sets if s["grade"] == "ALL"][0]
for g in ORDER:
    group = [s for s in sets if s["grade"] == g] + [base]
    md.append(f"\n## {g} grade")
    for role, key in (("Damage casters and summoners", ("damage",)), ("Healers", ("cast",))):
        order = rank(group, key, REF)
        tops = set()
        for i_ in (35, 45, 55):
            for w_ in (30, 40, 50):
                tops.add(tuple(s["name"] for s in rank(group, key, dict(INT=i_, WIT=w_, MEN=40))))
        stable = "stable" if len(tops) == 1 else "order shifts with stats"
        md.append(f"\n### {role} ({stable})\n\n| # | Set | pieces | damage | cast speed | max MP | mana idx | bonuses | source |\n|---|---|---|---|---|---|---|---|---|")
        for n, s in enumerate(order, 1):
            e = s["ev"]; names = ", ".join(items[i].get("name") for _, i in s["pieces"]) or "-"
            srcs = "; ".join(sorted({f"{items[i].get('name')}: {where(i)}" for _, i in s["pieces"]}))[:400] if s["pieces"] else "-"
            md.append(f"| {n} | {s['name']} | {names} | {e['damage']:.3f} | {e['cast']:.3f} | {e['mp']:g} | {e['mana']:.0f} | {label(s)} | {srcs} |")
            out.append(dict(role=role, grade=g, rank=n, set=s["name"], damage=round(e["damage"], 3), cast_speed=round(e["cast"], 3), max_mp=e["mp"], mana_idx=round(e["mana"]),
                            bonuses=label(s), pieces=names, stable=stable))
with open("robe_tiers.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(out[0])); w.writeheader(); w.writerows(out)
open("robe_tiers.md", "w").write("\n".join(md) + "\n")
print(len(sets) - 1, "obtainable robe sets")
