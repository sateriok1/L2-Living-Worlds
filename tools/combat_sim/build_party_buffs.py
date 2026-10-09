"""PBUFF rows for zone_combat.tsv: what ONE buffer line gives a party member, per role and level (the cold party model picks a buffer at random).
Same method as build_buff_factors.py (damage = P.Atk x P.Atk speed, or sqrt(M.Atk) x cast speed for mages; P.Def and M.Def products) with only that buffer's skills.
PBUFF  buffer  role  level  damageMult  pDefMult  mDefMult      Usage: python3 build_party_buffs.py [out.tsv]"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build_buff_factors as B
import l2data as L, stats_model as S, party_buffs as P

BUFFERS = {98: "hierophant", 100: "sword_muse", 107: "spectral_dancer", 115: "dominator", 116: "doom_cryer", 105: "evas_saint", 112: "shillien_saint"}
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "party_buff_rows.tsv")
names, parent, trees = B.names, B.parent, B.trees
rows = []
for leaf, bname in BUFFERS.items():
    P.BUFFER_LEAVES = [leaf]
    for role, (line, wtypes) in B.REPS.items():
        slug = line.lower().replace(" ", "_")
        W = S.read_csv(os.path.join(HERE, f"gear_{slug}_weapons.csv"))
        A = S.read_csv(os.path.join(HERE, f"gear_{slug}_armor.csv"))
        rleaf = [k for k, v in names.items() if v == line][0]
        magic = role == "mage"
        for level in range(1, 86):
            cid = S.class_at(rleaf, level, parent)
            learned = L.learned(cid, level, trees, parent)
            w = B.pick([x for x in W if x["weapon_type"] in wtypes] or W, level)[0]
            a = B.pick(A, level)[0]
            base = S.compute(cid, level, w, a, learned)
            buffs = P.party_buffs(level, w["weapon_type"], w["hands"], parent, trees, magic=magic)
            full = S.compute(cid, level, w, a, learned, tuple(buffs))
            if magic:
                dmg = ((full["m_atk"] / max(1e-9, base["m_atk"])) ** 0.5) * (full["m_atk_spd"] / max(1e-9, base["m_atk_spd"]))
            else:
                dmg = (full["p_atk"] / max(1e-9, base["p_atk"])) * (full["p_atk_spd"] / max(1e-9, base["p_atk_spd"]))
            pdm, mdm = B.defence_buffs(level)
            rows.append("PBUFF\t%s\t%s\t%d\t%.4f\t%.4f\t%.4f" % (bname, role, level, dmg, pdm, mdm))
with open(out, "w", encoding="utf-8", newline="") as f:
    f.write("#PBUFF\tbuffer\trole\tlevel\tdamageMult\tpDefMult\tmDefMult   (one buffer line, see tools/combat_sim/build_party_buffs.py)\n")
    f.write("\n".join(rows) + "\n")
print(len(rows), "rows ->", out)
