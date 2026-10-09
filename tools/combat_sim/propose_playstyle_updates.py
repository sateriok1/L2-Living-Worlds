"""Add the sim's best-rotation skills that PhantomPlaystyles.xml lacks, as guarded ROTATION entries.

Per damage line: skills the sim fires (rotations_<line>.json, levels 40/61/76/80) that the covering playstyle does not list
at all get a ROTATION entry (MP_ABOVE guard, minLevel = the level the class first learns it), placed ahead of the entry's
first existing ROTATION so the stronger, higher-tier skills lead. Only additions; nothing existing is touched.
Skipped on purpose: tanks, support lines, dancers and summoners (their XML entries are deliberately simple).
Usage: python3 propose_playstyle_updates.py PhantomPlaystyles.xml
"""
import glob, json, os, re, sys
import xml.etree.ElementTree as ET
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import l2data as L
XML = sys.argv[1]
NAMES = {280: "Burning Fist", 281: "Soul Breaker"}
NOT_DAMAGE = {129, 348}   # Poison (a pure DoT the sim over-values for archers), Spoil Crush (economic, manager-owned)
SKIP = {90, 91, 99, 106, 107, 96, 104, 111, 97, 98, 105, 112, 115, 116, 100}
names, parent = L.load_classes(); trees = L.load_trees()
by_name = {v.lower(): k for k, v in names.items()}
root = ET.parse(XML).getroot()
styles = [(p.get("name"), [int(x) for x in p.get("classIds").replace(" ", "").split(",") if x], {int(s.get("id")) for s in p.findall("skill")}) for p in root.iter("playstyle")]
def style_for(c):
    while c is not None:
        for s in styles:
            if c in s[1]: return s
        c = parent.get(c)
add = {}   # playstyle name -> {id: (name, minLevel, caster)}
for path in sorted(glob.glob(os.path.join(HERE, "rotations_*.json"))):
    stem = os.path.basename(path)[len("rotations_"):-len(".json")]
    if stem.endswith("_none_dagger_front"): line = stem[:-len("_none_dagger_front")].replace("_", " ")
    elif re.search(r"(party|undead|pre_refine|fighterplus|spawn|finitemp|self|vicious|dagger|none)", stem): continue
    else: line = stem.replace("_", " ")
    if line not in by_name or by_name[line] in SKIP: continue
    leaf = by_name[line]; st = style_for(leaf)
    if st is None: continue
    data = json.load(open(path)); keys = sorted(int(k) for k in data)
    for lv in (40, 61, 76, 80):
        k = max([x for x in keys if x <= lv] or keys[:1]); row = data[str(k)][0]
        w = row["windows"]; w = w.get("60") or w[max(w, key=int)]
        nm = {int(i): n for i, n in row["skills"].items()}
        for i in (int(x) for x in w["order_ids"]):
            if i in st[2] or i in NOT_DAMAGE: continue
            first = next((l for l in range(1, 81) if i in L.learned(leaf, l, trees, parent)), None)
            if first is None: continue
            add.setdefault(st[0], {})[i] = (nm.get(i) or NAMES.get(i, "?"), first)
text = open(XML, encoding="utf-8").read()
for ps, skills in add.items():
    m = re.search(r'<playstyle name="%s"[^>]*>.*?</playstyle>' % re.escape(ps), text, re.S)
    block = m.group(0)
    lines = []
    for i, (n, first) in sorted(skills.items(), key=lambda kv: kv[1][1], reverse=True):
        caster = i >= 1000
        lines.append('\t\t<skill id="%d" name="%s" use="ROTATION" when="MP_ABOVE" mpAbove="%d" minLevel="%d" />' % (i, n, 35 if caster else 30, first))
    mm = re.search(r'^[ \t]*<skill [^\n]*use="ROTATION"', block, re.M)
    pos = mm.start() if mm else block.rindex("</playstyle>")
    tail = "" if mm else "\t"
    new = block[:pos] + "\n".join(lines) + "\n" + tail + block[pos:]
    text = text[:m.start()] + new + text[m.end():]
    print(ps, [(i, n, f) for i, (n, f) in skills.items()])
open(XML, "w", encoding="utf-8").write(text)
