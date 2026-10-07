"""Best single-target rotation per level breakpoint and combat window, for one leaf line.

For each breakpoint level: every wearable weapon x armor combination that is not dominated, every skill ordering and hold
window; one 120 s run per policy gives cumulative damage for the 5 s .. 120 s windows. Mana is simulated: max MP and regen
come from the class template, MEN and level; a skill is skipped when MP is short, so mana-starved rotations lose damage.
Usage: python3 rotations.py Titan [level ...]
"""
import itertools, json, os, sys, bisect
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l2data as L, stats_model as S, combat_sim as C

WINDOWS = [5, 15, 30, 45, 60, 90, 120]
MODEL_MP = os.environ.get("L2_MP", "infinite") == "finite"   # first tests assume infinite mana (user decision); L2_MP=finite charges MP and regen
TIMED_BUFFS = os.environ.get("L2_BUFF_TIME", "infinite") == "finite"   # first tests assume buffs never expire; finite = Rage ends at 90 s
POSITION = os.environ.get("L2_POS", "")        # '' = no positioning model; 'front' = target faces the attacker (bad), 'behind' = perfect positioning
MP_SUFFIX = ("" if not MODEL_MP else "_finitemp") + ("_timed" if TIMED_BUFFS else "") + (("_" + POSITION) if POSITION else "")
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


IS_MAGE = False       # set from the gear table (spirit-shot weapons): spawn / scheme / party buff kits then use the caster versions
SPAWN_MAGE = [1204, 1085, 1059, 1303, 1078, 1397, 1040, 1047, 1259, 1035, 1036, 1045, 1048, 1062]   # PREBUFF_COMMON + PREBUFF_CASTER


def set_mage(flag):
    global IS_MAGE
    IS_MAGE = bool(flag)


SPAWN_BUFFS = [1204, 1068, 1086, 1077, 1242, 1240, 1268, 1087, 1040, 1243, 1044, 1259, 1035, 1036, 1045, 1048, 1062]   # PhantomBuffs.applyFullBuffs for a damage dealer, max level


_PARENT = _TREES = None
RAGE_ID, RAGE_MS = 94, 90000      # Rage: abnormalTime 90 s, so it only covers the first 90 s of a long fight
VICIOUS_ID = 312                  # Vicious Stance toggle: +critDmgAdd, upkeep 0.8 * (level-1)/7.5 MP per second


def split_name(name):
    """'fighterplus_max_self_vicious' -> ('fighterplus_max', rage, vicious)."""
    name = name.replace("_dagger", "")
    vicious = name.endswith("_self_vicious")
    rage = vicious or name.endswith("_self")
    base = name.replace("_self_vicious", "").replace("_self", "")
    return base, rage, vicious


def buff_set(name):
    """Permanent buffs: (skill id, level) pairs; level None = the level the class has learned. 'spawn' = what a spawned phantom gets (max level, as the server applies them);
    'fighterplus' = the Scheme Buffer FIGHTER_GROUP preset at the levels it lists."""
    if name.endswith("_dagger"):
        # dagger self-buffs, assumed up for the whole fight (<= 120 s: each is cast once at the start): Vicious Stance, Mortal Strike (blowRate), the better Focus (rear crit damage)
        return buff_set(name[:-7]) + ((VICIOUS_ID, None), (410, None))
    base, rage, vicious = split_name(name)
    if (base, rage, vicious) != (name, False, False):
        return buff_set(base) + (((VICIOUS_ID, None),) if vicious else ())
    if name in ("none", "party"):
        return ()                      # 'party' is level- and weapon-dependent: setup() asks party_buffs for it
    els = S._skill_elements()
    if name == "spawn":
        return tuple((i, int(els[i].get("levels"))) for i in (SPAWN_MAGE if IS_MAGE else SPAWN_BUFFS) if i in els)
    if name in ("fighterplus", "fighterplus_max"):
        import re
        t = open(os.path.join(L.DATA, "SchemeBufferSkills.xml")).read()
        body = re.search(r'<category type="%s">(.*?)</category>' % ("MAGE_GROUP" if IS_MAGE else "FIGHTER_GROUP"), t, re.S).group(1)
        out = []
        for i, lv in re.findall(r'<buff id="(\d+)" level="(\d+)"', body):
            i, lv = int(i), int(lv)
            out.append((i, int(els[i].get("levels")) if name.endswith("_max") else lv))
        return tuple(out)
    raise SystemExit("unknown buff set " + name)


def temp_set(name):
    return ((RAGE_ID, None),) if split_name(name)[1] else ()


