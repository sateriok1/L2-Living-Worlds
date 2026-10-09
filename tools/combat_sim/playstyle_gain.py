"""Expected single-target DPS gain of the updated PhantomPlaystyles.xml over the old one, per class line, from the simulator.
Each XML's learned, level-legal OPENER/ROTATION skills (file order) become a priority list run through the same sim
(best gear for that line, no buffs, infinite MP, 60 s) and are compared with the sim's best rotation.
Usage: python3 playstyle_gain.py old.xml new.xml out.md"""
import glob, json, os, re, sys
import xml.etree.ElementTree as ET
os.environ["L2_POS"] = "front"
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import l2data as L, stats_model as S, combat_sim as C, rotations as R
OLD, NEW, OUT = sys.argv[1:4]
names, parent = L.load_classes(); trees = L.load_trees(); sk_all = L.load_skills()
by_name = {v.lower(): k for k, v in names.items()}
def load(path):
    out = []
    for ps in ET.parse(path).getroot().iter("playstyle"):
        out.append(([int(x) for x in ps.get("classIds").replace(" ", "").split(",") if x],
                    [(int(s.get("id")), s.get("minLevel"), s.get("maxLevel")) for s in ps.findall("skill") if s.get("use") in ("OPENER", "ROTATION")]))
    return out
def style(styles, leaf):
    c = leaf
    while c is not None:
        for ids, sk in styles:
            if c in ids: return sk
        c = parent.get(c)
    return []
old, new = load(OLD), load(NEW)
rows, tot = [], []
for line, bname_suffix in sorted({(re.sub(r"_none_dagger_front$", "", os.path.basename(p)[10:-5]).replace("_", " "), "none_dagger" if p.endswith("_none_dagger_front.json") else "none")
                 for p in glob.glob(os.path.join(HERE, "rotations_*.json")) if re.fullmatch(r"rotations_[a-z_']+?(_none_dagger_front)?\.json", os.path.basename(p))}):
    if line not in by_name: continue
    leaf = by_name[line]; slug = line.replace(" ", "_")
    if not os.path.exists(os.path.join(HERE, "gear_%s_weapons.csv" % slug)): continue
    data = json.load(open(os.path.join(HERE, "rotations_%s.json" % (slug if bname_suffix == "none" else slug + "_none_dagger_front"))))
    weapons = S.read_csv(os.path.join(HERE, "gear_%s_weapons.csv" % slug)); armors = S.read_csv(os.path.join(HERE, "gear_%s_armor.csv" % slug))
    R.POSITION = "front" if bname_suffix != "none" else ""
    R.set_mage(any(str(x.get("shot", "")).startswith("SPS") for x in weapons))
    res = []
    keys = sorted(int(k) for k in data)
    for lv in (40, 61, 76, 80):
        k = max([x for x in keys if x <= lv] or keys[:1])
        best = max(data[str(k)], key=lambda r: r["windows"]["60"]["dps"])
        cid = S.class_at(leaf, k, parent); learned = L.learned(cid, k, trees, parent)
        opt = [(w, a) for w, a in S.options(weapons, armors, k) if ("%s %s" % (w["weapon_name"], w["variant"])).strip() == best["weapon"] and a["set_name"] == best["armor"]]
        if not opt: continue
        w, a = opt[0]
        _st, actor, later = R.setup(k, cid, w, a, learned, bname_suffix, best.get("focus_id"))
        skills = {sid: sk_all[(sid, lv_)] for sid, lv_ in learned.items() if (sid, lv_) in sk_all}
        def dps(styles):
            order = [i for i, lo, hi in style(styles, leaf) if i in skills and C.usable(skills[i], actor) and (not lo or int(lo) <= k) and (not hi or int(hi) >= k)]
            order = list(dict.fromkeys(order))
            return C.simulate(actor, R.DUMMY, skills, C.Policy(tuple(order), 0), 60000, later=later)[0] / 60.0
        d0, d1 = dps(old), dps(new)
        res.append((k, d0, d1, best["windows"]["60"]["dps"]))
    if res: rows.append((line, res))
lines = ["| class line | lvl 40 | lvl 61 | lvl 76 | lvl 80 | gap left to sim best (lvl 80) |", "|---|---|---|---|---|---|"]
for line, res in rows:
    cells = {k: "%+.0f%%" % (100 * (d1 / d0 - 1)) if d0 else "n/a" for k, d0, d1, b in res}
    last = res[-1]
    lines.append("| %s | %s | %s | %s | %s | %.0f%% |" % (line.title(), *[cells.get(x, "-") for x in (40, 61, 76, 80)], 100 * (last[3] / last[2] - 1) if last[2] else 0))
open(OUT, "w").write("\n".join(lines) + "\n"); print("\n".join(lines))
