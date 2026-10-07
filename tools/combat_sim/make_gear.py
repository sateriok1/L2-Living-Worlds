"""Write gear_<slug>_weapons.csv / gear_<slug>_armor.csv drafts in the Titan format.
Usage: python3 make_gear.py "<Line>" --types=BLUNT[,POLE]
Rules (user's): armor types from the class's armor-mastery passives; strongest weapons per grade (a second base weapon only when within 10% of the best,
per hands) plus their offence special-ability variants (Focus, Haste, Anger = any SA whose passive changes pAtk, pAtkSpd, critRate, accCombat while at full HP);
armor sets only when they raise offence. Review the output with the user before running rotations."""
import csv, glob, os, sys, collections
import xml.etree.ElementTree as ET
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l2data as L, stats_model as S

line = sys.argv[1]; slug = line.lower().replace(" ", "_")
types = next(a.split("=")[1].split(",") for a in sys.argv if a.startswith("--types="))
WITHIN = float(next((a.split("=")[1] for a in sys.argv if a.startswith("--within=")), "0.9"))   # a second base weapon must reach this fraction of the best pAtk
HANDS = next((a.split("=")[1] for a in sys.argv if a.startswith("--hands=")), None)           # e.g. 1H when the class uses a shield
ONLY_ARMOR = next((a.split("=")[1].split(",") for a in sys.argv if a.startswith("--armor=")), None)   # e.g. HEAVY overrides the mastery-derived armor types
ARMOR_MASTERY = {"Light Armor Mastery": "LIGHT", "Heavy Armor Mastery": "HEAVY", "Robe Mastery": "MAGIC", "Robe Armor Mastery": "MAGIC"}
MAGIC = "--magic" in sys.argv       # spell caster: magic weapons only (is_magic_weapon), offence = M.Atk / cast speed / magic crit / cast reuse, ranking by mAtk
GR = {"NONE": "NG"}
OFFENCE = {"pAtk", "pAtkSpd", "critRate", "accCombat", "blowRate"}
MOFFENCE = {"mAtk", "mAtkSpd", "mCritRate", "mReuse"}
MSET = MOFFENCE | {"magicalMpConsumeRate", "maxMp", "regMp"}      # stats an armor set can carry that the spell model uses
if MAGIC: OFFENCE = MOFFENCE
here = os.path.dirname(os.path.abspath(__file__))
names, parent = L.load_classes(); trees = L.load_trees(); sk = L.load_skills()
leaf = [k for k, v in names.items() if v == line][0]
learned = L.learned(leaf, 80, trees, parent)
atypes = {ARMOR_MASTERY[sk[(s, lv)].name] for s, lv in learned.items() if (s, lv) in sk and sk[(s, lv)].name in ARMOR_MASTERY}
if ONLY_ARMOR: atypes = set(ONLY_ARMOR)
items = {}
for path in glob.glob(os.path.join(L.DATA, "stats/items/*.xml")):
    for it in ET.parse(path).getroot().iter("item"): items[int(it.get("id"))] = it
def sets_of(it): return {s.get("name"): s.get("val") for s in it.findall("set")}

# ---- weapons
by = collections.defaultdict(lambda: collections.defaultdict(list))   # (grade, hands) -> base -> rows
for iid, it in sorted(items.items()):
    if it.get("type") != "Weapon": continue
    s = sets_of(it)
    if s.get("weapon_type") not in types or (s.get("is_magic_weapon") == "true") != MAGIC: continue
    nm = it.get("name"); st = {x.get("type"): float(x.text) for x in it.findall("stats/stat")}
    if nm.startswith(("_", "Monster")) or nm.strip().isdigit() or not (0 < st.get("pAtk", 0) <= 600): continue
    if int(s.get("price", "0") or 0) <= 0: continue
    if s.get("is_tradable") == "false": continue
    if s.get("crystal_type", "NONE") == "NONE" and st.get("pAtk", 0) > 60: continue      # a no-grade weapon this strong is an NPC item
    if s.get("crystal_type", "NONE") != "NONE" and int(s.get("crystal_count", "0") or 0) <= 0: continue      # NPC / test / event items have no crystal count
    hands = "2H" if s.get("bodypart") == "lrhand" else "1H"
    if HANDS and hands != HANDS: continue
    sas = [x for x in it.findall("skills/skill") if x.get("id") != "3599"]      # 3599 = Polearm Multi-attack, the weapon's own ability
    sa = sas[0] if sas else None; eff = []; sid = ""
    if sa is not None:
        sid = f"{sa.get('id')}:{sa.get('level')}"
        eff = [(f, k, v) for f, k, v in S.passive_entries(int(sa.get("id")), int(sa.get("level")), s["weapon_type"], hands)]
        if not any(k in OFFENCE for _, k, _ in eff): continue        # SA with no full-HP offence effect (Health, Rsk., Stun...) is not a candidate
    base, _, variant = nm.partition(" - ")
    if variant and not eff: continue      # a variant whose special ability changes nothing offensive is just the plain weapon
    text = ", ".join(f"{k} {'x' if f == 'mul' else '+'}{v:g}" for f, k, v in eff if k in OFFENCE or k == "maxHp")
    by[(GR.get(s.get("crystal_type", "NONE"), s.get("crystal_type")), hands)][base].append(
        dict(line=line, grade=GR.get(s.get("crystal_type", "NONE"), s.get("crystal_type")), item_id=iid, weapon_name=base, variant=variant, weapon_type=s["weapon_type"], hands=hands,
             p_atk=st["pAtk"], m_atk=st.get("mAtk", 0), p_atk_spd=st.get("pAtkSpd", 0), crit=st.get("critRate", 0),
             reuse_delay=int(s.get("reuse_delay", 0) or 0), shot=("SPS-" if MAGIC else "SS-") + GR.get(s.get("crystal_type", "NONE"), s.get("crystal_type")), special_skill=sid, special_effect=text))
