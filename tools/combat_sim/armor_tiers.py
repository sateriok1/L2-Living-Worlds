"""Light and heavy armor set tier lists, per grade. Light = damage gained. Heavy DPS = damage gained. Heavy tank = survivability.
Sets only (set bonuses beat plain pieces); only sets whose every piece can be obtained. Output: armor_tiers.csv / armor_tiers.md."""
import csv, glob, os
import xml.etree.ElementTree as ET
import l2data as L, stats_model as S
from item_sources import sources, quest_ids

REF = dict(STR=45, DEX=40, CON=40)
BASE_PDEF = 80          # naked P.Def; the same for every set, it only sets how much a set's armor matters against it
TANK_HP = {"NG": 500, "D": 1500, "C": 3000, "B": 4500, "A": 6000, "S": 8500}   # a tank's HP at the grade, to value +maxHp bonuses
GRADE = {"NONE": "NG"}; ORDER = ["NG", "D", "C", "B", "A", "S"]
items = {}
for p in glob.glob(os.path.join(L.DATA, "stats/items/*.xml")):
    for it in ET.parse(p).getroot().iter("item"): items[int(it.get("id"))] = it
sv = lambda it: {s.get("name"): s.get("val") for s in it.findall("set")}
def stat(i, k): return sum(float(x.text) for x in items[i].findall("stats/stat") if x.get("type") == k)
def where(i):
    src = set(sources.get(i, ()))
    if i in quest_ids: src.add("quest")
    lab = {"recipe": "craft", "multisell": "exchange"}
    return "/".join(sorted({lab.get(x, x) for x in src}))

def ents_for(st, wt, shield=False):
    e = []
    for sk in st.findall("skill") + (st.findall("shield_skill") if shield else []):
        if sk.get("id") == "3006": continue
        e += S.passive_entries(int(sk.get("id")), int(sk.get("level")), wt, "2H")
    return e

def evaluate(bonus, ents, pieces, grade, ref, with_shield, best_shield=0):
    d = lambda k: bonus.get(k, 0)
    mul = lambda stat_: __import__("math").prod(v for fn, k, v in ents if k == stat_ and fn == "mul")
    add = lambda stat_: sum(v for fn, k, v in ents if k == stat_ and fn == "add")
    str_r = S.bonus("STR", ref["STR"] + d("str")) / S.bonus("STR", ref["STR"])
    dex_r = S.bonus("DEX", ref["DEX"] + d("dex")) / S.bonus("DEX", ref["DEX"])
    con_r = S.bonus("CON", ref["CON"] + d("con")) / S.bonus("CON", ref["CON"])
    crit = 1 + 0.08 * (mul("critRate") * dex_r - 1) if False else (1 + 0.08 * mul("critRate") * dex_r) / 1.08
    damage = mul("pAtk") * str_r * mul("pAtkSpd") * dex_r * crit
    pdef_items = sum(stat(i, "pDef") for t, i in pieces if t != "shield")
    shield = [i for t, i in pieces if t == "shield"]
    sdef = max(sum(stat(i, "sDef") for i in shield), best_shield) if with_shield else 0
    pdef = (BASE_PDEF + pdef_items + sdef + add("pDef")) * mul("pDef")
    hp = (TANK_HP[grade] + add("maxHp")) * mul("maxHp") * con_r
    return dict(damage=damage, pdef=pdef, hp=hp, surv=pdef * hp / ((BASE_PDEF + 0) * TANK_HP[grade]),
                mdef=sum(stat(i, "mDef") for t, i in pieces if t != "shield") * mul("mDef"))

def load(armor_type):
    out = []
    for path in sorted(glob.glob(os.path.join(L.DATA, "stats/armorsets/*.xml"))):
        if "clan" in path: continue
        for st in ET.parse(path).getroot().iter("set"):
            ch = st.find("chest")
            if ch is None or int(ch.get("id")) not in items: continue
            ci = items[int(ch.get("id"))]; cs = sv(ci)
            if cs.get("armor_type") != armor_type: continue
            pieces = [(t.tag, int(t.get("id"))) for t in st if t.tag in ("chest", "legs", "head", "gloves", "feet", "shield") and int(t.get("id")) in items]
            if not all(where(i) for _, i in pieces): continue
            bonus = {t: int(st.find(t).get("val")) for t in ("str", "dex", "con", "int", "wit", "men") if st.find(t) is not None}
            out.append(dict(grade=GRADE.get(cs.get("crystal_type", "NONE"), cs.get("crystal_type")), name=ci.get("name"), pieces=pieces, bonus=bonus, st=st,
                            has_shield=any(t == "shield" for t, _ in pieces)))
    return out

def label(ents, bonus):
    bits = []
    for fn, k, v in ents:
        if k in ("pAtk", "pAtkSpd", "critRate", "pDef", "mDef", "maxHp", "accCombat", "rEvas", "runSpd") and fn == "mul": bits.append(f"{k} x{v:g}")
        elif k in ("maxHp", "accCombat", "pDef", "runSpd") and fn in ("add", "sub"): bits.append(f"{k} {'+' if fn == 'add' else '-'}{v:g}")
    bits += [f"{k.upper()} {v:+d}" for k, v in bonus.items() if k != "int" or True]
    return ", ".join(bits) or "-"

