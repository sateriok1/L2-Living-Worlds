"""Do buffs change the optimal rotation or gear?  Usage: python3 compare_buffs.py <line> <buffset>
For each level and window it takes the no-buff best (gear + skill priority) and asks how much DPS is lost, under the buffed
stats, by sticking with it: (a) rotation regret = no-buff priority vs buffed-best priority, same gear;
(b) gear regret = no-buff gear vs buffed-best gear, each with its own best priority. Regret above 1% is 'meaningful'."""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l2data as L, stats_model as S, combat_sim as C, rotations as R

line, bname = sys.argv[1], sys.argv[2]
here = os.path.dirname(os.path.abspath(__file__))
slug = line.lower().replace(" ", "_")
base = json.load(open(os.path.join(here, f"rotations_{slug}.json")))
buffd = json.load(open(os.path.join(here, f"rotations_{slug}_{bname}.json")))
names, parent = L.load_classes(); trees = L.load_trees(); sk_all = L.load_skills()
leaf = [k for k, v in names.items() if v == line][0]
W = S.read_csv(os.path.join(here, f"gear_{slug}_weapons.csv")); A = S.read_csv(os.path.join(here, f"gear_{slug}_armor.csv"))
buffs = R.buff_set(bname)
THRESH = 0.01


def find(rows, label_w, label_a):
    for r in rows:
        if r["weapon"] == label_w and r["armor"] == label_a:
            return r


def dps_of(level, weapon_label, armor_label, order_ids, hold, ms):
    w = next(x for x in W if f"{x['weapon_name']} {x['variant']}".strip() == weapon_label)
    a = next(x for x in A if x["set_name"] == armor_label)
    cid = S.class_at(leaf, level, parent)
    learned = L.learned(cid, level, trees, parent)
    st = S.compute(cid, level, w, a, learned, buffs)
    actor = R.build_actor(st, w)
    skills = {sid: sk_all[(sid, lv)] for sid, lv in learned.items() if (sid, lv) in sk_all}
    tl = []
    C.simulate(actor, R.DUMMY, skills, C.Policy(tuple(order_ids), hold), R.WINDOWS[-1] * 1000, timeline=tl)
    return R.at(tl, ms * 1000)[0] / ms


def best(rows, ms):
    b = max(rows, key=lambda r: r["windows"][ms]["dps"])
    return b, b["windows"][ms]


rot_bad, gear_bad, cells = [], [], 0
gain = []
for lv in buffd:
    if lv not in base:
        continue
    for ms in buffd[lv][0]["windows"]:
        cells += 1
        nb, nw = best(base[lv], ms)           # no-buff best
        bb, bw = best(buffd[lv], ms)          # buffed best
        # (a) same gear (buffed best), no-buff priority
        d_old = dps_of(int(lv), bb["weapon"], bb["armor"], nw["order_ids"], nw["hold"], int(ms))
        rot_regret = 1 - d_old / bw["dps"]
        # (b) no-buff gear with its own best buffed priority
        other = find(buffd[lv], nb["weapon"], nb["armor"])
        gear_regret = 1 - other["windows"][ms]["dps"] / bw["dps"] if other else 1.0
        gain.append(bw["dps"] / nw["dps"])
        if rot_regret > THRESH:
            rot_bad.append((int(lv), int(ms), rot_regret, nw["order"], bw["order"], bw["dps"]))
        if gear_regret > THRESH:
            gear_bad.append((int(lv), int(ms), gear_regret, f"{nb['weapon']} + {nb['armor']}", f"{bb['weapon']} + {bb['armor']}"))
print(f"{line} with '{bname}' buffs: {cells} level/window cells; DPS gain from buffs {min(gain):.2f}x to {max(gain):.2f}x (median {sorted(gain)[len(gain)//2]:.2f}x)")
print(f"rotation changes worth >{THRESH:.0%}: {len(rot_bad)} cells;  gear changes worth >{THRESH:.0%}: {len(gear_bad)} cells")
for x in rot_bad[:12]:
    print(f"  L{x[0]} {x[1]:>3}s rotation regret {x[2]:.1%}: was {x[3]}  -> now {x[4]}")
for x in gear_bad[:12]:
    print(f"  L{x[0]} {x[1]:>3}s gear regret {x[2]:.1%}: was {x[3]}  -> now {x[4]}")
