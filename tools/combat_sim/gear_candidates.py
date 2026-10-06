"""Draft gear candidates for a class line, following the user's rules.
Usage: python3 gear_candidates.py <Line> [--types=BLUNT,POLE]   (default: weapon types inferred from the class's mastery passives)
Armor types come from the Light/Heavy/Robe Armor Mastery passives. Weapons: top base weapons per (type, hands, grade) with their special-ability
variants (Focus / Critical Damage / Haste / Health ... are separate items in the data). Armor sets: only sets that raise offence
(STR/DEX bonus or a set skill that changes pAtk, pAtkSpd, critRate, critDmg, accuracy). Output is a draft for the user to confirm."""
import glob, os, sys, collections
import xml.etree.ElementTree as ET
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l2data as L, stats_model as S

MASTERY_TYPES = {"Blunt Mastery": ["BLUNT"], "Polearm Mastery": ["POLE"], "Bow Mastery": ["BOW"], "Dagger Mastery": ["DAGGER"],
                 "Sword Blunt Mastery": ["SWORD", "BLUNT"], "Sword Mastery": ["SWORD"], "Dual Weapon Mastery": ["DUAL"], "Fist Weapon Mastery": ["FIST"]}
ARMOR_MASTERY = {"Light Armor Mastery": "LIGHT", "Heavy Armor Mastery": "HEAVY", "Robe Mastery": "MAGIC", "Robe Armor Mastery": "MAGIC"}
OFFENCE = {"pAtk", "pAtkSpd", "critRate", "critDmg", "critDmgAdd", "accCombat", "rCrit"}
GRADE_ORDER = ["NONE", "D", "C", "B", "A", "S"]
OUR_GRADE = {"NONE": "NG"}

line = sys.argv[1]
names, parent = L.load_classes(); trees = L.load_trees(); sk = L.load_skills()
leaf = [k for k, v in names.items() if v == line][0]
learned = L.learned(leaf, 80, trees, parent)
masteries = [sk[(s, lv)].name for s, lv in learned.items() if (s, lv) in sk]
wtypes = sorted({t for m in masteries for t in MASTERY_TYPES.get(m, [])})
override = next((a.split("=")[1].split(",") for a in sys.argv if a.startswith("--types=")), None)
if override: wtypes = override
atypes = sorted({ARMOR_MASTERY[m] for m in masteries if m in ARMOR_MASTERY})
print(f"{line}: masteries {[m for m in masteries if 'Mastery' in m]}\n  weapon types: {wtypes}\n  armor types: {atypes}\n")

items = {}
for path in glob.glob(os.path.join(L.DATA, "stats/items/*.xml")):
    for it in ET.parse(path).getroot().iter("item"):
        items[int(it.get("id"))] = it

def sets_of(it): return {s.get("name"): s.get("val") for s in it.findall("set")}

# weapons
rows = collections.defaultdict(list)
for iid, it in items.items():
    if it.get("type") != "Weapon": continue
    s = sets_of(it)
    if s.get("weapon_type") not in wtypes or s.get("is_magic_weapon") == "true": continue
    if int(s.get("price", "0") or 0) <= 0 or s.get("is_tradable") == "false" and s.get("is_dropable") == "false": continue
    st = {x.get("type"): float(x.text) for x in it.findall("stats/stat")}
    nm = it.get("name")
    if nm.startswith(("_", "Monster")) or nm.strip().isdigit() or st.get("pAtk", 0) > 600 or st.get("pAtk", 0) <= 0:
        continue      # placeholder / monster / GM rows
    sa = it.find("skills/skill")
    eff = ""
    if sa is not None:
        ents = S.passive_entries(int(sa.get("id")), int(sa.get("level")), s["weapon_type"], "2H" if s.get("bodypart") == "lrhand" else "1H")
        eff = ", ".join(f"{k} {f}{v:g}" for f, k, v in ents if k in OFFENCE or k in ("maxHp",))
    base = it.get("name").split(" - ")[0]
    rows[(s["weapon_type"], "2H" if s.get("bodypart") == "lrhand" else "1H", s.get("crystal_type", "NONE"))].append(
        (st.get("pAtk", 0), base, it.get("name"), iid, st.get("pAtkSpd", 0), st.get("critRate", 0), f"{sa.get('id')}:{sa.get('level')}" if sa is not None else "", eff))
print("WEAPONS (top 3 base weapons per type/hands/grade; variants listed under each)")
for key in sorted(rows, key=lambda k: (k[0], k[1], GRADE_ORDER.index(k[2]) if k[2] in GRADE_ORDER else 9)):
    g = rows[key]; bases = sorted({b for _, b, *_ in g}, key=lambda b: -max(p for p, bb, *_ in g if bb == b))[:3]
    print(f" {key[0]} {key[1]} {OUR_GRADE.get(key[2], key[2])}")
    for b in bases:
        for p, bb, name, iid, spd, crit, skid, eff in sorted([x for x in g if x[1] == b], key=lambda x: x[2]):
            print(f"    {p:5.0f} pAtk spd{spd:4.0f} crit{crit:3.0f}  id {iid:<5} {name:<34} {skid:<8} {eff}")

# armor sets
print("\nARMOR SETS (offence-relevant only)")
for path in sorted(glob.glob(os.path.join(L.DATA, "stats/armorsets/*.xml"))):
    if "clan" in path: continue
    for st_ in ET.parse(path).getroot().iter("set"):
        chest = st_.find("chest")
        if chest is None or int(chest.get("id")) not in items: continue
        ci = items[int(chest.get("id"))]; cs = sets_of(ci)
        at = cs.get("armor_type")
        # full-body armor counts by the legs-less set's armor_type; skip types the class cannot wear
        if at not in atypes: continue
        bonus = {t: int(st_.find(t).get("val")) for t in ("str", "dex", "con") if st_.find(t) is not None}
        effs = []
        for sk_el in st_.findall("skill"):
            if sk_el.get("id") == "3006": continue
            for f, k, v in S.passive_entries(int(sk_el.get("id")), int(sk_el.get("level")), wtypes[0], "2H"):
                if k in OFFENCE: effs.append(f"{k} {f}{v:g}")
        if bonus.get("str", 0) > 0 or bonus.get("dex", 0) > 0 or effs:
            print(f"  {OUR_GRADE.get(cs.get('crystal_type', 'NONE'), cs.get('crystal_type'))} {at:5} {ci.get('name'):<28} {bonus} {effs}")
