"""Servitor auto-attack DPS per summoner line, level and buff tier (none / party). Servitors only auto-attack (no skills, no shots beyond soulshot).
Uses the server's NPC stats for each summon skill level; party tier applies the physical party buff kit (as for a physical attacker) to the pet."""
import os, sys, re, json, glob
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l2data as L, stats_model as S, combat_sim as C, party_buffs as PB
import xml.etree.ElementTree as ET

LINES = {"Arcana Lord": 96, "Elemental Master": 104, "Spectral Master": 111}
DUMMY = C.Dummy()

def load_npcs():
    out = {}
    for p in glob.glob(os.path.join(L.DATA, "stats/npcs/*.xml")):
        for n in ET.parse(p).getroot().iter("npc"):
            if n.get("type") != "Pet":
                continue
            a = n.find("stats/attack")
            if a is None:
                continue
            out[int(n.get("id"))] = dict(name=n.get("name"), level=int(n.get("level")), patk=float(a.get("physical")), spd=float(a.get("attackSpeed")), crit=float(a.get("critical")))
    return out

def summon_npc(sid, lv):
    sk = S._skill_elements().get(sid)
    if sk is None:
        return None
    for e in sk.findall("effects/effect"):
        if e.get("name") == "Summon":
            t = (e.findtext("npcId") or "").strip()
            if t.startswith("#"):
                arr = {x.get("name"): x.text.split() for x in sk.findall("table")}.get(t)
                return int(arr[min(lv, len(arr)) - 1]) if arr else None
            return int(t)
    return None

def pet_dps(npc, entries):
    mul, add, _ = S._apply(entries, "pAtk"); patk = npc["patk"] * mul + add
    mul, add, _ = S._apply(entries, "pAtkSpd"); spd = min(npc["spd"] * mul + add, S.MAX_PATK_SPEED)
    mul, add, _ = S._apply(entries, "critRate"); crit = min(npc["crit"] * 10 * mul + add, S.MAX_PCRIT_RATE) / 1000.0
    cm = S._apply(entries, "critDmg")[0]; ca = S._apply(entries, "critDmgAdd")[1]
    a = C.Actor(patk=patk, patk_spd=spd, matk=0, matk_spd=0, mp_max=1e12, mp_regen_3s=0, crit=crit, crit_mul=cm, crit_add=ca, soulshot=True)
    return C.auto_dmg(a, DUMMY) * 1000.0 / (500000.0 / spd)

def main():
    names, parent = L.load_classes(); trees = L.load_trees(); npcs = load_npcs()
    sums = {sid for sid, sk in S._skill_elements().items() if any(e.get("name") == "Summon" for e in sk.findall("effects/effect"))}
    res = {}
    for line, leaf in LINES.items():
        res[line] = {}
        for lvl in range(20, 81):
            cid = S.class_at(leaf, lvl, parent)
            learned = L.learned(cid, lvl, trees, parent)
            cands = []
            for sid, lv in learned.items():
                if sid not in sums:
                    continue
                n = summon_npc(sid, lv)
                if n in npcs:
                    cands.append((sid, lv, n))
            if not cands:
                continue
            buffs = PB.party_buffs(lvl, "SWORD", 1, parent, trees, magic=False)
            pent = []
            for sid, lv in buffs:
                pent += S.passive_entries(sid, lv, "SWORD", 1)
            row = {}
            for tier, ent in (("none", []), ("party", pent)):
                best = max(((pet_dps(npcs[n], ent), sid, lv, n) for sid, lv, n in cands))
                row[tier] = dict(dps=round(best[0], 1), summon=S._skill_elements()[best[1]].get("name"), skill_level=best[2], npc=npcs[best[3]]["name"], npc_level=npcs[best[3]]["level"])
            res[line][lvl] = row
    json.dump(res, open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "servitors.json"), "w"), indent=1)
    for line, d in res.items():
        print(line)
        for lv in (20, 30, 40, 50, 60, 70, 76, 78, 80):
            if lv in d:
                r = d[lv]; print(" L%d none %.0f (%s) party %.0f (%s) npcL%d" % (lv, r["none"]["dps"], r["none"]["summon"], r["party"]["dps"], r["party"]["summon"], r["none"]["npc_level"]))

if __name__ == "__main__":
    main()
