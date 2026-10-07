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
            par = {p.get("name"): (int(p.get("id")), int(p.get("level"))) for p in n.findall("parameters/skill")}
            out[int(n.get("id"))] = dict(name=n.get("name"), level=int(n.get("level")), patk=float(a.get("physical")), matk=float(a.get("magical")), spd=float(a.get("attackSpeed")), crit=float(a.get("critical")),
                                         dd=par.get("DDMagic") or par.get("RangeDD"), support=[(k, v) for k, v in par.items() if k not in ("DDMagic", "RangeDD")])
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

SK = None
def pet_dps(npc, entries, mag_entries=None):
    """Returns (total, auto, skill) DPS. The pet fires its one damaging skill (DDMagic / RangeDD parameter, at that parameter's level) every time it is ready, autos otherwise.
    Magic skill uses spirit shots (x2) and the pet's M.Atk; party tier: physical kit on autos, caster kit (Acumen, Empower...) on the skill's M.Atk."""
    global SK
    auto = pet_auto(npc, entries)
    if not npc.get("dd"):
        return auto, auto, 0.0
    if SK is None:
        SK = L.load_skills()
    sd = SK.get(npc["dd"])
    if sd is None:
        return auto, auto, 0.0
    mul, add, _ = S._apply(mag_entries or [], "mAtk"); matk = npc["matk"] * mul + add
    mul, add, _ = S._apply(entries, "pAtk"); patk = npc["patk"] * mul + add
    mul, add, _ = S._apply(entries, "pAtkSpd"); spd = min(npc["spd"] * mul + add, S.MAX_PATK_SPEED)
    mul, add, _ = S._apply(mag_entries or [], "mAtkSpd"); mspd = 333 * mul + add
    mul, add, _ = S._apply(entries, "critRate"); crit = min(npc["crit"] * 10 * mul + add, S.MAX_PCRIT_RATE) / 1000.0
    a = C.Actor(patk=patk, patk_spd=spd, matk=matk, matk_spd=mspd, mp_max=1e12, mp_regen_3s=0, crit=crit, soulshot=True, spiritshot=1)
    tot = C.simulate(a, DUMMY, {npc["dd"][0]: sd}, C.Policy((npc["dd"][0],), 0), 120000)[0] / 120.0
    return tot, auto, tot - auto

def pet_auto(npc, entries):
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
            ment = []
            for sid, lv in PB.party_buffs(lvl, "BLUNT", 2, parent, trees, magic=True):
                ment += S.passive_entries(sid, lv, "BLUNT", 2)
            row = {}
            for tier, ent, ment_ in (("none", [], []), ("party", pent, ment)):
                best = max(((pet_dps(npcs[n], ent, ment_)[0], sid, lv, n) for sid, lv, n in cands))
                tot, au, skd = pet_dps(npcs[best[3]], ent, ment_)
                row[tier] = dict(dps=round(tot, 1), auto=round(au, 1), skill=round(skd, 1), support=[k for k, _ in npcs[best[3]]["support"]], summon=S._skill_elements()[best[1]].get("name"), skill_level=best[2], npc=npcs[best[3]]["name"], npc_level=npcs[best[3]]["level"])
            res[line][lvl] = row
    json.dump(res, open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "servitors.json"), "w"), indent=1)
    for line, d in res.items():
        print(line)
        for lv in (20, 30, 40, 50, 60, 70, 76, 78, 80):
            if lv in d:
                r = d[lv]; print(" L%d none %.0f (%s) party %.0f (%s) npcL%d" % (lv, r["none"]["dps"], r["none"]["summon"], r["party"]["dps"], r["party"]["summon"], r["none"]["npc_level"]))

if __name__ == "__main__":
    main()
