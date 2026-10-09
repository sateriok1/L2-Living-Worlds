"""NBUFF rows: what the Newbie Helper's support magic (SupportMagic.java, levels 8-25, not for third-class characters) gives a bot at each level.
NBUFF  role  level  damage  pDef  mDef  sitRegen  absorb   (multipliers; roles TANK MELEE BOW MAGE)
Windows from the server script: Shield for Beginners 11-23 (P.Def x1.15), Haste for Beginners 15-19 (attack speed x1.15, fighters),
Acumen for Beginners 13-21 (cast speed x1.3, mages), Empower for Beginners 15-19 (M.Atk x1.55, mages). Fighters also get Blessed Body 12-22 (HP x1.35, folded into the defence columns as effective HP), Vampiric Rage 13-21 (9% of damage dealt returns as HP) and
Regeneration 14-20 (HP regen x1.2, used for the sit rate); mages get Blessed Soul 12-22 (MP x1.35: the pool size cancels in the rest model, so it changes nothing),
Concentration 14-20 (interrupts, not modeled). Wind Walk 8-24 (run speed +33 on a base of 120) shortens the walk between monsters. The Life Cubic is left out. Values read from the skill data."""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import stats_model as S

def mul(sid, stat):
    return next(v for t, st, v in S.passive_entries(sid, 1, "SWORD", 1) + S.passive_entries(sid, 1, "BLUNT", 2) if t == "mul" and st == stat)

shield, haste, acumen, empower = mul(4323, "pDef"), mul(4327, "pAtkSpd"), mul(4329, "mAtkSpd"), mul(4331, "mAtk")
windwalk = (120.0 + [v for t, st, v in S.passive_entries(4322, 1, "SWORD", 1) if st == "runSpd"][0]) / 120.0       # Wind Walk for Beginners, levels 8-24 (run speed -> less walking between monsters)
body, regen = mul(4324, "maxHp"), mul(4326, "regHp")
absorb = next(v for t, st, v in S.passive_entries(4325, 1, "SWORD", 1) if st == "absorbDam") / 100.0      # Vampiric Rage: a share of the damage dealt comes back as HP
rows = ["#NBUFF\trole\tlevel\tdamage\tpDef\tmDef\tsitRegen\tabsorb\trun   (Newbie Helper buffs, levels 8-25; see build_newbie_buffs.py)"]
for role in ("TANK", "MELEE", "BOW", "MAGE"):
    for lv in range(8, 26):
        dmg = 1.0
        if role == "MAGE":
            dmg *= (empower if 15 <= lv <= 19 else 1.0) * (acumen if 13 <= lv <= 21 else 1.0)
        elif 15 <= lv <= 19:
            dmg *= haste
        fighter = role != "MAGE"
        hp = body if (fighter and 12 <= lv <= 22) else 1.0
        rows.append("NBUFF\t%s\t%d\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f" % (role, lv, dmg, (shield if 11 <= lv <= 23 else 1.0) * hp, hp,
                                                                     regen if (fighter and 14 <= lv <= 20) else 1.0, absorb if (fighter and 13 <= lv <= 21) else 0.0) + "\t%.4f" % (windwalk if 8 <= lv <= 24 else 1.0))
open(os.path.join(HERE, "newbie_buff_rows.tsv"), "w").write("\n".join(rows) + "\n")
print(len(rows) - 1, "rows", shield, haste, acumen, empower)
