"""Compare the in-game Stat Dump (stats.csv, buffed=0 rows) with the offline stat model, for one leaf line.
Uses the dump's own weapon and its reported STR/DEX/CON/MEN so the check isolates the formulas (set bonuses are checked separately).
Usage: python3 validate_dump.py <dump folder> [line]"""
import csv, glob, os, sys
import xml.etree.ElementTree as ET
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l2data as L, stats_model as S

folder = sys.argv[1]
line = sys.argv[2] if len(sys.argv) > 2 else "Titan"
names, parent = L.load_classes()
trees = L.load_trees()
leaf = ([k for k, v in names.items() if v.lower() == line.lower()] or [0])[0]

items = {}
for p in glob.glob(os.path.join(L.DATA, "stats/items/*.xml")):
    for it in ET.parse(p).getroot().iter("item"):
        if it.get("type") == "Weapon":
            st = {s.get("type"): float(s.text) for s in it.findall("stats/stat")}
            sk = [(int(x.get("id")), int(x.get("level"))) for x in it.findall("skills/skill") if x.get("type") is None]
            items[int(it.get("id"))] = (st, sk, {s.get("name"): s.get("val") for s in it.findall("set")})

ALL = line.lower() == "all"
# armor set bonuses: chest id -> set skills; the dump's equip.csv tells which chest the phantom wore
set_skills = {}
for p in glob.glob(os.path.join(L.DATA, "stats/armorsets/*.xml")):
    for st_ in ET.parse(p).getroot().iter("set"):
        ch = st_.find("chest")
        if ch is not None:
            set_skills[int(ch.get("id"))] = [int(x.get("id")) for x in st_.findall("skill") if x.get("id") != "3006"]
equip = {}
for e in csv.DictReader(open(os.path.join(folder, "equip.csv"))):
    equip.setdefault(e["sample"], {})[e["body_part"]] = e
rows = [r for r in csv.DictReader(open(os.path.join(folder, "stats.csv"))) if (ALL or r["class"].lower() == line.lower()) and r["buffed"] == "0"]
print(f"{len(rows)} {line} rows")
worst = {}
for r in rows:
    lv = int(r["level"])
    if int(r["weapon_id"]) not in items:
        continue
    st, sk, setv = items[int(r["weapon_id"])]
    hands = "2H" if setv.get("bodypart") == "lrhand" else "1H"
    w = {"m_atk": st.get("mAtk", 0), "p_atk": st.get("pAtk", 0), "p_atk_spd": st.get("pAtkSpd", 300), "crit": st.get("critRate", 4),
         "weapon_type": r["weapon_type"], "hands": hands, "special_skill": ""}
    if ALL:
        leaf = int(r["class_id"])
    cid_now = int(r["class_id"]) if ALL else S.class_at(leaf, lv, parent)     # the dump's class_id is already the class the phantom had at that level
    ls = L.learned(cid_now, lv, trees, parent)
    for sid, lvl in sk:                                           # weapon special ability skills are applied like passives
        ls = dict(ls); ls[sid] = lvl
    cid = cid_now
    t = S.template(cid)
    # feed the dump's own STR/DEX/CON as an "armor" delta against the template, so formulas are tested independently of sets
    chest = equip.get(r["sample"], {}).get("CHEST") or equip.get(r["sample"], {}).get("FULL_ARMOR")
    ents = []
    if chest and int(chest["item_id"]) in set_skills:
        for sid in set_skills[int(chest["item_id"])]:
            ents += S.passive_entries(sid, 1, w["weapon_type"], hands)
    pm = 1.0
    sm = 1.0
    for f, s_, v in ents:
        if f == "mul" and s_ == "pAtk":
            pm *= v
        if f == "mul" and s_ == "pAtkSpd":
            sm *= v
    armor = {"p_atk_mul": pm, "p_atk_spd_mul": sm, "str": float(r["str"]) - t["baseSTR"], "dex": float(r["dex"]) - t["baseDEX"], "con": float(r["con"]) - t["baseCON"],
             "int": float(r["int"]) - t["baseINT"], "wit": float(r["wit"]) - t["baseWIT"],
             "entries": [e for e in ents if e[1] not in ("pAtk", "pAtkSpd")]}
    m = S.compute(cid, lv, w, armor, ls)
    comp = {"p_atk": (m["p_atk"], float(r["p_atk"])), "p_atk_spd": (m["p_atk_spd"], float(r["p_atk_spd"])),
            "crit": (m["crit_pct"] * 10, float(r["crit"])), "max_hp": (m["hp_max"], float(r["max_hp"])),
            "max_mp": (m["mp_max"], float(r["max_mp"])), "hp_regen": (m["hp_regen_3s"], float(r["hp_regen"])),
            "mp_regen": (m["mp_regen_3s"], float(r["mp_regen"])),
            "m_atk": (m["m_atk"], float(r["m_atk"])), "m_atk_spd": (m["m_atk_spd"], float(r["m_atk_spd"])), "m_crit": (m["m_crit"], float(r["m_crit"]))}
    if not ALL:
        print(f"L{lv} {r['weapon']} ({r['weapon_grade']}) STR{r['str']} DEX{r['dex']} CON{r['con']} MEN{r['men']}")
    for k, (a, b) in comp.items():
        bad = abs(a - b) > max(1.0 if k == "crit" else 0.5, 0.005 * abs(b))
        tot = worst.setdefault(k, [0, 0, []])
        tot[0] += 1
        if bad:
            tot[1] += 1
            tot[2].append((r["class"], lv, r["weapon"], round(a, 2), round(b, 2)))
        if not ALL:
            print(f"   {k:10s} model {a:10.2f}  game {b:10.2f}  ratio {a / b if b else 0:6.3f}{'   <-- differs' if bad else ''}")
if ALL:
    for k, (n, bad, ex) in worst.items():
        print(f"{k:10s} {n - bad}/{n} match")
        for e in ex[:6]:
            print("     ", e)
