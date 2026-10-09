"""Buff factors for the Living Population cold model: how much a bot's damage and defence rise when buffers keep it buffed while it
levels. Per role (tank, melee, bow, mage) and character level 1-85, with the full party of buffers the sim already models
(party_buffs.py: every third-class buffer line at the character's level, server buff caps, same-type buffs replace each other).

damage_mult  physical roles: (P.Atk x P.Atk speed) buffed / unbuffed; mage: sqrt(M.Atk) x cast speed buffed / unbuffed
             (the same shape as the damage rate the cold model uses; crits and skill power are left out)
pdef_mult / mdef_mult  products of the P.Def and M.Def multipliers of the buffs (Shield, Greater Shield, Mental Shield, Magic Barrier ...)
Output rows for zone_combat.tsv: BUFF  role  level  damage_mult  pdef_mult  mdef_mult.   Usage: python3 build_buff_factors.py [out.tsv]
"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import l2data as L, stats_model as S, party_buffs as P

REPS = {                       # role -> (line with gear data, preferred weapon types)
    "tank": ("Phoenix Knight", ("SWORD",)),
    "melee": ("Duelist", ("SWORD",)),
    "bow": ("Sagittarius", ("BOW",)),
    "mage": ("Archmage", ("BLUNT",)),
}
DEF_STATS = {"pDef", "mDef"}
names, parent = L.load_classes()
trees = L.load_trees()


def pick(rows, level):
    """The highest grade wearable at this level (first of them)."""
    ok = [r for r in rows if S.WEAR_LEVEL[r["grade"]] <= level]
    best = max(S.WEAR_LEVEL[r["grade"]] for r in ok)
    return [r for r in ok if S.WEAR_LEVEL[r["grade"]] == best]


def defence_buffs(level):
    """(P.Def multiplier, M.Def multiplier): products over the best buff of each type the buffers give at this level."""
    els = S._skill_elements()
    stats = DEF_STATS
    best = {}
    for leaf in P.BUFFER_LEAVES:
        cid = S.class_at(leaf, level, parent)
        for sid, lv in L.learned(cid, level, trees, parent).items():
            sk = els.get(sid)
            if sk is None or (sk.findtext("operateType") or "").strip() != "A2":
                continue
            if (sk.findtext("targetType") or "").strip() not in ("ONE", "PARTY", "PARTY_NOTME", "PARTY_MEMBER", "PARTY_OTHER"):
                continue
            ents = [e for e in S.passive_entries(sid, lv, "SWORD", "1H") if e[1] in stats and e[0] == "mul"]
            if not ents:
                continue
            at = (sk.findtext("abnormalType") or "").strip() or f"id{sid}"
            tables = {t.get("name"): t.text.split() for t in sk.findall("table")}
            al = (sk.findtext("abnormalLevel") or "0").strip()
            if al.startswith("#"):
                arr = tables.get(al)
                al = arr[min(lv, len(arr)) - 1] if arr else "0"
            al = int(float(al))
            pm = mm = 1.0
            for _, stat, v in ents:
                if stat == "pDef":
                    pm *= v
                else:
                    mm *= v
            cur = best.get(at)
            if cur is None or (al, pm * mm) > (cur[0], cur[1] * cur[2]):
                best[at] = (al, pm, mm)
    p_out = m_out = 1.0
    for _, pm, mm in best.values():
        p_out *= pm
        m_out *= mm
    return p_out, m_out


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "buff_factors.tsv")
    rows = []
    for role, (line, wtypes) in REPS.items():
        slug = line.lower().replace(" ", "_")
        W = S.read_csv(os.path.join(HERE, f"gear_{slug}_weapons.csv"))
        A = S.read_csv(os.path.join(HERE, f"gear_{slug}_armor.csv"))
        leaf = [k for k, v in names.items() if v == line][0]
        magic = role == "mage"
        for level in range(1, 86):
            cid = S.class_at(leaf, level, parent)
            learned = L.learned(cid, level, trees, parent)
            ws = pick([w for w in W if w["weapon_type"] in wtypes] or W, level)
            w = ws[0]
            a = pick(A, level)[0]
            base = S.compute(cid, level, w, a, learned)
            buffs = P.party_buffs(level, w["weapon_type"], w["hands"], parent, trees, magic=magic)
            full = S.compute(cid, level, w, a, learned, tuple(buffs))
            if magic:
                dmg = ((full["m_atk"] / max(1e-9, base["m_atk"])) ** 0.5) * (full["m_atk_spd"] / max(1e-9, base["m_atk_spd"]))
            else:
                dmg = (full["p_atk"] / max(1e-9, base["p_atk"])) * (full["p_atk_spd"] / max(1e-9, base["p_atk_spd"]))
            pdm, mdm = defence_buffs(level)
            rows.append((role, level, dmg, pdm, mdm))
    with open(out, "w", encoding="utf-8", newline="") as f:
        f.write("#BUFF\trole\tlevel\tdamageMult\tpDefMult\tmDefMult   (full buffer party, see tools/combat_sim/build_buff_factors.py)\n")
        for role, level, d, pdm, mdm in rows:
            f.write("BUFF\t%s\t%d\t%.4f\t%.4f\t%.4f\n" % (role, level, d, pdm, mdm))
    print(len(rows), "rows ->", out)
    for role in REPS:
        pts = {lv: (d, pdm, mdm) for r, lv, d, pdm, mdm in rows if r == role and lv in (1, 20, 40, 60, 76, 85)}
        print(role, "  ".join(f"L{lv}: dmg x{d:.2f} pdef x{pdm:.2f} mdef x{mdm:.2f}" for lv, (d, pdm, mdm) in pts.items()))


main()
