"""Offline character stat model mirroring the server's stat function order.

Order of application (StatFunction / AbstractFunction): set(0) -> formula funcs (1) -> mul(20) -> add/sub(30).
  P.Atk      = weaponPAtk(set) * STRbonus * (level+89)/100 * prod(mul) + sum(add)
  P.Atk Spd  = weaponSpd(set) * DEXbonus * prod(mul) + sum(add)
  Crit rate  = weaponCrit(set) * DEXbonus * 10 * prod(mul) + sum(add)      (per 1000)
STR/DEX come from the class template plus armor-set bonuses. Passive skills and weapon special abilities are parsed
from the skill XML (`Buff` effect add/mul/sub/set), honouring `using kind=` and `using slot=lrhand` conditions.
"""
import csv, glob, os, re, sys
import xml.etree.ElementTree as ET
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l2data as L

WEAR_LEVEL = {"NG": 1, "D": 20, "C": 40, "B": 52, "A": 61, "S": 76}
GRADES = ["NG", "D", "C", "B", "A", "S"]
MAX_PCRIT_RATE, MAX_PATK_SPEED = 500, 1500   # Player.ini caps
_cache = {}


def stat_bonus():
    if "sb" not in _cache:
        root = ET.parse(os.path.join(L.DATA, "stats/statBonus.xml")).getroot()
        _cache["sb"] = {el.tag: {int(s.get("value")): float(s.get("bonus")) for s in el.findall("stat")} for el in root}
    return _cache["sb"]


def bonus(stat, value):
    t = stat_bonus()[stat]
    v = max(min(t), min(int(value), max(t)))
    return t[v]


def _skill_elements():
    if "sk" not in _cache:
        out = {}
        for p in sorted(glob.glob(os.path.join(L.DATA, "stats/skills/*.xml"))):
            for sk in ET.parse(p).getroot().iter("skill"):
                out[int(sk.get("id"))] = sk
        _cache["sk"] = out
    return _cache["sk"]


def _value(el, tables, level):
    txt = el.text.strip() if el.text and el.text.strip() else None
    v = el.find("value")
    if v is not None:
        txt = v.text.strip()
    if txt is None:
        return 0.0
    if txt.startswith("#"):
        arr = tables.get(txt)
        return float(arr[min(level - 1, len(arr) - 1)]) if arr else 0.0
    return float(txt)


def _cond_ok(el, weapon_type, hands):
    """Evaluate the using/and conditions this server uses on passives. Unknown conditions return None (skip entry)."""
    conds = [c for c in el if c.tag != "value"]
    for c in conds:
        r = _cond_node(c, weapon_type, hands)
        if r is None:
            return None
        if not r:
            return False
    return True


def _cond_node(c, weapon_type, hands):
    if c.tag == "using":
        if c.get("kind") is not None:
            return weapon_type in c.get("kind").split(",")
        if c.get("slot") is not None:
            return (hands == "2H") if c.get("slot") == "lrhand" else False
        return None
    if c.tag == "and":
        rs = [_cond_node(x, weapon_type, hands) for x in c]
        return None if None in rs else all(rs)
    if c.tag == "or":
        rs = [_cond_node(x, weapon_type, hands) for x in c]
        return None if None in rs else any(rs)
    return None  # player resting, etc: not combat relevant


def passive_entries(skill_id, level, weapon_type, hands):
    """List of (func, stat, value) active for this weapon from a passive (or weapon special ability) skill."""
    sk = _skill_elements().get(skill_id)
    if sk is None:
        return []
    tables = {t.get("name"): t.text.split() for t in sk.findall("table")}
    out = []
    for eff in sk.findall("effects/effect"):
        if eff.get("name") not in ("Buff", "MpConsumePerLevel"):        # toggles such as Vicious Stance carry their stats on MpConsumePerLevel
            continue
        for e in eff:
            if e.tag not in ("add", "mul", "sub", "set", "div"):
                continue
            ok = _cond_ok(e, weapon_type, hands)
            if ok:
                out.append((e.tag, e.get("stat"), _value(e, tables, level)))
    return out


