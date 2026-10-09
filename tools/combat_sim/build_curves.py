"""Gear curves for quick calculations: the best P.Atk / M.Atk per weapon type and the best P.Def / M.Def per armor type, as a step
curve over player level. A step is the level where the next grade unlocks (the same gates Living Population uses): 1 NG, 20 D, 40 C,
52 B, 61 A, 76 S. Level 80 adds no gear grade (there is no S80), so it is not a point. Gear is +0, no augment, no buffs.

Sources: weapon_tiers.csv and magic_weapon_tiers.csv (best weapon of each type/grade = rank 1 of the tier list), the armor sets and
jewelry in the datapack (best P.Def set of each armor type and grade, best M.Def jewelry), monsters.json (monster P.Def / M.Def by level).
Naked defence: each EMPTY slot keeps its base (fighters 31/18/12/7/8 + underwear 3 + cloak 1 = 80 P.Def; mystics 15/8/12/7/8 + 4 = 54;
everyone 41 M.Def from the five jewelry slots); a worn piece replaces its slot's base, as the server does.
Output: curves_points.csv / curves_by_level.csv / curves.md."""
import csv, json, os
import armor_tiers as A      # re-writes armor_tiers.csv/md identically as a side effect
import l2data as L

HERE = os.path.dirname(os.path.abspath(__file__))
ORDER = ["NG", "D", "C", "B", "A", "S"]
UNLOCK = {"NG": 1, "D": 20, "C": 40, "B": 52, "A": 61, "S": 76}
POINTS = [UNLOCK[g] for g in ORDER]
BASE = {"fighter": dict(chest=31, legs=18, head=12, feet=7, gloves=8, rest=4), "mystic": dict(chest=15, legs=8, head=12, feet=7, gloves=8, rest=4)}
JEWEL_SLOTS = {"Necklace": 1, "Earring": 2, "Ring": 2}      # how many of each can be worn

def carry(by_grade):
    """best at or below each grade (a lower-grade item can still be worn)"""
    out, best = {}, None
    for g in ORDER:
        v = by_grade.get(g)
        if v is not None and (best is None or v[0] > best[0]): best = v
        out[g] = best
    return out

curves = {}      # name -> {grade: (value, label)}

# ---- weapons: P.Atk and M.Atk of the best weapon (tier-list rank 1) per type/hands/grade
def read(fn):
    return list(csv.DictReader(open(os.path.join(HERE, fn), encoding="utf-8")))
for r in read("weapon_tiers.csv"):
    if r["rank"] != "1": continue
    key = f"{r['weapon_type']} {r['hands']}"
    curves.setdefault(f"P.Atk {key}", {})[r["grade"]] = (float(r["pAtk"]), r["name"])
    curves.setdefault(f"M.Atk {key}", {})[r["grade"]] = (float(r["mAtk"]), r["name"])
for r in read("magic_weapon_tiers.csv"):
    if r["rank"] != "1": continue
    key = f"magic {r['weapon_type']} {r['hands']}"
    curves.setdefault(f"M.Atk {key}", {})[r["grade"]] = (float(r["mAtk"]), r["name"])
    curves.setdefault(f"P.Atk {key}", {})[r["grade"]] = (float(r["pAtk"]), r["name"])

