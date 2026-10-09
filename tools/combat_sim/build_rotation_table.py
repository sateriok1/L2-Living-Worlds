"""Rotation rows for zone_combat.tsv: the sim's best single-target rotation per line and level breakpoint, for the cold model's
real time-to-kill (ZoneRotationTtk).

ROT  line  level  autoDps  dps5 dps15 dps30 dps45 dps60 dps90 dps120   damage per second against the sim's dummy (P.Def 400, M.Def 300),
                                  over each window; autoDps is the plain auto-attack rate the same gear would do (0 for casters)
ROTCLASS  classId  line          the line a class follows (its own third-class line, or the first line that grows from it)

Only the plain files (rotations_<line>.json) are used; variants (party, finite MP, position) are ignored. Usage: python3 build_rotation_table.py [out.tsv]
"""
import glob, json, os, re, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import l2data as L

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "rotation_rows.tsv")
WINDOWS = [5, 15, 30, 45, 60, 90, 120]
SIM_PDEF = 400.0
BOW = {"sagittarius", "moonlight sentinel", "ghost sentinel"}
CASTER = {"archmage", "soultaker", "arcana lord", "cardinal", "hierophant", "eva's saint", "shillien saint", "mystic muse", "elemental master", "storm screamer", "spectral master", "doom cryer", "dominator"}
names, parent = L.load_classes()
by_name = {v.lower(): k for k, v in names.items()}
lines = {}
for path in glob.glob(os.path.join(HERE, "rotations_*.json")):
    stem = os.path.basename(path)[len("rotations_"):-len(".json")]
    if re.search(r"(party|undead|pre_refine|fighterplus|spawn|finitemp|self|vicious|dagger|none)", stem):
        continue
    name = stem.replace("_", " ")
    if name in by_name:
        lines[name] = (by_name[name], json.load(open(path)))
rows, skills, classes = [], [], {}
for name, (leaf, data) in sorted(lines.items()):
    for key in sorted(int(k) for k in data):
        row = data[str(key)][0]
        win = {int(w): v for w, v in row["windows"].items()}
        dps = [win[w]["dps"] if w in win else win[max(win)]["dps"] for w in WINDOWS]
        st = row["stats"]
        auto = 0.0
        if name not in CASTER:
            interval = (500000.0 + (1500.0 * 333.0 if name in BOW else 0.0)) / st["p_atk_spd"]
            auto = min(76.0 * st["p_atk"] * 2.0 * (1 + st["crit_pct"] / 100.0) / SIM_PDEF / (interval / 1000.0), dps[0])
        rows.append("ROT\t%s\t%d\t%.3f\t%s" % (name, key, auto, "\t".join("%.3f" % d for d in dps)))
        skills.append("ROTSKILLS\t%s\t%d\t%s" % (name, key, ",".join(str(s) for s in sorted(int(i) for i in row["skills"]))))
    cid = leaf
    while cid is not None:
        classes.setdefault(cid, name)       # the first line (alphabetical) that grows from a shared ancestor wins
        cid = parent.get(cid)
with open(OUT, "w", encoding="utf-8", newline="") as f:
    f.write("#ROT\tline\tlevel\tautoDps\tdps5\tdps15\tdps30\tdps45\tdps60\tdps90\tdps120   (sim best rotation vs P.Def 400 / M.Def 300; see build_rotation_table.py)\n")
    f.write("\n".join(rows) + "\n")
    f.write("#ROTCLASS\tclassId\tline\n" + "\n".join("ROTCLASS\t%d\t%s" % (c, n) for c, n in sorted(classes.items())) + "\n")
print(len(lines), "lines,", len(rows), "rows,", len(classes), "classes ->", OUT)
print(sorted(lines))