def setup(level, cid, w, a, learned, bname, focus=None):
    """(stats with every buff, the actor, the actor that continues after Rage expires or None)."""
    perm, temp = buff_set(bname), temp_set(bname)
    if focus:
        perm = perm + ((focus, None),)      # Focus Death / Focus Power: one per fight (shared buff slot), chosen per gear row
    if split_name(bname)[0] == "party":
        import party_buffs
        global _PARENT, _TREES
        if _PARENT is None:
            _PARENT, _TREES = L.load_classes()[1], L.load_trees()
        perm = tuple(party_buffs.party_buffs(level, w["weapon_type"], w["hands"], _PARENT, _TREES, magic=IS_MAGE)) + perm
    st = S.compute(cid, level, w, a, learned, perm + temp)
    st["crit_pos_now"] = st["crit_pos"][POSITION or "front"]
    drain = 0.8 * (level - 1) / 7.5 if ((split_name(bname)[2] or bname.endswith("_dagger")) and VICIOUS_ID in learned) else 0.0
    actor = build_actor(st, w)
    actor.mp_drain_per_s = drain
    later = None
    if temp and RAGE_ID in learned and TIMED_BUFFS:
        after = build_actor(S.compute(cid, level, w, a, learned, perm), w)
        after.mp_drain_per_s = drain
        later = (RAGE_MS, after)
    return st, actor, later


def build_actor(st, w):
    mage = str(w.get("shot", "")).startswith("SPS")         # spirit shots (blessed) for a caster's weapon, soulshots otherwise
    return C.Actor(patk=st["p_atk"], patk_spd=st["p_atk_spd"], matk=st["m_atk"], matk_spd=st["m_atk_spd"], mcrit=st["m_crit"] / 1000.0,
                   soulshot=not mage, spiritshot=2 if mage else 0, mreuse_mul=st["mreuse_mul"], mmp_mul=st["mmp_mul"], mcrit_mul=st["mcrit_mul"], mp_max=st["mp_max"] if MODEL_MP else 1e12,
                   mp_regen_3s=st["mp_regen_3s"] if MODEL_MP else 0.0, weapon=w["weapon_type"], crit=min(1.0, st["crit_pct"] / 100.0),
                   str_bonus=st["str_bonus"], crit_mul=st["crit_mul"], crit_add=st["crit_add"], reuse_mul=st["reuse_mul"],
                   mp_mul=st["mp_mul"], weapon_reuse=float(w.get("reuse_delay") or 0),
                   position=POSITION or "front", prox={"behind": 1.2, "side": 1.1}.get(POSITION, 1.0), dex_bonus=st["dex_bonus"], blow_mul=st["blow_mul"],
                   crit_pos=st["crit_pos"][POSITION or "front"], hp_max=st["hp_max"] if MODEL_HP else 0.0, hp_regen_3s=st["hp_regen_3s"])


SEED = {}
FAST_MAGE = os.environ.get('L2_FULL') is None      # L2_FULL=1 keeps the exhaustive caster search
MAGE_ROWS = 6
MAGE_SKILLS = 5          # more usable skills than this -> greedy selection
GREEDY_MAX = 6


CHARGE_DURS = (15, 60, 120)     # fight lengths whose greedy rotations are pooled for charge classes
CHARGE_ROWS = 6
CHARGE_PER_TYPE = 3
CHARGE_SKILLS = 7


def greedy_set(actor, skills, ids, dur_ms, later, gmax=6):
    """Greedy rotation build for classes whose attacks need charges: start from the charge builders, then insert the skill (at the position) that adds most damage."""
    gens = [i for i in ids if skills[i].charge_gain]
    best = None
    for g in ([[x] for x in gens] + ([gens] if len(gens) > 1 else []) or [[]]):
        cur = list(g)
        cur_d = C.simulate(actor, DUMMY, skills, C.Policy(tuple(cur), 0), dur_ms, later=later)[0]
        forced = True
        while len(cur) < gmax:
            gain = None
            for i in ids:
                if i in cur:
                    continue
                for pos in range(len(cur) + 1):
                    cand = tuple(cur[:pos] + [i] + cur[pos:])
                    d = C.simulate(actor, DUMMY, skills, C.Policy(cand, 0), dur_ms, later=later)[0]
                    if gain is None or d > gain[0]:
                        gain = (d, cand)
            if gain is None or (gain[0] < cur_d * 1.002 and not (forced and gain[0] >= cur_d * 0.5)):
                break
            if any(skills[i].charge_use for i in gain[1]):
                forced = False
            cur_d, cur = gain[0], list(gain[1])
        if best is None or cur_d > best[1]:
            best = (cur, cur_d)
    return best


