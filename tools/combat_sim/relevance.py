"""Which class skills matter for the baseline damage rotation (no buffs, no control, no heals).

KEEP
  dmg      direct damage: PhysicalDamage, MagicalDamage, EnergyDamage, HpDrain, FatalBlow, Backstab, Lethal, PolearmSingleTarget
  dot      damage over time
  enabler  builds a resource a damage skill spends (force charges)
  stat     passive that changes a damage-relevant stat (attack, attack/cast speed, crit, accuracy, MP use and regen, skill reuse)
  defdown  debuff that lowers the target's physical or magical defence (raises damage taken)
DROP everything else: buffs, toggles, heals, summons, stuns/sleeps/roots/fear, plain debuffs, hate skills, defence/speed/resist passives.
"""
import glob, os
import xml.etree.ElementTree as ET
import l2data

DMG = {"PhysicalDamage", "MagicalDamage", "EnergyDamage", "HpDrain", "FatalBlow", "Backstab", "Lethal", "PolearmSingleTarget"}
DOT = {"DamOverTime"}
ENABLER = {"FocusEnergy", "ElementSeed"}
STAT_KEEP = {"pAtk", "mAtk", "pAtkSpd", "mAtkSpd", "critRate", "mCritRate", "critDmg", "critDmgAdd", "skillCritical", "accCombat",
             "regMp", "maxMp", "magicalMpConsumeRate", "physicalMpConsumeRate", "danceMpConsumeRate", "pReuse", "mReuse", "blowRate",
             "atkCountMax", "manaCharge"}
DEF_STATS = {"pDef", "mDef"}


def _table_value(sk, text):
    if text is None:
        return None
    text = text.strip()
    if text.startswith("#"):
        for t in sk.findall("table"):
            if t.get("name") == text:
                vals = t.text.split()
                try:
                    return float(vals[-1])
                except (ValueError, IndexError):
                    return None
        return None
    try:
        return float(text)
    except ValueError:
        return None


def classify_all():
    """skill id -> dict(name, op, kinds=set(...), stats=set(...)). Level-independent: a skill's category does not change with level."""
    out = {}
    for path in sorted(glob.glob(os.path.join(l2data.DATA, "stats/skills/*.xml"))):
        for sk in ET.parse(path).getroot().iter("skill"):
            sid = int(sk.get("id"))
            op = (sk.findtext("operateType") or "").strip()
            kinds, stats = set(), set()
            ep = _table_value(sk, sk.findtext("effectPoint"))
            offensive = (ep is not None) and (ep < 0)
            for e in sk.findall("effects/effect"):
                n = e.get("name")
                if n in DMG:
                    kinds.add("dmg")
                elif n in DOT:
                    kinds.add("dot")
                elif n in ENABLER:
                    kinds.add("enabler")
                # stat-changing entries
                for t in e.iter():
                    st = t.get("stat")
                    if not st:
                        continue
                    val = None
                    v = t.find("value")
                    if v is not None:
                        val = _table_value(sk, v.text)
                    if op == "P" and st in STAT_KEEP:
                        kinds.add("stat"); stats.add(st)
                    if offensive and st in DEF_STATS and val is not None:
                        lowers = (t.tag == "mul" and val < 1) or (t.tag == "sub" and val > 0) or (t.tag == "add" and val < 0)
                        if lowers:
                            kinds.add("defdown"); stats.add(st)
            out[sid] = dict(name=sk.get("name"), op=op, kinds=kinds, stats=stats)
    return out


def keep(info):
    return bool(info and info["kinds"])
