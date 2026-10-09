"""BBUFF rows: what a Hierophant's or a Doom Cryer's buffs give a solo bot beyond damage and defence (the PBUFF rows keep only those), at the bot's level.
BBUFF  buffer  level  runAdd  sitRegen  absorb  hp      runAdd = run speed added (Wind Walk, Berserker Spirit), sitRegen = HP regen multiplier (Regeneration),
absorb = share of damage dealt returned as HP (Chant of Vampire), hp = max HP multiplier (Blessed Body). All last 20 minutes: treated as always on.
Usage: python3 build_buffer_extras.py"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import l2data as L, stats_model as S

names, parent = L.load_classes(); trees = L.load_trees(); els = S._skill_elements()
rows = ["#BBUFF\tbuffer\tlevel\trunAdd\tsitRegen\tabsorb\thp   (Hierophant / Doom Cryer extras, see build_buffer_extras.py)"]
for leaf, name in ((98, "hierophant"), (116, "doom_cryer")):
    for level in range(26, 86):
        cid = S.class_at(leaf, level, parent)
        run, regen, absorb, hp = 0.0, 1.0, 0.0, 1.0
        for sid, lv in L.learned(cid, level, trees, parent).items():
            e = els.get(sid)
            if e is None or (e.findtext("operateType") or "") != "A2" or (e.findtext("targetType") or "").strip() not in ("ONE", "PARTY", "PARTY_NOTME", "PARTY_MEMBER", "PARTY_OTHER"):
                continue
            for t, st, v in S.passive_entries(sid, lv, "SWORD", 1):
                if st == "runSpd" and t == "add": run += v
                elif st == "regHp" and t == "mul": regen *= v
                elif st == "maxHp" and t == "mul": hp *= v
                elif st == "absorbDam" and t == "add": absorb += v / 100.0
        rows.append("BBUFF\t%s\t%d\t%.1f\t%.4f\t%.4f\t%.4f" % (name, level, run, regen, absorb, hp))
open(os.path.join(HERE, "buffer_extra_rows.tsv"), "w").write("\n".join(rows) + "\n")
print(len(rows) - 1, "rows")
