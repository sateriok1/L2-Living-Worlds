"""Rough SP-spending priority per class for the phantoms. Not a simulation: a score from what the skill is, and how much our best rotations use it,
divided by its SP cost. Output: sp_priority.csv / sp_priority.md.
A bot walks its class's list top to bottom and learns the first entry it can afford, has the level for, and has the previous level of."""
import csv, glob, json, os, collections
import l2data as L, stats_model as S

names, parent = L.load_classes(); trees = L.load_trees(); sk = L.load_skills()
children = collections.defaultdict(list)
for c, p in parent.items(): children[p].append(c)
TANK, HEALER, BUFFER = {90, 91, 99, 106}, {97, 105, 112}, {98, 115, 116}
here = os.path.dirname(os.path.abspath(__file__))

def role_of(c):
    leaves = leaves_of(c)
    if leaves and all(x in TANK for x in leaves): return "tank"
    if leaves and all(x in HEALER for x in leaves): return "healer"
    if leaves and all(x in BUFFER for x in leaves): return "buffer"
    return "damage"
def leaves_of(c):
    kids = children.get(c, [])
    return [c] if not kids else [l for k in kids for l in leaves_of(k)]

# how much our rotations use each skill: share of rotation windows (all levels) that contain it, per leaf line
use = {}
for c in set(l for k in names for l in leaves_of(k)):
    slug = names[c].lower().replace(" ", "_")
    files = sorted(glob.glob(os.path.join(here, f"rotations_{slug}.json")) or glob.glob(os.path.join(here, f"rotations_{slug}_none_dagger_*.json")))
    cnt = collections.Counter(); total = 0
    for f in files:
        for lvl, entries in json.load(open(f)).items():
            for e in entries:
                for w in e["windows"].values():
                    total += 1
                    for sid in set(w["order_ids"]): cnt[sid] += 1
    use[c] = {sid: n / total for sid, n in cnt.items()} if total else {}

MASTERY_TYPES = {"Blunt Mastery": ["BLUNT"], "Polearm Mastery": ["POLE"], "Bow Mastery": ["BOW"], "Dagger Mastery": ["DAGGER"], "Sword Blunt Mastery": ["SWORD", "BLUNT"],
                 "Sword Mastery": ["SWORD"], "Dual Weapon Mastery": ["DUAL"], "Fist Weapon Mastery": ["DUALFIST", "FIST"]}
ARMOR_MASTERY = {"Light Armor Mastery": "LIGHT", "Heavy Armor Mastery": "HEAVY", "Robe Mastery": "MAGIC", "Robe Armor Mastery": "MAGIC"}
gear = {}      # leaf class id -> (weapon types, armor types) the line's gear files use; absent = unknown
for c in set(l for k in names for l in leaves_of(k)):
    slug = names[c].lower().replace(" ", "_")
    wf, af = os.path.join(here, f"gear_{slug}_weapons.csv"), os.path.join(here, f"gear_{slug}_armor.csv")
    if os.path.exists(wf) and os.path.exists(af):
        wt = {r["weapon_type"] for r in csv.DictReader(open(wf))}
        at = {r["notes"] for r in csv.DictReader(open(af)) if r["notes"]}
        gear[c] = (wt, at)
def mastery_used(c, name):
    leaves = [l for l in leaves_of(c) if l in gear]
    if not leaves or (name not in MASTERY_TYPES and name not in ARMOR_MASTERY): return True
    for l in leaves:
        wt, at = gear[l]
        if name in MASTERY_TYPES and wt & set(MASTERY_TYPES[name]): return True
        if name in ARMOR_MASTERY and ARMOR_MASTERY[name] in at: return True
    return False

WT = [("SWORD", "1H"), ("BOW", "2H"), ("DAGGER", "1H"), ("BLUNT", "2H"), ("POLE", "2H")]
def passive_score(sid, lvl, role):
    best = 0.0
    for wt, h in WT:
        s = 0.0
        for fn, k, v in S.passive_entries(sid, lvl, wt, h):
            m = (v - 1) if fn == "mul" else None
            if k in ("pAtk", "mAtk"): s += (m * 500) if m is not None else v * 0.5
            elif k in ("pAtkSpd", "mAtkSpd"): s += (m * 400) if m is not None else v * 0.1
            elif k in ("critRate", "mCritRate", "critDmg", "blowRate"): s += (m * 200) if m is not None else v * 1.5
            elif k == "accCombat": s += v * 6
            elif k in ("pDef", "mDef", "maxHp", "rEvas", "absorbDam", "regHp"):
                w = 2.0 if role == "tank" else 0.6
                s += w * ((m * 150) if m is not None else v * 0.3)
            elif k in ("maxMp", "regMp", "mReuse"): s += (3 if role in ("healer", "buffer") else 1.5) * ((m * 100) if m is not None else v * 0.02)
        best = max(best, s)
    return best