# ---- armor: best P.Def set per type and grade (set bonus included), without and with the best shield
def worn(s, tag): return any(t == tag for t, _ in s["pieces"])
def best_sets(atype, who):
    res = {}
    for s in A.load(atype):
        g = s["grade"]
        chest = next(i for t, i in s["pieces"] if t == "chest")
        one_piece = A.sv(A.items[chest]).get("bodypart") == "onepiece"
        psum = sum(A.stat(i, "pDef") for t, i in s["pieces"] if t != "shield")
        base = BASE[who]
        empty = BASE[who]["rest"] + sum(base[t] for t in ("head", "feet", "gloves") if not worn(s, {"head": "head", "feet": "feet", "gloves": "gloves"}[t]))
        if not (worn(s, "legs") or one_piece): empty += base["legs"]
        own_shield = next((A.stat(i, "sDef") for t, i in s["pieces"] if t == "shield"), 0)
        for with_shield in (False, True):
            ents = A.ents_for(s["st"], "SWORD", with_shield and s["has_shield"])
            mul = lambda k: __import__("math").prod(v for fn, kk, v in ents if kk == k and fn == "mul")
            add = lambda k: sum(v for fn, kk, v in ents if kk == k and fn == "add")
            sdef = max(own_shield, A.BEST_SHIELD.get(g, 0)) if with_shield else 0
            pdef = (psum + empty + sdef + add("pDef")) * mul("pDef")
            mdef_mul = mul("mDef")
            key = (g, with_shield)
            if key not in res or pdef > res[key][0]: res[key] = (pdef, s["name"], mdef_mul)
    return res
for who, atype, label in (("fighter", "HEAVY", "heavy"), ("fighter", "LIGHT", "light"), ("mystic", "MAGIC", "robe")):
    r = best_sets(atype, who)
    naked = sum(BASE[who][k] for k in ("chest", "legs", "head", "feet", "gloves", "rest"))      # no set of this grade yet: wear nothing
    for g in ORDER:
        for ws in (False, True):
            if (g, ws) not in r: r[(g, ws)] = (naked + (A.BEST_SHIELD.get(g, 0) if ws else 0), "(naked)", 1.0)
    for with_shield, suffix in ((False, ""), (True, " +shield")):
        curves[f"P.Def {label}{suffix}"] = {g: (r[(g, with_shield)][0], r[(g, with_shield)][1]) for g in ORDER if (g, with_shield) in r}
    curves[f"_mdefmul {label}"] = {g: (r[(g, False)][2], r[(g, False)][1]) for g in ORDER if (g, False) in r}

# ---- jewelry: best M.Def necklace + 2 earrings + 2 rings per grade; M.Def of a worn piece replaces the slot base
jew = {}
for r in read("jewelry_tiers.csv"):
    if r["rank"] == "1": jew.setdefault(r["slot"], {})[r["grade"]] = (float(r["mDef"]), r["name"])
best_slot = {s: carry(jew[s]) for s in jew}
tot = {}
for g in ORDER:
    parts = [best_slot[s][g] for s in JEWEL_SLOTS if best_slot[s][g]]
    if len(parts) == len(JEWEL_SLOTS):
        tot[g] = (sum(best_slot[s][g][0] * n for s, n in JEWEL_SLOTS.items()), ", ".join(f"{best_slot[s][g][1]}" + (f" x{n}" if n > 1 else "") for s, n in JEWEL_SLOTS.items()))
curves["M.Def jewelry (5 pieces)"] = tot
for label in ("heavy", "light", "robe"):
    mm = curves[f"_mdefmul {label}"]
    curves[f"M.Def {label}"] = {g: (tot[g][0] * (carry(mm)[g][0] if carry(mm)[g] else 1.0), "jewelry x set bonus") for g in tot}
for k in [k for k in curves if k.startswith("_")]: del curves[k]

# ---- carry the best lower-grade value forward and step it over levels
stepped = {name: carry(c) for name, c in curves.items()}
def at_level(name, level):
    g = max((g for g in ORDER if UNLOCK[g] <= level), key=lambda g: UNLOCK[g])
    v = stepped[name].get(g)
    return v[0] if v else None

mon = json.load(open(os.path.join(HERE, "monsters.json")))
def mon_at(level, kind, stat):
    d = mon.get(str(level), {}).get(kind)
    return d[f"{stat}_mean"] if d else None

