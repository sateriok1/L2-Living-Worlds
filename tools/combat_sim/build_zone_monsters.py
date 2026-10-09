"""Average monster stats for every Living Population hunting area, for the cold hunting model (kills/hr and deaths/hr from a bot's
P.Atk / M.Atk / P.Def / M.Def against the area's monsters instead of a flat rate).
Input: living_zones.xml (a copy of living-population/data/zones.xml: each zone lists its monsters and how many spawn there) and the
datapack's NPC stats. Weighted by spawn count. Output: zone_monsters.csv and zone_monsters.md."""
import csv, glob, os, sys, collections
import xml.etree.ElementTree as ET
import l2data as L

# respawn delay of each monster (seconds), averaged over its spawn entries in the datapack, weighted by how many spawn
respawn = collections.defaultdict(list)
for sp in glob.glob(os.path.join(L.DATA, "spawns/**/*.xml"), recursive=True):
    try: sroot = ET.parse(sp).getroot()
    except ET.ParseError: continue
    for n in sroot.iter("npc"):
        try: d = float(n.get("respawnDelay") or 0)
        except ValueError: d = 0
        if d > 0: respawn[int(n.get("id"))].append((int(n.get("count", "1")), d))

HERE = os.path.dirname(os.path.abspath(__file__))
zones_file = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "living_zones.xml")
npcs = {}
for p in glob.glob(os.path.join(L.DATA, "stats/npcs/*.xml")):
    for n in ET.parse(p).getroot().iter("npc"):
        st = n.find("stats"); a = st.find("attack") if st is not None else None; d = st.find("defence") if st is not None else None; v = st.find("vitals") if st is not None else None
        if a is None or d is None or v is None: continue
        acq = n.find("acquire"); ai = n.find("ai")
        npcs[int(n.get("id"))] = dict(name=n.get("name"), level=int(n.get("level")), type=n.get("type"), hp=float(v.get("hp")), mp=float(v.get("mp")),
            patk=float(a.get("physical")), matk=float(a.get("magical")), aspd=float(a.get("attackSpeed")), crit=float(a.get("critical", 0)), acc=float(a.get("accuracy", 0)),
            pdef=float(d.get("physical")), mdef=float(d.get("magical")), exp=float(acq.get("exp", 0)) if acq is not None else 0, sp=float(acq.get("sp", 0)) if acq is not None else 0,
            race=((n.findtext("race") or "").strip().upper()), aggro=(ai is not None and float(ai.get("aggroRange", 0) or 0) > 0 and ai.get("isAggressive") != "false"), run=float((st.find("speed/run") or ET.Element("x")).get("ground", 0) or 0))
root = ET.parse(zones_file).getroot()
rows = []
for z in root.iter("zone"):
    mons = [(int(m.get("npcId")), int(m.get("count", 1))) for m in z.findall("monster")]
    known = [(npcs[i], c) for i, c in mons if i in npcs]
    if not known: continue
    tw = sum(c for _, c in known)
    wm = lambda k: sum(m[k] * c for m, c in known) / tw
    lv = [m["level"] for m, _ in known]
    per_s = 0.0      # monsters the zone can supply per second: each spawn comes back after its respawn delay
    for i, c in mons:
        if i in respawn:
            w = sum(cc for cc, _ in respawn[i]); delay = sum(cc * dd for cc, dd in respawn[i]) / w
            per_s += c / delay
    other = sum(c for m, c in known if m["type"] != "Monster")
    rows.append(dict(zone=z.get("name"), min_level=z.get("minLevel"), max_level=z.get("maxLevel"), starter_race=z.get("starterRace") or "",
        monster_types=len(known), spawn_count=tw, mob_level_min=min(lv), mob_level_avg=round(wm("level"), 1), mob_level_max=max(lv),
        hp=round(wm("hp")), p_def=round(wm("pdef"), 1), m_def=round(wm("mdef"), 1), p_atk=round(wm("patk"), 1), m_atk=round(wm("matk"), 1),
        atk_speed=round(wm("aspd")), crit=round(wm("crit"), 1), accuracy=round(wm("acc"), 1), exp=round(wm("exp")), sp=round(wm("sp"), 1),
        aggressive_pct=round(100 * sum(c for m, c in known if m["aggro"]) / tw), non_monster_pct=round(100 * other / tw),
        undead_pct=round(100 * sum(c for m, c in known if m["race"] == "UNDEAD") / tw), spots=len(z.findall("spot")), respawn_per_min=round(per_s * 60, 1)))
rows.sort(key=lambda r: (int(r["min_level"]), int(r["max_level"]), r["zone"]))
with open(os.path.join(HERE, "zone_monsters.csv"), "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0])); w.writeheader(); w.writerows(rows)
md = ["# Hunting area monster averages (spawn-count weighted)\n", "From Living Population's zones.xml (each zone's monster list and counts) and the datapack NPC stats. One row per leveling area. "
      "`atk_speed` is the monster's attack speed stat (higher = faster); `exp`/`sp` are per kill at the server's base values (before the XP rate).\n",
      "| zone | levels | mobs lvl (min/avg/max) | HP | P.Def | M.Def | P.Atk | M.Atk | atk spd | exp | sp | aggro % | types | count | respawn/min |", "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
for r in rows:
    md.append(f"| {r['zone']} | {r['min_level']}-{r['max_level']} | {r['mob_level_min']}/{r['mob_level_avg']}/{r['mob_level_max']} | {r['hp']} | {r['p_def']} | {r['m_def']} | {r['p_atk']} | {r['m_atk']} | {r['atk_speed']} | {r['exp']} | {r['sp']} | {r['aggressive_pct']} | {r['monster_types']} | {r['spawn_count']} | {r['respawn_per_min']} |")
open(os.path.join(HERE, "zone_monsters.md"), "w").write("\n".join(md) + "\n")
print(len(rows), "zones;", sum(1 for r in rows if int(r["non_monster_pct"]) > 0), "with non-Monster types")