def crit_pos_mult(skill_id, position):
    """critDmgPos multiplier a buff gives when the attacker is `position` ('front', 'side', 'behind') of the target (Focus Death / Focus Power)."""
    sk = _skill_elements().get(skill_id)
    out = 1.0
    if sk is None:
        return out
    for e in sk.iter("mul"):
        if e.get("stat") != "critDmgPos":
            continue
        front = e.find("player[@front='true']") is not None
        behind = e.find("player[@behind='true']") is not None
        side = e.find("and") is not None
        if (position == "front" and front) or (position == "behind" and behind) or (position == "side" and side):
            out *= float(e.findtext("value"))
    return out


def template(class_id):
    if class_id not in _cache.setdefault("tpl", {}):
        _cache["tpl"][class_id] = L.load_template(class_id)
    return _cache["tpl"][class_id]


def _apply(entries, stat):
    mul, add = 1.0, 0.0
    sets = None
    for f, s, v in entries:
        if s != stat:
            continue
        if f == "mul":
            mul *= v
        elif f == "div":
            mul /= v
        elif f == "add":
            add += v
        elif f == "sub":
            add -= v
        elif f == "set":
            sets = v
    return mul, add, sets


def compute(class_id, level, weapon, armor, learned_skills, buffs=()):
    """weapon: dict(p_atk, p_atk_spd, crit, weapon_type, hands, special_skill 'id:level' or '').
    armor: dict(str, dex, con, p_atk_mul, p_atk_spd_mul, accuracy_add) or None.
    learned_skills: {skill id: level}. Returns the stat dict the simulator needs."""
    t = template(class_id)
    wt, hands = weapon["weapon_type"], weapon["hands"]
    entries = []
    for sid, lv in learned_skills.items():
        sk = _skill_elements().get(sid)
        if sk is not None and (sk.findtext("operateType") or "").strip() == "P":
            entries += passive_entries(sid, lv, wt, hands)
    for sid, lv in buffs:                         # buffs: (skill id, level), applied like passives (weapon conditions honoured)
        if lv is None:                            # None = the level this class has learned at this character level
            lv = learned_skills.get(sid)
            if lv is None:
                continue
        entries += passive_entries(sid, lv, wt, hands)
    sp = weapon.get("special_skill")
    if sp:
        sid, lv = (int(x) for x in sp.split(":"))
        entries += passive_entries(sid, lv, wt, hands)
    a = armor or {}
    strv = t["baseSTR"] + float(a.get("str", 0) or 0)
    dexv = t["baseDEX"] + float(a.get("dex", 0) or 0)
    str_b, dex_b = bonus("STR", strv), bonus("DEX", dexv)
    lvl_mod = (level + 89) / 100.0
    # P.Atk
    m, ad, _ = _apply(entries, "pAtk")
    m *= float(a.get("p_atk_mul", 1) or 1)
    patk = float(weapon["p_atk"]) * str_b * lvl_mod * m + ad
    # attack speed
    m, ad, _ = _apply(entries, "pAtkSpd")
    m *= float(a.get("p_atk_spd_mul", 1) or 1)
    spd = min(float(weapon["p_atk_spd"]) * dex_b * m + ad, MAX_PATK_SPEED)        # Player.ini MaxPAtkSpeed = 1500
    # crit (per 1000)
    m, ad, _ = _apply(entries, "critRate")
    crit = min(float(weapon["crit"]) * dex_b * 10 * m + ad, MAX_PCRIT_RATE)       # Player.ini MaxPCritRate = 500 (per 1000)
    acc_m, acc_a, _ = _apply(entries, "accCombat")
    crit_mul, crit_add, _ = _apply(entries, "critDmg")[0], _apply(entries, "critDmgAdd")[1], None
    blow_mul = _apply(entries, "blowRate")[0]
    crit_pos = {p: 1.0 for p in ("front", "side", "behind")}
    for sid, lv in buffs:
        if sid in (355, 357) and (lv is not None or sid in learned_skills):
            for p in crit_pos:
                crit_pos[p] = max(crit_pos[p], crit_pos_mult(sid, p))    # Focus Death and Focus Power share an abnormal type: only one applies
    reuse_mul = _apply(entries, "pReuse")[0]
    mp_mul = _apply(entries, "physicalMpConsumeRate")[0]
    tl = t["levels"][min(level, max(t["levels"]))]
    men_b = bonus("MEN", t["baseMEN"])
    m, ad, _ = _apply(entries, "maxMp")
    mp_max = tl["mp"] * men_b * m + ad
    m, ad, _ = _apply(entries, "regMp")
    mp_regen = (tl["mpRegen"] * lvl_mod * men_b * 1.1) * m + ad    # per 3 s tick, standing still (x1.1)
    con = t["baseCON"] + float(a.get("con", 0) or 0)
    con_b = bonus("CON", con)
    m, ad, _ = _apply(entries, "maxHp")
    hp_max = tl["hp"] * con_b * m + ad
    m, ad, _ = _apply(entries, "regHp")
    hp_regen = (tl["hpRegen"] * lvl_mod * con_b * 1.1) * m + ad
    return {"str": strv, "dex": dexv, "p_atk": patk, "p_atk_spd": spd, "crit_pct": crit / 10.0,
            "acc_bonus": acc_a + float(a.get("accuracy_add", 0) or 0),
            "dex_bonus": dex_b, "blow_mul": blow_mul, "crit_pos": crit_pos, "crit_mul": crit_mul, "crit_add": crit_add, "reuse_mul": reuse_mul, "mp_mul": mp_mul, "str_bonus": str_b, "hp_max": hp_max, "hp_regen_3s": hp_regen, "mp_max": mp_max, "mp_regen_3s": mp_regen}


