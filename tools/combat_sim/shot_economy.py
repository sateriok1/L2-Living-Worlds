"""Soulshot economy per grade: what a bot spends on shots an hour against what the same hunting earns, per hunting zone.
Rough: shots from the weapon's shots-per-attack and an attack every ATTACK_S seconds while fighting (fight share of the kill cycle),
income from the zone monsters' drop lists (adena plus loot sold at half the reference price, server drop rates 1x, no level-gap penalty).
Usage: python3 shot_economy.py <datapack data dir> <zone_combat.tsv> [out.md]"""
import glob, os, sys, collections, statistics as st
import xml.etree.ElementTree as ET
DATA, TSV = sys.argv[1], sys.argv[2]
OUT = sys.argv[3] if len(sys.argv) > 3 else None
FIGHT_SHARE = 0.5      # share of the kill cycle spent hitting (the model's ColdKills fightShare)
ATTACK_S = {"fighter": 1.5, "mage": 2.0}
SHOT = {"NONE": (1835, 7), "D": (1463, 10), "C": (1464, 15), "B": (1465, 50), "A": (1466, 80), "S": (1467, 100)}   # item id, price (datapack)
SPIRIT = {"NONE": 15, "D": 18, "C": 35, "B": 100, "A": 120, "S": 150}
BLESSED = {"NONE": 35, "D": 45, "C": 90, "B": 245, "A": 290, "S": 350}
PER_ATTACK = {"NONE": 2, "D": 2, "C": 3, "B": 1, "A": 1, "S": 1}     # medians over the grade's weapons in the datapack
items = {}
for p in glob.glob(os.path.join(DATA, "stats/items/*.xml")):
    for it in ET.parse(p).getroot().iter("item"):
        s = {x.get("name"): x.get("val") for x in it.findall("set")}
        items[int(it.get("id"))] = (it.get("name"), int(s.get("price", "0") or 0), s.get("is_sellable") != "false")
npcs = {}
for p in glob.glob(os.path.join(DATA, "stats/npcs/*.xml")):
    for n in ET.parse(p).getroot().iter("npc"):
        dl = n.find("dropLists")
        drops = []
        if dl is not None:
            for d in dl.findall("drop"):
                for g in d.findall("group"):
                    gc = float(g.get("chance", "100")) / 100.0
                    for i in g.findall("item"):
                        drops.append((int(i.get("id")), gc * float(i.get("chance")) / 100.0, (int(i.get("min")) + int(i.get("max"))) / 2.0))
        npcs[int(n.get("id"))] = drops
zones = {}
root = ET.parse(os.path.join(os.path.dirname(TSV), "zones.xml")).getroot()
for z in root.iter("zone"):
    zones[z.get("name")] = [(int(m.get("npcId")), int(m.get("count", 1))) for m in z.findall("monster")]
def income(zone):
    mons = zones.get(zone, [])
    tot = sum(c for _, c in mons) or 1
    adena = loot = 0.0
    for nid, c in mons:
        for iid, p, amt in npcs.get(nid, []):
            if iid == 57:
                adena += c / tot * p * amt
            else:
                nm, price, sell = items.get(iid, ("?", 0, False))
                if sell and price > 0 and not nm.startswith(("Recipe", "Herb")):
                    loot += c / tot * p * amt * price / 2.0
    return adena, loot
rows = []
for line in open(TSV, encoding="utf-8"):
    f = line.rstrip("\n").split("\t")
    if f[0] != "ZONE":
        continue
    name, lo, hi, mob = f[1], int(f[2]), int(f[3]), float(f[4])
    resp = float(f[10])
    a, l = income(name)
    rows.append((name, lo, hi, mob, a, l, resp))
def grade_for(level):
    return "NONE" if level < 20 else "D" if level < 40 else "C" if level < 52 else "B" if level < 61 else "A" if level < 76 else "S"
print("| zone | levels | grade | adena/kill | loot/kill | kills/min (assumed) | income/h | soulshots/h | spirit/h | blessed/h | shots as % of income (fighter) |")
print("|---|---|---|---|---|---|---|---|---|---|---|")
lines = []
for name, lo, hi, mob, a, l, resp in sorted(rows, key=lambda r: (r[1], r[2])):
    mid = (lo + hi) // 2
    g = grade_for(mid)
    k = 6.0                                           # kills a minute: a mid value, the solo model gives 4-8 at these levels
    income_h = (a + l) * k * 60
    attacks_f = 3600 * FIGHT_SHARE / ATTACK_S["fighter"]
    attacks_m = 3600 * FIGHT_SHARE / ATTACK_S["mage"]
    ss = attacks_f * PER_ATTACK[g] * SHOT[g][1]
    sp = attacks_m * PER_ATTACK[g] * SPIRIT[g]
    bl = attacks_m * PER_ATTACK[g] * BLESSED[g]
    lines.append("| %s | %d-%d | %s | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f%% |" % (name, lo, hi, g, a, l, k, income_h, ss, sp, bl, 100 * ss / income_h if income_h else 0))
print("\n".join(lines))
if OUT:
    open(OUT, "w").write("\n".join(lines))
