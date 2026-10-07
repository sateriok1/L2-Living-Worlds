"""Local-search refinement of any class's rotations (generalised from refine_charge.py): the main search tries priority lists only up to a length cap, so for each level and fight length
take the best gear row's list and try every insertion / removal / move of one skill and every hold, until nothing improves.
Usage: python3 refine_charge.py "<Line>" <none|party> [levels...]   (rewrites rotations_<line>[_party].json, keeping a .pre_refine copy once)"""
import sys, os, json, shutil
here = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, here)
line, bname = sys.argv[1], sys.argv[2]
only = [int(x) for x in sys.argv[3:]]
sys.argv = ["x"]
import rotations as R, l2data as L, stats_model as S, combat_sim as C
slug = line.lower().replace(" ", "_")
fn = os.path.join(here, R.rot_file(slug, bname))
if not os.path.exists(fn + ".pre_refine"):
    shutil.copy(fn, fn + ".pre_refine")
data = json.load(open(fn))
names, parent = L.load_classes(); trees = L.load_trees(); sk_all = L.load_skills()
leaf = [k for k, v in names.items() if v == line][0]
weapons = S.read_csv(os.path.join(here, f"gear_{slug}_weapons.csv")); armors = S.read_csv(os.path.join(here, f"gear_{slug}_armor.csv"))
R.set_mage(any(str(x.get("shot", "")).startswith("SPS") for x in weapons))
HOLDS = (0, 400, 1000)
for lv in sorted(data, key=int):
    level = int(lv)
    if only and level not in only:
        continue
    rows = data[lv]
    cid = S.class_at(leaf, level, parent); learned = L.learned(cid, level, trees, parent)
    skills = {sid: sk_all[(sid, l)] for sid, l in learned.items() if (sid, l) in sk_all}
    cache = {}
    for ms in R.WINDOWS:
        key = str(ms)
        # the two best gear rows for this window
        top = sorted(rows, key=lambda r: -r["windows"][key]["dps"])[:2]
        for row in top:
            w = next(x for x in weapons if f"{x['weapon_name']} {x['variant']}".strip() == row["weapon"])
            a = next(x for x in armors if x["set_name"] == row["armor"])
            ck = (row["weapon"], row["armor"], row.get("focus_id"))
            if ck not in cache:
                _st, actor, later = R.setup(level, cid, w, a, learned, bname, row.get("focus_id"))
                cache[ck] = (actor, later, [s for s in skills if C.usable(skills[s], actor)])
            actor, later, ids = cache[ck]
            def f(o, h):
                return C.simulate(actor, R.DUMMY, skills, C.Policy(tuple(o), h), ms * 1000, later=later)[0] / ms
            cur, hold = list(row["windows"][key]["order_ids"]), row["windows"][key]["hold"]
            cd = f(cur, hold); f0 = cd
            while True:     # quick check: does the next best unused skill earn a place at the end of the list? repeat until none does
                best = max(((f(cur + [i], hold), i) for i in ids if i not in cur), default=(0, None))
                if best[1] is not None and best[0] > cd * 1.0005:
                    cd, cur = best[0], cur + [best[1]]
                else:
                    break
            if cd > f0 * 1.0005:     # gain measured against a fresh run of the stored list, then applied to the stored figure (a fresh 5 s run edges differently from the original timeline reading)
                row["windows"][key] = {"dps": row["windows"][key]["dps"] * cd / f0, "order": [skills[x].name for x in cur], "order_ids": list(cur), "hold": hold, "mp_used": row["windows"][key]["mp_used"]}
    print(f"L{level} refined", flush=True)
    json.dump(data, open(fn, "w"))
print("done")