def score(c, sid, lvl, role):
    d = sk.get((sid, lvl))
    if d is None: return 0, "unknown"
    name = d.name; eff = set(d.effects)
    u = max((use.get(l, {}).get(sid, 0) for l in leaves_of(c)), default=0)
    if u > 0:
        return 60 + 40 * u, "in rotation"
    if d.op == "P":
        if "Mastery" in name:
            return (70, "mastery") if mastery_used(c, name) else (6, "mastery for gear this line does not use")
        return 15 + passive_score(sid, lvl, role), "passive"
    if eff & {"Heal", "HealPercent", "HealOverTime", "ResurrectionSpecial"}:
        return (95 if role == "healer" else 12), "heal"
    if eff & {"PhysicalDamage", "MagicalDamage", "HpDrain", "FatalBlow", "EnergyDamage", "Backstab"}:
        return (30 if role in ("damage",) else 18), "attack skill (not in rotation)"
    if eff & {"TargetMe", "TargetMeProbability", "GetAgro"}:
        return (65 if role == "tank" else 5), "aggro"
    if eff & {"Stun", "Root", "Debuff", "PhysicalMute", "TargetCancel", "DispelBySlot"}:
        return 22, "control / debuff"
    if "Buff" in eff or "DefenceTrait" in eff or "ImmobileBuff" in eff:
        party = d.target in ("PARTY", "PARTY_MEMBER", "PARTY_OTHER", "PARTY_NOTME", "CLAN") or "PARTY" in (d.target or "")
        if role == "buffer": return (70 if party else 45), "buff"
        if role == "tank": return 40, "self buff / defence"
        return 25, "self buff"
    if eff & {"Lucky", "OpenCommonRecipeBook", "Relax", "RunAway", "SummonNpc"}:
        return 3, "utility"
    return 10, "other"

import xml.etree.ElementTree as ET
tree_sp = collections.defaultdict(list)       # class id -> (skill id, level, getLevel, sp)
for path in glob.glob(os.path.join(L.DATA, "stats/players/skillTrees/*Class/*.xml")):
    for st in ET.parse(path).getroot().iter("skillTree"):
        if st.get("type") != "classSkillTree": continue
        for e in st.findall("skill"):
            tree_sp[int(st.get("classId"))].append((int(e.get("skillId")), int(e.get("skillLevel")), int(e.get("getLevel")), int(e.get("levelUpSp", "0"))))

rows = []
for c in sorted(names):
    chain = []; cc = c
    while cc is not None: chain.append(cc); cc = parent.get(cc)
    entries = {}
    for k in chain:
        for sid, lv, gl, spc in tree_sp.get(k, []):
            entries.setdefault((sid, lv), (gl, spc))
    if not entries: continue
    role = role_of(c)
    per = collections.defaultdict(list)
    for (sid, lv), (gl, spc) in entries.items(): per[sid].append((lv, gl, spc))
    for sid, lst in per.items():
        for lv, gl, spc in sorted(lst):
            d = sk.get((sid, lv))
            if d is None: continue
            base, why = score(c, sid, lv, role)
            val = base if lv == min(x[0] for x in lst) else base * 0.5      # later levels of a skill add less than learning it
            eff = val / max(1.0, spc / 1000.0)
            rows.append(dict(class_id=c, class_name=names[c], role=role, skill_id=sid, skill=d.name, level=lv, learn_level=gl, sp=spc, value=round(val, 1), why=why, efficiency=round(eff, 3)))
# tiers within a class by value, then the order a bot should walk
out = []
for c in sorted({r["class_id"] for r in rows}):
    cr = [r for r in rows if r["class_id"] == c]
    for r in cr: r["tier"] = "A" if r["value"] >= 60 else ("B" if r["value"] >= 30 else ("C" if r["value"] >= 12 else "skip"))
    cr.sort(key=lambda r: ({"A": 0, "B": 1, "C": 2, "skip": 3}[r["tier"]], -r["efficiency"], r["learn_level"], r["level"]))
    for i, r in enumerate(cr, 1): r["priority"] = i
    out += cr
cols = ["class_id", "class_name", "role", "priority", "tier", "skill_id", "skill", "level", "learn_level", "sp", "value", "why", "efficiency"]
with open("sp_priority.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=cols); w.writeheader(); w.writerows(out)
md = ["# SP spending priority (rough)\n", "Per class, learn from the top: take the first entry you have the level and SP for and already know the previous level of. Tier A = rotation skills and masteries, B = useful, C = minor, skip = not worth SP. Value is a rough score (rotation use, passive stats, role); efficiency = value per 1000 SP. Classes include the skills of their earlier classes.\n"]
for c in sorted({r["class_id"] for r in out}):
    cr = [r for r in out if r["class_id"] == c and r["tier"] != "skip"]
    md.append(f"\n## {names[c]} ({cr[0]['role']})\n\n| # | tier | skill | lvl | learn at | SP | value | why |\n|---|---|---|---|---|---|---|---|")
    for r in cr: md.append(f"| {r['priority']} | {r['tier']} | {r['skill']} | {r['level']} | {r['learn_level']} | {r['sp']} | {r['value']} | {r['why']} |")
open("sp_priority.md", "w").write("\n".join(md) + "\n")
print(len(out), "rows,", len({r['class_id'] for r in out}), "classes")
