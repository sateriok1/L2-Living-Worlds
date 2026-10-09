"""NBUFF rows: what the Newbie Helper's support magic (SupportMagic.java, levels 8-25, not for third-class characters) gives a bot at each level.
NBUFF  role  level  damage  pDef  mDef   (multipliers; roles TANK MELEE BOW MAGE)
Windows from the server script: Shield for Beginners 11-23 (P.Def x1.15), Haste for Beginners 15-19 (attack speed x1.15, fighters),
Acumen for Beginners 13-21 (cast speed x1.3, mages), Empower for Beginners 15-19 (M.Atk x1.55, mages). Buffs that do not touch damage or defence
(Wind Walk, Blessed Body/Soul, Vampiric Rage, Regeneration, Concentration, Life Cubic) are left out. Values read from the skill data."""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import stats_model as S

def mul(sid, stat):
    return next(v for t, st, v in S.passive_entries(sid, 1, "SWORD", 1) + S.passive_entries(sid, 1, "BLUNT", 2) if t == "mul" and st == stat)

shield, haste, acumen, empower = mul(4323, "pDef"), mul(4327, "pAtkSpd"), mul(4329, "mAtkSpd"), mul(4331, "mAtk")
rows = ["#NBUFF\trole\tlevel\tdamage\tpDef\tmDef   (Newbie Helper buffs, levels 8-25; see build_newbie_buffs.py)"]
for role in ("TANK", "MELEE", "BOW", "MAGE"):
    for lv in range(8, 26):
        dmg = 1.0
        if role == "MAGE":
            dmg *= (empower if 15 <= lv <= 19 else 1.0) * (acumen if 13 <= lv <= 21 else 1.0)
        elif 15 <= lv <= 19:
            dmg *= haste
        rows.append("NBUFF\t%s\t%d\t%.4f\t%.4f\t1.0000" % (role, lv, dmg, shield if 11 <= lv <= 23 else 1.0))
open(os.path.join(HERE, "newbie_buff_rows.tsv"), "w").write("\n".join(rows) + "\n")
print(len(rows) - 1, "rows", shield, haste, acumen, empower)