wrows = []
best_m = {}
for (grade, hands), bases in by.items():
    best_m[(grade, hands)] = max(r[0]["m_atk"] for r in bases.values())
for (grade, hands), bases in by.items():
    best = max(r[0]["p_atk"] for r in bases.values())
    best_m[(grade, hands)] = max(r[0]["m_atk"] for r in bases.values())
    for base, rows in bases.items():
        if rows[0]["m_atk" if MAGIC else "p_atk"] < WITHIN * (best_m[(grade, hands)] if MAGIC else best): continue
        seen = set()
        for r in rows:
            if r["variant"] in seen: continue       # duplicate item ids for the same weapon/variant: keep the first
            seen.add(r["variant"]); wrows.append(r)
order = ["NG", "D", "C", "B", "A", "S"]
_seen, _w2 = set(), []
for r in wrows:                      # different items with identical stats and ability (e.g. three D-grade 2H staffs) are one candidate
    k = (r["grade"], r["hands"], r["p_atk"], r["m_atk"], r["p_atk_spd"], r["crit"], r["special_skill"])
    if k not in _seen:
        _seen.add(k); _w2.append(r)
wrows = _w2
wrows.sort(key=lambda r: (order.index(r["grade"]), r["hands"], -(r["m_atk"] if MAGIC else r["p_atk"]), r["weapon_name"], r["variant"]))
cols = ["line", "grade", "item_id", "weapon_name", "variant", "weapon_type", "hands", "p_atk", "m_atk", "p_atk_spd", "crit", "shot", "special_skill", "special_effect", "reuse_delay"]
with open(os.path.join(here, f"gear_{slug}_weapons.csv"), "w", newline="") as f:
    w = csv.DictWriter(f, cols); w.writeheader(); w.writerows(wrows)

# ---- armor
arows = [dict(line=line, grade="NG", set_name="none", set_skill="", str=0, dex=0, con=0, int=0, wit=0, men=0, p_atk_mul=1, p_atk_spd_mul=1, accuracy_add=0, entries="", notes="")]
for path in sorted(glob.glob(os.path.join(L.DATA, "stats/armorsets/*.xml"))):
    if "clan" in os.path.basename(path): continue
    for st_ in ET.parse(path).getroot().iter("set"):
        chest = st_.find("chest")
        if chest is None or int(chest.get("id")) not in items: continue
        ci = items[int(chest.get("id"))]; cs = sets_of(ci); at = cs.get("armor_type")
        if at not in atypes: continue
        bonus = {t: int(st_.find(t).get("val")) for t in ("str", "dex", "con", "int", "wit", "men") if st_.find(t) is not None}
        pm = sm = 1.0; acc = 0.0; skid = ""; ents = []
        for e in st_.findall("skill"):
            if e.get("id") == "3006": continue
            skid = f"{e.get('id')}:{e.get('level')}"
            for fn, k, v in S.passive_entries(int(e.get("id")), int(e.get("level")), types[0], "2H"):
                if k == "pAtk" and fn == "mul": pm *= v
                if k == "pAtkSpd" and fn == "mul": sm *= v
                if k == "accCombat" and fn == "add": acc += v
                if MAGIC and k in MSET: ents.append(f"{fn}:{k}:{v}")
        magic_gain = any(e.split(":")[1] in MOFFENCE for e in ents) or bonus.get("int", 0) > 0 or bonus.get("wit", 0) > 0
        if magic_gain if MAGIC else (bonus.get("str", 0) > 0 or bonus.get("dex", 0) > 0 or pm != 1 or sm != 1 or acc):
            arows.append(dict(line=line, grade=GR.get(cs.get("crystal_type", "NONE"), cs.get("crystal_type")), set_name=f"{ci.get('name')} ({at.title()})", set_skill=skid,
                              str=bonus.get("str", 0), dex=bonus.get("dex", 0), con=bonus.get("con", 0), int=bonus.get("int", 0), wit=bonus.get("wit", 0), men=bonus.get("men", 0), p_atk_mul=pm, p_atk_spd_mul=sm, accuracy_add=acc, entries=";".join(ents), notes=at))
arows.sort(key=lambda r: (order.index(r["grade"]), r["set_name"]))
acols = ["line", "grade", "set_name", "set_skill", "str", "dex", "con", "int", "wit", "men", "p_atk_mul", "p_atk_spd_mul", "accuracy_add", "entries", "notes"]
with open(os.path.join(here, f"gear_{slug}_armor.csv"), "w", newline="") as f:
    w = csv.DictWriter(f, acols); w.writeheader(); w.writerows(arows)
print(f"{line}: {len(wrows)} weapon rows, {len(arows)} armor rows; armor types {sorted(atypes)}, weapon types {types}")
