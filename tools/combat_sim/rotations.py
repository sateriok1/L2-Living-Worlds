"""Best single-target rotation per level breakpoint and combat window, for one leaf line.

For each breakpoint level: every wearable weapon x armor combination that is not dominated, every skill ordering and hold
window; one 120 s run per policy gives cumulative damage for the 5 s .. 120 s windows. Mana is simulated: max MP and regen
come from the class template, MEN and level; a skill is skipped when MP is short, so mana-starved rotations lose damage.
Usage: python3 rotations.py Titan [level ...]
"""
import itertools, json, os, sys, bisect
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l2data as L, stats_model as S, combat_sim as C

WINDOWS = list(range(5, 125, 5))
MODEL_HP = False        # HP costs ignored: phantom health is assumed maintained (user decision); set True to charge them
DUMMY = C.Dummy()          # defence scales every hit equally, so it cannot change which rotation is best
HOLDS = (0, 400, 1000)


SPAWN_BUFFS = [1204, 1068, 1086, 1077, 1242, 1240, 1268, 1087, 1040, 1243, 1044, 1259, 1035, 1036, 1045, 1048, 1062]   # PhantomBuffs.applyFullBuffs for a damage dealer, max level


def buff_set(name):
    """(skill id, level) pairs. 'spawn' = what a spawned phantom gets (max level, as the server applies them);
    'fighterplus' = the Scheme Buffer FIGHTER_GROUP preset at the levels it lists."""
    if name == "none":
        return ()
    els = S._skill_elements()
    if name == "spawn":
        return tuple((i, int(els[i].get("levels"))) for i in SPAWN_BUFFS if i in els)
    if name in ("fighterplus", "fighterplus_max"):
        import re
        t = open(os.path.join(L.DATA, "SchemeBufferSkills.xml")).read()
        body = re.search(r'<category type="FIGHTER_GROUP">(.*?)</category>', t, re.S).group(1)
        out = []
        for i, lv in re.findall(r'<buff id="(\d+)" level="(\d+)"', body):
            i, lv = int(i), int(lv)
            out.append((i, int(els[i].get("levels")) if name.endswith("_max") else lv))
        return tuple(out)
    raise SystemExit("unknown buff set " + name)


def build_actor(st, w):
    return C.Actor(patk=st["p_atk"], patk_spd=st["p_atk_spd"], matk=1, matk_spd=333, mp_max=st["mp_max"],
                   mp_regen_3s=st["mp_regen_3s"], weapon=w["weapon_type"], crit=min(1.0, st["crit_pct"] / 100.0),
                   str_bonus=st["str_bonus"], crit_mul=st["crit_mul"], crit_add=st["crit_add"], reuse_mul=st["reuse_mul"],
                   mp_mul=st["mp_mul"], hp_max=st["hp_max"] if MODEL_HP else 0.0, hp_regen_3s=st["hp_regen_3s"])


def pareto(rows):
    """Drop combos that are no better on P.Atk, attack speed, auto crit, STR bonus and MP than another combo."""
    keys = ("p_atk", "p_atk_spd", "crit_pct", "str_bonus", "mp_max", "mp_regen_3s", "crit_mul") + (("hp_max", "hp_regen_3s") if MODEL_HP else ())
    keep = []
    for i, a in enumerate(rows):
        dom = False
        for j, b in enumerate(rows):
            if i != j and all(b[1][k] >= a[1][k] for k in keys) and any(b[1][k] > a[1][k] for k in keys):
                dom = True
                break
        if not dom:
            keep.append(a)
    return keep


def at(tl, ms):
    i = bisect.bisect_right([x[0] for x in tl], ms) - 1
    return (tl[i][1], tl[i][2]) if i >= 0 else (0.0, 0.0)


def solve_level(line, leaf_id, level, weapons, armors, names, parent, trees, sk_all, buffs=()):
    cid = S.class_at(leaf_id, level, parent)
    learned = L.learned(cid, level, trees, parent)
    rows = []
    for w, a in S.options(weapons, armors, level):
        rows.append(((w, a), S.compute(cid, level, w, a, learned, buffs)))
    rows = pareto(rows)
    out = []
    for (w, a), st in rows:
        actor = build_actor(st, w)
        skills = {sid: sk_all[(sid, lv)] for sid, lv in learned.items() if (sid, lv) in sk_all}
        ids = [sid for sid, s in skills.items() if C.usable(s, actor)]
        best = {ms: None for ms in WINDOWS}
        for k in range(0, min(6, len(ids)) + 1):
            for perm in itertools.permutations(ids, k):
                for h in (HOLDS if k > 1 else (0,)):
                    tl, pol = [], C.Policy(perm, h)
                    _, _, casts = C.simulate(actor, DUMMY, skills, pol, WINDOWS[-1] * 1000, timeline=tl)
                    for ms in WINDOWS:
                        dmg, mp = at(tl, ms * 1000)
                        if best[ms] is None or dmg > best[ms][0] + 1e-9:
                            best[ms] = (dmg, perm, h, mp)
        out.append({"weapon": f"{w['weapon_name']} {w['variant']}".strip(), "armor": a["set_name"], "stats": st,
                    "skills": {sid: skills[sid].name for sid in ids},
                    "windows": {ms: {"dps": b[0] / ms, "order": [skills[x].name for x in b[1]], "order_ids": list(b[1]), "hold": b[2], "mp_used": b[3]}
                                for ms, b in best.items()}})
    return out


if __name__ == "__main__":
    args = [x for x in sys.argv[1:] if not x.startswith("--")]
    bname = next((x.split("=")[1] for x in sys.argv if x.startswith("--buffs=")), "none")
    buffs = buff_set(bname)
    line = args[0]
    here = os.path.dirname(os.path.abspath(__file__))
    names, parent = L.load_classes()
    trees = L.load_trees()
    sk_all = L.load_skills()
    leaf = [k for k, v in names.items() if v == line][0]
    slug = line.lower().replace(" ", "_")
    weapons = S.read_csv(os.path.join(here, f"gear_{slug}_weapons.csv"))
    armors = S.read_csv(os.path.join(here, f"gear_{slug}_armor.csv"))
    bps = json.load(open(os.path.join(here, "breakpoints.json")))[line]
    # skill breakpoints plus the levels where a new gear grade becomes wearable, plus the cap
    levels = [int(x) for x in args[1:]] or sorted({b["level"] for b in bps} | {S.WEAR_LEVEL[g] for g in S.GRADES if S.WEAR_LEVEL[g] > 1} | {80})
    result = {}
    for lv in levels:
        result[lv] = solve_level(line, leaf, lv, weapons, armors, names, parent, trees, sk_all, buffs)
        best = max(result[lv], key=lambda r: r["windows"][60]["dps"])
        print(f"L{lv}: {len(result[lv])} combos; best@60s {best['weapon']} + {best['armor']}: "
              f"{best['windows'][60]['dps']:.0f} dps, order {best['windows'][60]['order']}, mp used {best['windows'][60]['mp_used']:.0f}/{best['stats']['mp_max']:.0f}", flush=True)
    json.dump(result, open(os.path.join(here, f"rotations_{slug}.json" if bname == "none" else f"rotations_{slug}_{bname}.json"), "w"), indent=1)
