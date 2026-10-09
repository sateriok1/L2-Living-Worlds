"""HEAL rows for zone_combat.tsv: the heals a class line can cast on itself (and Servitor Heal for summoners), at each level breakpoint of its rotation.

HEAL  line  level  skillId  power  mana  castSeconds  reuseSeconds
  power  the skill's Heal effect power at the highest skill level the line can learn by that character level (the server adds sqrt(M.Atk x multiplier) on top)
  mana   mpConsume + mpInitialConsume (both are spent on a cast)
Self casts: Heal 1011, Battle Heal 1015, Self Heal 1216, Greater Heal 1217, Greater Battle Heal 1218, Major Heal 1401. Servitor Heal 1127 heals a summoner's servitor.
Usage: python3 build_heal_rows.py [out.tsv]   (needs rotation_rows.tsv for the lines and level breakpoints)
"""
import glob, os, sys
import xml.etree.ElementTree as ET
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import l2data as L
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "heal_rows.tsv")
SKILLS = {1011, 1015, 1216, 1217, 1218, 1401, 1127}

def level_value(sk, tables, tag, lv, default=0.0, path=None):
    el = sk.find(path or tag)
    if el is None or el.text is None:
        return default
    txt = el.text.strip()
    if txt.startswith("#"):
        arr = tables.get(txt[1:])
        if not arr:
            return default
        return float(arr[min(lv - 1, len(arr) - 1)])
    return float(txt)

skills = {}
for path in glob.glob(os.path.join(L.DATA, "stats/skills/*.xml")):
    for sk in ET.parse(path).getroot().iter("skill"):
        sid = int(sk.get("id"))
        if sid not in SKILLS:
            continue
        tables = {t.get("name").lstrip("#"): t.text.split() for t in sk.findall("table")}
        eff = sk.find("effects")
        heal = None
        if eff is not None:
            for e in eff.findall("effect"):
                if e.get("name") == "Heal":
                    heal = e.find("power")
        levels = int(sk.get("levels", "1"))
        rows = {}
        for lv in range(1, levels + 1):
            if heal is None or heal.text is None:
                continue
            txt = heal.text.strip()
            power = float(tables[txt.lstrip("#")][min(lv - 1, len(tables[txt.lstrip("#")]) - 1)]) if txt.startswith("#") else float(txt)
            mana = level_value(sk, tables, "mpConsume", lv) + level_value(sk, tables, "mpInitialConsume", lv)
            rows[lv] = (power, mana, level_value(sk, tables, "hitTime", lv) / 1000.0, level_value(sk, tables, "reuseDelay", lv) / 1000.0)
        skills[sid] = rows

names, parent = L.load_classes()
trees = L.load_trees()
by_name = {v.lower(): k for k, v in names.items()}
lines = {}
for line in open(os.path.join(HERE, "rotation_rows.tsv"), encoding="utf-8"):
    f = line.rstrip("\n").split("\t")
    if f[0] == "ROT":
        lines.setdefault(f[1], set()).add(int(f[2]))
out = []
for name, lvls in sorted(lines.items()):
    leaf = by_name.get(name)
    if leaf is None:
        continue
    for lv in sorted(lvls):
        got = L.learned(leaf, lv, trees, parent)
        for sid in sorted(SKILLS):
            sl = got.get(sid)
            if sl and sl in skills.get(sid, {}):
                p, m, c, r = skills[sid][sl]
                out.append("HEAL\t%s\t%d\t%d\t%.0f\t%.0f\t%.1f\t%.1f" % (name, lv, sid, p, m, c, r))
with open(OUT, "w", encoding="utf-8", newline="") as fh:
    fh.write("#HEAL\tline\tlevel\tskillId\tpower\tmana\tcastSeconds\treuseSeconds   (heals a line can cast on itself, and Servitor Heal 1127; see tools/combat_sim/build_heal_rows.py)\n")
    fh.write("\n".join(out) + "\n")
print(len(out), "rows ->", OUT)
