"""Full-party buff model: every buffer class at the same level as the character buffs it with everything that helps damage,
up to the server's buff caps (Player.ini MaxBuffAmount = 20 buffs, MaxDanceAmount = 12 dances/songs).

Buffer lines (third class leaf): Hierophant, Sword Muse, Spectral Dancer, Dominator, Doom Cryer, Eva's Saint, Shillien Saint.
At level N each buffer is in the class its line has at that level and knows what its skill tree gives it by then.
Rules taken from the server: buffs with the same abnormalType replace each other (the higher abnormalLevel wins; on a tie we
keep the stronger one); a buff counts for the dance cap when its abnormalType starts with DANCE_/SONG_, else for the buff cap.
Only buffs that change a damage stat (or MP) are kept, so the caps rarely bind; if they do, the strongest are kept first.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l2data as L, stats_model as S

BUFFER_LEAVES = [98, 100, 107, 115, 116, 105, 112]
MAX_BUFFS, MAX_DANCES = 20, 12
RELEVANT = {"pAtk", "pAtkSpd", "critRate", "critDmg", "critDmgAdd", "pReuse", "physicalMpConsumeRate", "maxMp", "regMp"}
WEIGHT = {"pAtk": 3.0, "pAtkSpd": 3.0, "critRate": 1.0, "critDmg": 1.0, "critDmgAdd": 0.1, "pReuse": 3.0, "physicalMpConsumeRate": 1.0, "maxMp": 0.3, "regMp": 0.3, "accCombat": 0.0}


def _score(entries):
    s = 1.0
    for f, stat, v in entries:
        if stat in WEIGHT and WEIGHT[stat]:
            if f == "mul":
                s += WEIGHT[stat] * abs(v - 1.0) * 10 if stat != "pReuse" else WEIGHT[stat] * (1 - v) * 10
            elif f == "add":
                s += WEIGHT[stat] * 0.01 * abs(v)
    return s


def party_buffs(level, weapon_type, hands, parent, trees):
    """[(skill id, level)] of the buffs the full party gives a character at `level` wielding this weapon."""
    els = S._skill_elements()
    cand = {}      # abnormalType -> (abnormalLevel, score, sid, lv, is_dance)
    for leaf in BUFFER_LEAVES:
        cid = S.class_at(leaf, level, parent)
        for sid, lv in L.learned(cid, level, trees, parent).items():
            sk = els.get(sid)
            if sk is None:
                continue
            if (sk.findtext("operateType") or "").strip() not in ("A2",):
                continue
            if (sk.findtext("targetType") or "").strip() not in ("ONE", "PARTY", "PARTY_NOTME", "PARTY_MEMBER", "PARTY_OTHER"):
                continue
            ents = [e for e in S.passive_entries(sid, lv, weapon_type, hands) if e[1] in RELEVANT]
            if not ents:
                continue
            tables = {t.get("name"): t.text.split() for t in sk.findall("table")}
            at = (sk.findtext("abnormalType") or "").strip()
            al = (sk.findtext("abnormalLevel") or "0").strip()
            if al.startswith("#"):
                arr = tables.get(al)
                al = arr[min(lv, len(arr)) - 1] if arr else "0"
            al = int(float(al))
            key = at or f"id{sid}"
            sc = _score(ents)
            cur = cand.get(key)
            if cur is None or (al, sc) > (cur[0], cur[1]):
                cand[key] = (al, sc, sid, lv, at.startswith(("DANCE", "SONG")))
    chosen = sorted(cand.values(), key=lambda c: -c[1])
    buffs = [c for c in chosen if not c[4]][:MAX_BUFFS]
    dances = [c for c in chosen if c[4]][:MAX_DANCES]
    return [(c[2], c[3]) for c in buffs + dances]


if __name__ == "__main__":
    names, parent = L.load_classes()
    trees = L.load_trees()
    for lv in [int(x) for x in sys.argv[1:]] or [40, 61, 80]:
        got = party_buffs(lv, "SWORD", "2H", parent, trees)
        print(f"L{lv}: {len(got)} buffs")
        for sid, l in got:
            print("   ", sid, S._skill_elements()[sid].get("name"), "lv", l)