def class_at(leaf_id, level, parent):
    """The class a leaf (third profession) line is actually in at this level: base 1-19, first 20-39, second 40-75, third 76+."""
    chain = [leaf_id]
    while chain[-1] in parent:
        chain.append(parent[chain[-1]])
    chain.reverse()                       # base, first, second, third
    tier = 0 if level < 20 else 1 if level < 40 else 2 if level < 76 else 3
    return chain[min(tier, len(chain) - 1)]


def read_csv(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def options(weapons, armors, level):
    """Every weapon and armor set whose grade is wearable at this level (grade wear level <= level), any grade mix.
    Nothing above the level's grade is ever offered; a lower-grade armor can pair with a higher-grade weapon."""
    ws = [w for w in weapons if WEAR_LEVEL[w["grade"]] <= level]
    ars = [a for a in armors if WEAR_LEVEL[a["grade"]] <= level]
    return [(w, a) for w in ws for a in ars]


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    names, parent = L.load_classes()
    trees = L.load_trees()
    cid = [k for k, v in names.items() if v == "Titan"][0]
    W = read_csv(os.path.join(here, "gear_titan_weapons.csv"))
    A = read_csv(os.path.join(here, "gear_titan_armor.csv"))
    for lv in [int(x) for x in (sys.argv[1:] or ["80"])]:
        ls = L.learned(cid, lv, trees, parent)
        for w, a in options(W, A, lv):
            r = compute(class_at(cid, lv, parent), lv, w, a, ls)
            print(f"L{lv} {w['grade']}:{w['weapon_name']} {w['variant'] or '-'} + {a['grade']}:{a['set_name']}: "
                  f"PAtk {r['p_atk']:.0f}  AtkSpd {r['p_atk_spd']:.0f}  Crit {r['crit_pct']:.1f}%  STR {r['str']:.0f} DEX {r['dex']:.0f}")