names = sorted(curves, key=lambda n: (n.split()[0], n))
with open(os.path.join(HERE, "curves_points.csv"), "w", newline="") as f:
    w = csv.writer(f); w.writerow(["curve"] + [f"L{p}" for p in POINTS] + ["best items (NG, D, C, B, A, S)"])
    for n in names:
        w.writerow([n] + [round(at_level(n, p), 1) if at_level(n, p) is not None else "" for p in POINTS] + [" | ".join(f"{g}: {stepped[n][g][1]}" if stepped[n].get(g) else f"{g}: -" for g in ORDER)])
    for kind, stat in (("regular", "pdef"), ("regular", "mdef"), ("boss", "pdef"), ("boss", "mdef")):
        w.writerow([f"monster {kind} {stat[0].upper()}.Def"] + [mon_at(p, kind, stat) for p in POINTS] + ["spawn-weighted mean within +-2 levels (bosses -3..+5)"])
with open(os.path.join(HERE, "curves_by_level.csv"), "w", newline="") as f:
    w = csv.writer(f); w.writerow(["level"] + names + ["monster P.Def", "monster M.Def", "boss P.Def", "boss M.Def"])
    for lv in range(1, 86):
        w.writerow([lv] + [round(at_level(n, lv), 1) if at_level(n, lv) is not None else "" for n in names] + [mon_at(lv, "regular", "pdef"), mon_at(lv, "regular", "mdef"), mon_at(lv, "boss", "pdef"), mon_at(lv, "boss", "mdef")])

md = ["# Gear curves (+0 gear, no augment, no buffs)\n",
      "Step curves: the value at a level is the best gear of the highest grade unlocked by that level. Points are the unlock levels: **1 NG, 20 D, 40 C, 52 B, 61 A, 76 S**. "
      "Level 80 is not a point because no gear grade unlocks there; the gear curve is flat from 76 on (monsters keep rising, see the monster rows).\n",
      "Weapons: the best weapon of each type and grade by the tier lists (auto-attack index for physical, M.Atk for magic). "
      "Armor P.Def: the highest P.Def set of the type at or below the grade, with empty slots at their naked base (fighters 80, mystics 54 P.Def naked; 41 M.Def naked) and set bonuses applied; '+shield' adds the grade's best shield. "
      "Jewelry M.Def: best necklace + 2 earrings + 2 rings. Multiply a P.Atk curve by your own buffs and enchant (see enchant notes) before comparing with the monster rows.\n"]
def table(title, prefix):
    rows = [n for n in names if n.startswith(prefix)]
    if not rows: return
    md.append(f"\n## {title}\n\n| curve | " + " | ".join(f"L{p} ({g})" for p, g in zip(POINTS, ORDER)) + " |\n|---|" + "---|" * len(POINTS))
    for n in rows:
        md.append(f"| {n} | " + " | ".join(f"{at_level(n, p):g}" if at_level(n, p) is not None else "-" for p in POINTS) + " |")
table("P.Atk by weapon", "P.Atk"); table("M.Atk by weapon", "M.Atk"); table("P.Def by armor type", "P.Def"); table("M.Def (jewelry and set bonus)", "M.Def")
md.append("\n## Monsters (spawn-weighted means near the level)\n\n| | " + " | ".join(f"L{p}" for p in POINTS) + " | L80 |\n|---|" + "---|" * (len(POINTS) + 1))
for kind, stat, lab in (("regular", "pdef", "regular P.Def"), ("regular", "mdef", "regular M.Def"), ("boss", "pdef", "boss P.Def"), ("boss", "mdef", "boss M.Def")):
    md.append(f"| {lab} | " + " | ".join(str(mon_at(p, kind, stat) if mon_at(p, kind, stat) is not None else "-") for p in POINTS) + f" | {mon_at(80, kind, stat)} |")
md.append("\n## Best items behind each curve\n")
for n in names:
    md.append(f"- **{n}**: " + "; ".join(f"{g} {stepped[n][g][1]}" for g in ORDER if stepped[n].get(g)))
open(os.path.join(HERE, "curves.md"), "w").write("\n".join(md) + "\n")
print(len(names), "curves ->", "curves_points.csv, curves_by_level.csv, curves.md")