def pareto(rows):
    """Drop combos that are no better on P.Atk, attack speed, auto crit, STR bonus and MP than another combo."""
    keys = ("p_atk", "p_atk_spd", "crit_pct", "str_bonus", "crit_mul", "blow_mul", "crit_pos_now", "m_atk", "m_atk_spd", "m_crit") + (("mp_max", "mp_regen_3s") if MODEL_MP else ()) + (("hp_max", "hp_regen_3s") if MODEL_HP else ())
    keep = []
    for i, a in enumerate(rows):
        dom = False
        for j, b in enumerate(rows):
            if i != j and b[0][0].get('weapon_type') == a[0][0].get('weapon_type') and all(b[1][k] >= a[1][k] for k in keys) and any(b[1][k] > a[1][k] for k in keys):
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
    foci = [f for f in (355, 357) if f in learned] if bname.endswith("_dagger") else []
    for w, a in S.options(weapons, armors, level):
        for f in (foci or [None]):
            rows.append(((w, a, f), setup(level, cid, w, a, learned, bname, f)[0]))
    rows = pareto(rows)
    skills_all = {sid: sk_all[(sid, lv)] for sid, lv in learned.items() if (sid, lv) in sk_all}
    if IS_MAGE and FAST_MAGE and len(rows) > MAGE_ROWS:
        # casters: rank gear by the damage of the single best spammed spell (infinite mana) and search only the top few rows
        def solo(item):
            (w, a, f), _st = item
            _s, act, lat = setup(level, cid, w, a, learned, bname, f)
            sk = {sid: sk_all[(sid, lv)] for sid, lv in learned.items() if (sid, lv) in sk_all}
            cand = [i for i, x in sk.items() if C.usable(x, act)]
            best = C.simulate(act, DUMMY, sk, C.Policy((), 0), 60000, later=lat)[0]
            for i in cand:
                best = max(best, C.simulate(act, DUMMY, sk, C.Policy((i,), 0), 60000, later=lat)[0])
            return best
        rows = sorted(rows, key=solo, reverse=True)[:MAGE_ROWS]
    CHARGE = any(sk_all[(sid, lv)].charge_use or sk_all[(sid, lv)].charge_gain for sid, lv in learned.items() if (sid, lv) in sk_all)
    if CHARGE:
        # charge classes (Duelist): rank the gear rows by the greedy rotation each one gets over a few fight lengths, keep the best few, then search orders of the union set
        scored = []
        for (w, a, f), st in rows:
            _s, actor, later = setup(level, cid, w, a, learned, bname, f)
            skills = {sid: sk_all[(sid, lv)] for sid, lv in learned.items() if (sid, lv) in sk_all}
            ids = [sid for sid, s in skills.items() if C.usable(s, actor)]
            union, score = [], 0.0
            for dur in CHARGE_DURS:
                cur, d = greedy_set(actor, skills, ids, dur * 1000, later)
                score += d / dur
                union += [i for i in cur if i not in union]
            scored.append((score, ((w, a, f), st), union))
        scored.sort(key=lambda x: -x[0])
        picked, per_type, seen = [], {}, set()
        for sc, row, union in scored:                    # the best row of every weapon type first (each type unlocks different skills), then the best remaining
            t = row[0][0].get("weapon_type")
            if t not in seen:
                seen.add(t); per_type[t] = 1; picked.append((row, union))
        for sc, row, union in scored:
            t = row[0][0].get("weapon_type")
            if len(picked) >= CHARGE_ROWS:
                break
            if per_type.get(t, 0) < CHARGE_PER_TYPE and not any(row is p[0] for p in picked):
                per_type[t] = per_type.get(t, 0) + 1
                picked.append((row, union))
        rows = [r_ for r_, _u in picked]
        unions = {id(r_): u for r_, u in picked}
    out = []
    for (w, a, f), st in rows:
        st, actor, later = setup(level, cid, w, a, learned, bname, f)
        skills = {sid: sk_all[(sid, lv)] for sid, lv in learned.items() if (sid, lv) in sk_all}
        ids = [sid for sid, s in skills.items() if C.usable(s, actor)]
        if IS_MAGE and FAST_MAGE and len(ids) > MAGE_SKILLS:
            # casters: build the rotation greedily (add the skill, at the position, that raises damage most), then search every order of just those skills
            near = [v for k, v in sorted(SEED.items()) if k <= level]
            cur = [i for i in (near[-1] if near else []) if i in ids][:GREEDY_MAX]      # start from the no-buff rotation
            cur_d = C.simulate(actor, DUMMY, skills, C.Policy(tuple(cur), 0), 60000, later=later)[0]
            while len(cur) < GREEDY_MAX:
                best_gain = None
                for i in ids:
                    if i in cur:
                        continue
                    for pos in range(len(cur) + 1):
                        cand = tuple(cur[:pos] + [i] + cur[pos:])
                        d = C.simulate(actor, DUMMY, skills, C.Policy(cand, 0), 60000, later=later)[0]
                        if best_gain is None or d > best_gain[0]:
                            best_gain = (d, cand)
                if best_gain is None or best_gain[0] < cur_d * 1.002:
                    break
                cur_d, cur = best_gain[0], list(best_gain[1])
            ids = cur
        if CHARGE:
            ids = [i for i in unions[id(next(x for x in rows if x[0] == (w, a, f)))][:CHARGE_SKILLS] if i in ids] if False else [i for i in unions[id(next(x for x in rows if x[0][0] == w and x[0][1] == a and x[0][2] == f))] if i in ids][:CHARGE_SKILLS]
        if len(ids) > 7:
            # too many skills for a full permutation search: drop those that add under 0.3% over plain autos even on their own
            base = C.simulate(actor, DUMMY, skills, C.Policy((), 0), 60000, later=later)[0]
            ids = [i for i in ids if C.simulate(actor, DUMMY, skills, C.Policy((i,), 0), 60000, later=later)[0] > base * 1.003]
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
        out.append({"weapon": f"{w['weapon_name']} {w['variant']}".strip(), "armor": a["set_name"], "focus_id": f, "focus": (sk_all[(f, 1)].name if f else ""), "stats": st,
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
    set_mage(any(str(x.get("shot", "")).startswith("SPS") for x in weapons))
    bps = json.load(open(os.path.join(here, "breakpoints.json")))[line]
    # skill breakpoints plus the levels where a new gear grade becomes wearable, plus the cap
    levels = [int(x) for x in args[1:]] or sorted({b["level"] for b in bps} | {S.WEAR_LEVEL[g] for g in S.GRADES if S.WEAR_LEVEL[g] > 1} | {80})
    if split_name(bname)[0] == "party" and not args[1:]:
        import party_buffs     # a party stage also needs the levels where the buffers' kits change (e.g. Haste at 44, Greater Might at 58)
        pb = [tuple(party_buffs.party_buffs(l, "BLUNT" if IS_MAGE else "SWORD", "2H", parent, trees, magic=IS_MAGE)) for l in range(1, 81)]
        levels = sorted(set(levels) | {l for l in range(2, 81) if pb[l - 1] != pb[l - 2]})
        print("party breakpoints added; levels:", levels, flush=True)
    SEED = {}
    if IS_MAGE and bname != "none" and os.path.exists(os.path.join(here, rot_file(slug, "none"))):
        # buffed tiers start from the no-buff answer: its best rotation's skills are always in the candidate set
        base = json.load(open(os.path.join(here, rot_file(slug, "none"))))
        for lv_, rows_ in base.items():
            b_ = max(rows_, key=lambda r: r["windows"]["60"]["dps"])
            SEED[int(lv_)] = list(b_["windows"]["60"]["order_ids"])
    result = {}
    outp = os.path.join(here, rot_file(slug, bname))
    merge_old = {}
    if os.environ.get("L2_MERGE") and args[1:] and os.path.exists(outp):
        merge_old = {int(k): v for k, v in json.load(open(outp)).items()}
    part = os.path.join(here, rot_file(slug, bname) + ".partial")
    if os.path.exists(part):
        result = {int(k): v for k, v in json.load(open(part)).items()}
        print("resumed levels:", sorted(result), flush=True)
    for lv in levels:
        if lv in result:
            continue
        result[lv] = solve_level(line, leaf, lv, weapons, armors, names, parent, trees, sk_all, bname)
        best = max(result[lv], key=lambda r: r["windows"][60]["dps"])
        print(f"L{lv}: {len(result[lv])} combos; best@60s {best['weapon']} + {best['armor']}: "
              f"{best['windows'][60]['dps']:.0f} dps, order {best['windows'][60]['order']}, mp used {best['windows'][60]['mp_used']:.0f}/{best['stats']['mp_max']:.0f}", flush=True)
        json.dump(result, open(part, "w"))
    if merge_old:
        merge_old.update(result); result = dict(sorted(merge_old.items()))
    json.dump(result, open(os.path.join(here, rot_file(slug, bname)), "w"), indent=1)
    if os.path.exists(part):
        os.remove(part)
