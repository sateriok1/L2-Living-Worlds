"""Writes zone_combat.tsv for the Living Population cold-state model: the hunting-area monster averages (zone_monsters.csv) and the
gear curves (curves_points.csv) in one small file the module reads at start. Run build_zone_monsters.py, build_curves.py and build_buff_factors.py first.
Usage: python3 build_zone_combat.py [out.tsv]"""
import csv, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "zone_combat.tsv")
curves = {r["curve"]: r for r in csv.DictReader(open(os.path.join(HERE, "curves_points.csv"), encoding="utf-8"))}
GR = ["L1", "L20", "L40", "L52", "L61", "L76"]            # NG D C B A S
def row(name):
    r = curves[name]
    return [float(r[g]) if r[g] not in ("", None) else None for g in GR]
def fill(vals, fallback):
    return [v if v is not None else fb for v, fb in zip(vals, fallback)]
CURVES = [
    ("patk_melee", fill(row("P.Atk SWORD 2H"), row("P.Atk SWORD 1H"))),
    ("patk_bow", row("P.Atk BOW 2H")),
    ("matk_mage", fill(row("M.Atk magic BLUNT 2H"), row("M.Atk magic BLUNT 1H"))),
    ("pdef_tank", row("P.Def heavy +shield")), ("pdef_melee", row("P.Def heavy")), ("pdef_light", row("P.Def light")), ("pdef_robe", row("P.Def robe")),
    ("mdef_heavy", row("M.Def heavy")), ("mdef_light", row("M.Def light")), ("mdef_robe", row("M.Def robe")),
]
seen = set()
with open(out, "w", encoding="utf-8", newline="") as f:
    f.write("#CURVE\tname\tNG\tD\tC\tB\tA\tS   (+0 gear, best of each grade; see tools/combat_sim/curves.md)\n")
    f.write("#ZONE\tname\tminLevel\tmaxLevel\tmobLevelAvg\thp\tpDef\tmDef\tpAtk\tmAtk   (spawn-weighted averages; see zone_monsters.md)\n")
    for n, v in CURVES:
        f.write("CURVE\t%s\t%s\n" % (n, "\t".join("%g" % x for x in v)))
    for r in csv.DictReader(open(os.path.join(HERE, "zone_monsters.csv"), encoding="utf-8")):
        if r["zone"] in seen or not r["hp"]:
            continue
        seen.add(r["zone"])
        f.write("ZONE\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" % (r["zone"], r["min_level"], r["max_level"], r["mob_level_avg"], r["hp"], r["p_def"], r["m_def"], r["p_atk"], r["m_atk"]))
    bf = os.path.join(HERE, "buff_factors.tsv")
    if os.path.exists(bf):                                  # full-buffer-party multipliers by role and level (buffed leveling option)
        f.write("#BUFF\trole\tlevel\tdamageMult\tpDefMult\tmDefMult   (full buffer party; see tools/combat_sim/build_buff_factors.py)\n")
        for line in open(bf, encoding="utf-8"):
            if line.startswith("BUFF"):
                f.write(line)
print(len(seen), "zones,", len(CURVES), "curves ->", out)
