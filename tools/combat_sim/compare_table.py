"""Score the CURRENT phantom playstyle table against the simulator optimum.
Usage: [L2_POS=front|behind] python3 compare_table.py <Line> <buffset> [order_ids...]   (default Titan ROTATION from PhantomPlaystyles.xml: 315 190 255<=L45)
Same gear as the optimum's best combo; infinite mana/duration (MP gates mpAbove ignored); CONTROL skills that need a blunt weapon drop out
with a sword. Reports table DPS / best DPS per level at selected windows."""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l2data as L, stats_model as S, combat_sim as C, rotations as R
line, bname = sys.argv[1], sys.argv[2]
args = [x for x in sys.argv[3:] if not x.startswith("--")]
TABLE = [int(x) for x in args] or [315, 190, 255]
MAXLV = {255: 45}
for a in sys.argv[3:]:
    if a.startswith("--max="):            # e.g. --max=100:45,245:45  (skill id : maxLevel from the playstyle entry)
        MAXLV = {int(k): int(v) for k, v in (x.split(":") for x in a.split("=")[1].split(","))}
here = os.path.dirname(os.path.abspath(__file__)); slug = line.lower().replace(" ", "_")
opt = json.load(open(os.path.join(here, R.rot_file(slug, bname))))
names, parent = L.load_classes(); trees = L.load_trees(); sk_all = L.load_skills()
leaf = [k for k, v in names.items() if v == line][0]
W = S.read_csv(os.path.join(here, f"gear_{slug}_weapons.csv")); A = S.read_csv(os.path.join(here, f"gear_{slug}_armor.csv"))
WS = (10, 30, 60, 120)
print(f"[{line} / {bname}] table order {TABLE}")
print("lvl  " + "  ".join(f"{m:>3}s tbl/opt  %" for m in WS) + "   missing-from-table (optimal order @60s)")
tot = []
for lv in sorted(opt, key=int):
    best = max(opt[lv], key=lambda r: r["windows"]["60"]["dps"])
    w = next(x for x in W if f"{x['weapon_name']} {x['variant']}".strip() == best["weapon"]); a = next(x for x in A if x["set_name"] == best["armor"])
    level = int(lv); cid = S.class_at(leaf, level, parent); learned = L.learned(cid, level, trees, parent)
    st, actor, later = R.setup(level, cid, w, a, learned, bname, best.get("focus_id"))
    skills = {sid: sk_all[(sid, l)] for sid, l in learned.items() if (sid, l) in sk_all}
    order = tuple(s for s in TABLE if s in skills and C.usable(skills[s], actor) and level <= MAXLV.get(s, 99))
    tl = []; C.simulate(actor, R.DUMMY, skills, C.Policy(order, 0), 120000, timeline=tl, later=later)
    cells = []
    for m in WS:
        d = R.at(tl, m * 1000)[0] / m; o = best["windows"][str(m)]["dps"]; cells.append(f"{d:7.0f}/{o:<7.0f}{d/o*100:4.0f}%"); tot.append(d / o)
    print(f"{lv:>3}  " + "  ".join(cells) + f"   {best['windows']['60']['order']} vs table {[skills[s].name for s in order]}")
print(f"mean ratio {sum(tot)/len(tot)*100:.1f}%  worst {min(tot)*100:.1f}%")
