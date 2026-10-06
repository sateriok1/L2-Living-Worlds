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
MODEL_MP = os.environ.get("L2_MP", "infinite") == "finite"   # first tests assume infinite mana (user decision); L2_MP=finite charges MP and regen
TIMED_BUFFS = os.environ.get("L2_BUFF_TIME", "infinite") == "finite"   # first tests assume buffs never expire; finite = Rage ends at 90 s
MP_SUFFIX = ("" if not MODEL_MP else "_finitemp") + ("_timed" if TIMED_BUFFS else "")
MODEL_HP = False        # HP costs ignored: phantom health is assumed maintained (user decision); set True to charge them
DUMMY = C.Dummy()          # defence scales every hit equally, so it cannot change which rotation is best
HOLDS = (0, 400, 1000)
MAX_POLICIES = 20000      # cap on priority orders tried per gear combo; longer orders are dropped when a class has many usable skills


def max_len_for(n_skills):
    """Longest priority list whose full permutation count (x hold options) stays under MAX_POLICIES."""
    import math
    total, k = 1, 0
    while k < min(6, n_skills):
        nxt = total + math.perm(n_skills, k + 1) * len(HOLDS)
        if nxt > MAX_POLICIES:
            break
        total, k = nxt, k + 1
    return k


SPAWN_BUFFS = [1204, 1068, 1086, 1077, 1242, 1240, 1268, 1087, 1040, 1243, 1044, 1259, 1035, 1036, 1045, 1048, 1062]   # PhantomBuffs.applyFullBuffs for a damage dealer, max level


_PARENT = _TREES = None
RAGE_ID, RAGE_MS = 94, 90000      # Rage: abnormalTime 90 s, so it only covers the first 90 s of a long fight
VICIOUS_ID = 312                  # Vicious Stance toggle: +critDmgAdd, upkeep 0.8 * (level-1)/7.5 MP per second


def split_name(name):
    """'fighterplus_max_self_vicious' -> ('fighterplus_max', rage, vicious)."""
    vicious = name.endswith("_self_vicious")
    rage = vicious or name.endswith("_self")
    base = name.replace("_self_vicious", "").replace("_self", "")
    return base, rage, vicious


def buff_set(name):
    """Permanent buffs: (skill id, level) pairs; level None = the level the class has learned. 'spawn' = what a spawned phantom gets (max level, as the server applies them);
    'fighterplus' = the Scheme Buffer FIGHTER_GROUP preset at the levels it lists."""
    base, rage, vicious = split_name(name)
    if (base, rage, vicious) != (name, False, False):
        return buff_set(base) + (((VICIOUS_ID, None),) if vicious else ())
    if name in ("none", "party"):
        return ()                      # 'party' is level- and weapon-dependent: setup() asks party_buffs for it
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


def temp_set(name):
    return ((RAGE_ID, None),) if split_name(name)[1] else ()


def setup(level, cid, w, a, learned, bname):
    """(stats with every buff, the actor, the actor that continues after Rage expires or None)."""
    perm, temp = buff_set(bname), temp_set(bname)
    if split_name(bname)[0] == "party":
        import party_buffs
        global _PARENT, _TREES
        if _PARENT is None:
            _PARENT, _TREES = L.load_classes()[1], L.load_trees()
        perm = tuple(party_buffs.party_buffs(level, w["weapon_type"], w["hands"], _PARENT, _TREES)) + perm
    st = S.compute(cid, level, w, a, learned, perm + temp)
    drain = 0.8 * (level - 1) / 7.5 if (split_name(bname)[2] and VICIOUS_ID in learned) else 0.0
    actor = build_actor(st, w)
    actor.mp_drain_per_s = drain
    later = None
    if temp and RAGE_ID in learned and TIMED_BUFFS:
        after = build_actor(S.compute(cid, level, w, a, learned, perm), w)
        after.mp_drain_per_s = drain
        later = (RAGE_MS, after)
    return st, actor, later


def build_actor(st, w):
    return C.Actor(patk=st["p_atk"], patk_spd=st["p_atk_spd"], matk=1, matk_spd=333, mp_max=st["mp_max"] if MODEL_MP else 1e12,
                   mp_regen_3s=st["mp_regen_3s"] if MODEL_MP else 0.0, weapon=w["weapon_type"], crit=min(1.0, st["crit_pct"] / 100.0),
                   str_bonus=st["str_bonus"], crit_mul=st["crit_mul"], crit_add=st["crit_add"], reuse_mul=st["reuse_mul"],
                   mp_mul=st["mp_mul"], hp_max=st["hp_max"] if MODEL_HP else 0.0, hp_regen_3s=st["hp_regen_3s"])


def pareto(rows):
    """Drop combos that are no better on P.Atk, attack speed, auto crit, STR bonus and MP than another combo."""
    keys = ("p_atk", "p_atk_spd", "crit_pct", "str_bonus", "crit_mul") + (("mp_max", "mp_regen_3s") if MODEL_MP else ()) + (("hp_max", "hp_regen_3s") if MODEL_HP else ())
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


def rot_file(slug, bname):
    return f"rotations_{slug}{'' if bname == 'none' else '_' + bname}{MP_SUFFIX}.json"


def at(tl, ms):
    i = bisect.bisect_right([x[0] for x in tl], ms) - 1
    return (tl[i][1], tl[i][2]) if i >= 0 else (0.0, 0.0)


def solve_level(line, leaf_id, level, weapons, armors, names, parent, trees, sk_all, bname="none"):
    cid = S.class_at(leaf_id, level, parent)
    learned = L.learned(cid, level, trees, parent)
    rows = []
    for w, a in S.options(weapons, armors, level):
        rows.append(((w, a), setup(level, cid, w, a, learned, bname)[0]))
    rows = pareto(rows)
    out = []
    for (w, a), st in rows:
        st, actor, later = setup(level, cid, w, a, learned, bname)
        skills = {sid: sk_all[(sid, lv)] for sid, lv in learned.items() if (sid, lv) in sk_all}
        ids = [sid for sid, s in skills.items() if C.usable(s, actor)]
        best = {ms: None for ms in WINDOWS}
        for k in range(0, max_len_for(len(ids)) + 1):
            for perm in itertools.permutations(ids, k):
                for h in (HOLDS if k > 1 else (0,)):
                    tl, pol = [], C.Policy(perm, h)
                    _, _, casts = C.simulate(actor, DUMMY, skills, pol, WINDOWS[-1] * 1000, timeline=tl, later=later)
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
    if split_name(bname)[0] == "party" and not args[1:]:
        import party_buffs     # a party stage also needs the levels where the buffers' kits change (e.g. Haste at 44, Greater Might at 58)
        pb = [tuple(party_buffs.party_buffs(l, "SWORD", "2H", parent, trees)) for l in range(1, 81)]
        levels = sorted(set(levels) | {l for l in range(2, 81) if pb[l - 1] != pb[l - 2]})
        print("party breakpoints added; levels:", levels, flush=True)
    result = {}
    for lv in levels:
        result[lv] = solve_level(line, leaf, lv, weapons, armors, names, parent, trees, sk_all, bname)
        best = max(result[lv], key=lambda r: r["windows"][60]["dps"])
        print(f"L{lv}: {len(result[lv])} combos; best@60s {best['weapon']} + {best['armor']}: "
              f"{best['windows'][60]['dps']:.0f} dps, order {best['windows'][60]['order']}, mp used {best['windows'][60]['mp_used']:.0f}/{best['stats']['mp_max']:.0f}", flush=True)
    json.dump(result, open(os.path.join(here, rot_file(slug, bname)), "w"), indent=1)
