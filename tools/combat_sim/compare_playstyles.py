"""Compare the sim's best rotations with PhantomPlaystyles.xml, per class line.

For each line the sim has (rotations_<line>.json) and the playstyle entries that cover its classes, it lists at a few levels:
  sim order   the skills the sim's best rotation fires, in order (the 60 s window, single target, infinite MP)
  xml order   the playstyle's ROTATION/OPENER skills that the class has learned by then, in file order
  missing     in the sim's rotation but not in the XML (the controller may still use them through PhantomSkillFallback)
  extra       in the XML but not used by the sim (a retired or situational skill)
Usage: python3 compare_playstyles.py [PhantomPlaystyles.xml] [out.md]
"""
import glob, json, os, re, sys
import xml.etree.ElementTree as ET
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import l2data as L
XML = sys.argv[1] if len(sys.argv) > 1 else os.path.join(L.DATA, "PhantomPlaystyles.xml")
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "playstyle_compare.md")
LEVELS = (40, 61, 76, 80)
names, parent = L.load_classes()
trees = L.load_trees()
by_name = {v.lower(): k for k, v in names.items()}

styles = []
for ps in ET.parse(XML).getroot().iter("playstyle"):
    ids = [int(x) for x in ps.get("classIds").replace(" ", "").split(",") if x]
    skills = [(int(s.get("id")), s.get("name"), s.get("use"), s.get("maxLevel"), s.get("when") or "") for s in ps.findall("skill")]
    styles.append((ps.get("name"), ids, skills, ps.get("role")))

def style_for(leaf):
    # the entry whose classIds names the leaf class itself, else the nearest ancestor's
    c = leaf
    while c is not None:
        for s in styles:
            if c in s[1]:
                return s
        c = parent.get(c)
    return None

def sim_order(data, level):
    keys = sorted(int(k) for k in data)
    k = max([x for x in keys if x <= level] or keys[:1])
    row = data[str(k)][0]
    win = row["windows"]
    w = win.get("60") or win[max(win, key=int)]
    return [int(i) for i in w["order_ids"]], {int(i): n for i, n in row["skills"].items()}

report = ["# Sim rotation vs PhantomPlaystyles.xml", "", "Single target, infinite MP, 60 s window. `missing` = the sim fires it, the XML does not list it; `extra` = the XML lists it, the sim does not use it.", ""]
summary = []
for path in sorted(glob.glob(os.path.join(HERE, "rotations_*.json"))):
    stem = os.path.basename(path)[len("rotations_"):-len(".json")]
    if stem.endswith("_none_dagger_front"):
        line = stem[:-len("_none_dagger_front")].replace("_", " ")
    elif re.search(r"(party|undead|pre_refine|fighterplus|spawn|finitemp|self|vicious|dagger|none)", stem):
        continue
    else:
        line = stem.replace("_", " ")
    if line not in by_name:
        continue
    leaf = by_name[line]
    data = json.load(open(path))
    st = style_for(leaf)
    report.append("## %s (class %d)" % (line.title(), leaf))
    if st is None:
        report += ["No playstyle entry covers this line (stock behavior).", ""]
        summary.append((line, "no entry", ""))
        continue
    report.append("Playstyle: **%s**" % st[0])
    xml_dmg = [(i, n, u, ml, w) for i, n, u, ml, w in st[2] if u in ("OPENER", "ROTATION", "AOE", "STANCE")]
    diffs = 0
    for lv in LEVELS:
        order, nm = sim_order(data, lv)
        learned = L.learned(leaf, lv, trees, parent)
        xml_ids = [i for i, n, u, ml, w in xml_dmg if i in learned and (ml is None or int(ml) >= lv)]
        missing = [i for i in order if i not in xml_ids]
        extra = [i for i in xml_ids if i not in order]
        same_order = [i for i in xml_ids if i in order] == [i for i in order if i in xml_ids]
        diffs += bool(missing or extra or not same_order)
        xn = {i: n for i, n, u, ml, w in st[2]}
        label = lambda ids: ", ".join("%s (%d)" % (nm.get(i) or xn.get(i) or "?", i) for i in ids) or "-"
        report += ["", "- Level %d" % lv, "  - sim order: %s" % label(order), "  - xml order: %s" % label(xml_ids),
                   "  - missing: %s" % label(missing), "  - extra: %s" % label(extra), "  - same order of the shared skills: %s" % ("yes" if same_order else "NO")]
    report.append("")
    summary.append((line, st[0], "%d of %d levels differ" % (diffs, len(LEVELS))))
head = ["## Summary", "", "| line | playstyle | result |", "|---|---|---|"] + ["| %s | %s | %s |" % s for s in summary] + [""]
open(OUT, "w", encoding="utf-8").write("\n".join(report[:4] + head + report[4:]) + "\n")
print("\n".join("%-20s %-36s %s" % s for s in summary))