SHIELDS = []
for i, it in items.items():
    v = sv(it)
    if it.get("type") == "Armor" and v.get("bodypart") == "lhand" and stat(i, "sDef") > 0 and where(i) and not it.get("name").startswith(("_", "Shadow Item", "Event")) and "Pledge" not in it.get("name") and v.get("for_npc") != "true":
        SHIELDS.append(dict(id=i, name=it.get("name"), grade=GRADE.get(v.get("crystal_type", "NONE"), v.get("crystal_type")), sdef=stat(i, "sDef"), rshld=stat(i, "rShld"), revas=stat(i, "rEvas"), src=where(i)))
BEST_SHIELD = {g: max([x["sdef"] for x in SHIELDS if x["grade"] == g] or [0]) for g in ORDER}

LISTS = [("Light armor, damage (dagger)", "LIGHT", "DAGGER", "damage", False),
         ("Light armor, damage (bow)", "LIGHT", "BOW", "damage", False),
         ("Heavy armor, damage", "HEAVY", "SWORD", "damage", False),
         ("Heavy armor, tank (survivability, set shield included)", "HEAVY", "SWORD", "surv", True)]
out = []; md = ["# Light and heavy armor set tiers (sets only, obtainable only)\n",
    "Damage index = P.Atk x STR bonus x attack speed x DEX bonus x crit, relative to wearing no set (1.00). Survivability index = (P.Def + shield) x HP, relative to a naked tank of the same grade (a P.Def or HP bonus counts multiplicatively, CON bonus scales HP).",
    f"Reference stats STR {REF['STR']}, DEX {REF['DEX']}, CON {REF['CON']}; naked P.Def {BASE_PDEF}; tank HP by grade {TANK_HP}. 'stable' = same order for STR 35-55, DEX 30-50, CON 30-50.\n"]
for title, atype, wt, key, shield in LISTS:
    sets = load(atype)
    md.append(f"\n# {title}")
    for g in ORDER:
        group = [s for s in sets if s["grade"] == g]
        if not group: continue
        def rank(ref):
            for s in group:
                s["ev"] = evaluate(s["bonus"], ents_for(s["st"], wt, shield and s["has_shield"]), s["pieces"], g, ref, shield, BEST_SHIELD.get(g, 0))
            if key == "damage":
                return sorted(group, key=lambda s: (-round(s["ev"]["damage"], 3), -s["ev"]["pdef"]))
            return sorted(group, key=lambda s: (-round(s["ev"]["surv"], 3), -s["ev"]["mdef"]))
        order = rank(REF)
        shown = {s["name"]: dict(s["ev"]) for s in order}
        tops = {tuple(s["name"] for s in rank(dict(STR=a, DEX=b, CON=c))) for a in (35, 45, 55) for b in (30, 40, 50) for c in (30, 40, 50)}
        stable = "stable" if len(tops) == 1 else "order shifts with stats"
        rank(REF)
        md.append(f"\n## {g} ({stable})\n\n| # | Set | pieces | damage | P.Def | HP | survivability | bonuses | source |\n|---|---|---|---|---|---|---|---|---|")
        for n, s in enumerate(order, 1):
            e = shown[s["name"]]; names = ", ".join(items[i].get("name") for _, i in s["pieces"])
            srcs = "; ".join(sorted({f"{items[i].get('name')}: {where(i)}" for _, i in s["pieces"]}))
            lab = label(ents_for(s["st"], wt, shield and s["has_shield"]), s["bonus"])
            md.append(f"| {n} | {s['name']} | {names} | {e['damage']:.3f} | {e['pdef']:.0f} | {e['hp']:.0f} | {e['surv']:.3f} | {lab} | {srcs[:300]} |")
            out.append(dict(list=title, grade=g, rank=n, set=s["name"], damage=round(e["damage"], 3), pdef=round(e["pdef"]), hp=round(e["hp"]), survivability=round(e["surv"], 3),
                            bonuses=lab, pieces=names, stable=stable))
md.append("\n# Shields (for tanks and any 1H + shield class)\n\nRanked by shield defense, then block rate. Every set in the tank list is credited with the best shield of its grade (or its own, if better).")
for g in ORDER:
    sh = sorted([x for x in SHIELDS if x["grade"] == g], key=lambda x: (-x["sdef"], -x["rshld"], x["name"]))
    if not sh: continue
    md.append(f"\n## {g}\n\n| # | Shield | id | Shield Def | block rate | evasion | source |\n|---|---|---|---|---|---|---|")
    for n, x in enumerate(sh, 1):
        md.append(f"| {n} | {x['name']} | {x['id']} | {x['sdef']:g} | {x['rshld']:g} | {x['revas']:g} | {x['src']} |")
        out.append(dict(list="Shields", grade=g, rank=n, set=x["name"], pdef=x["sdef"], bonuses=f"block rate {x['rshld']:g}, evasion {x['revas']:g}", pieces=x["name"]))
with open("armor_tiers.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=["list","grade","rank","set","damage","pdef","hp","survivability","bonuses","pieces","stable"], restval=""); w.writeheader(); w.writerows(out)
open("armor_tiers.md", "w").write("\n".join(md) + "\n")
print(len(out), "rows")
