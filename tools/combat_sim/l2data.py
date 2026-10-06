"""Load L2J Mobius Interlude skill, class and skill-tree data (read-only) for the combat simulator."""
import glob, os, re
import xml.etree.ElementTree as ET

ROOT = os.environ.get("L2_PROJECT", "/home/claude/teravibes/l2-living-worlds/L2J_Mobius_CT_0_Interlude github")
DATA = os.path.join(ROOT, "dist/game/data")
DAMAGE_EFFECTS = {"PhysicalDamage", "MagicalDamage", "HpDrain", "EnergyDamage", "FatalBlow"}


class SkillDef:
    """One skill at one level, with every table already resolved to a number."""
    __slots__ = ("id", "level", "name", "magic", "op", "power", "mp", "hit", "cool", "reuse", "range",
                 "target", "effects", "weapons", "static_reuse", "flags", "magic_level")

    def damage_kind(self):
        for e in self.effects:
            if e in ("MagicalDamage", "HpDrain"):
                return "magic"
            if e in ("PhysicalDamage", "FatalBlow", "EnergyDamage"):
                return "magic" if self.magic else "phys"
        return None


def _num(text, level):
    """A literal, or '#table' resolved against the per-level tables of this skill."""
    return text


def load_skills():
    skills = {}
    for path in sorted(glob.glob(os.path.join(DATA, "stats/skills/*.xml"))):
        root = ET.parse(path).getroot()
        for sk in root.iter("skill"):
            sid = int(sk.get("id"))
            levels = int(sk.get("levels", "1"))
            name = sk.get("name", str(sid))
            tables = {}
            for t in sk.findall("table"):
                tables[t.get("name")] = t.text.split()
            sub = {}
            for t in sk.findall("enchant1"):
                pass

            def val(tag, lv, default=0.0):
                el = sk.find(tag)
                if el is None or el.text is None:
                    return default
                txt = el.text.strip()
                if txt.startswith("#"):
                    arr = tables.get(txt)
                    if arr is None:
                        return default
                    i = min(lv - 1, len(arr) - 1)
                    try:
                        return float(arr[i])
                    except ValueError:
                        return default
                try:
                    return float(txt)
                except ValueError:
                    return default

            effects = [e.get("name") for e in sk.findall("effects/effect")]
            op = (sk.findtext("operateType") or "").strip()
            weapons = set()
            for u in sk.findall("conditions/using"):
                weapons |= set((u.get("kind") or "").split(","))
            flags = set()
            if sk.find("conditions/rear") is not None or "behind" in ET.tostring(sk, encoding="unicode").lower()[:0]:
                flags.add("rear")
            for e in effects:
                if e and ("Charge" in e or "Force" in e):
                    flags.add("charge")
            for lv in range(1, levels + 1):
                d = SkillDef()
                d.id, d.level, d.name, d.op = sid, lv, name, op
                d.magic = int(val("isMagic", lv, 0)) == 1
                d.static_reuse = (sk.findtext("staticReuse") or "").strip() == "true"
                d.power = val("power", lv)
                d.mp = val("mpConsume", lv)
                d.hit = val("hitTime", lv)
                d.cool = val("coolTime", lv)
                d.reuse = val("reuseDelay", lv)
                d.range = val("castRange", lv)
                d.magic_level = val("magicLevel", lv)
                d.target = (sk.findtext("targetType") or "").strip()
                d.effects = effects
                d.weapons = weapons
                d.flags = flags
                skills[(sid, lv)] = d
    return skills


def load_classes():
    names, parent = {}, {}
    root = ET.parse(os.path.join(DATA, "stats/players/classList.xml")).getroot()
    for c in root.iter("class"):
        cid = int(c.get("classId"))
        names[cid] = c.get("name")
        if c.get("parentClassId") is not None:
            parent[cid] = int(c.get("parentClassId"))
    return names, parent


def load_trees():
    """class id -> list of (skillId, skillLevel, getLevel)."""
    trees = {}
    for path in glob.glob(os.path.join(DATA, "stats/players/skillTrees/*Class/*.xml")):
        root = ET.parse(path).getroot()
        for st in root.iter("skillTree"):
            if st.get("type") != "classSkillTree":
                continue
            cid = int(st.get("classId"))
            for s in st.findall("skill"):
                trees.setdefault(cid, []).append((int(s.get("skillId")), int(s.get("skillLevel")), int(s.get("getLevel"))))
    return trees


def learned(class_id, level, trees, parent):
    """Skill ids -> highest learnable level at `level`, over the class and its ancestors."""
    best = {}
    c = class_id
    while c is not None:
        for sid, sl, gl in trees.get(c, []):
            if gl <= level and sl > best.get(sid, 0):
                best[sid] = sl
        c = parent.get(c)
    return best


def load_template(class_id):
    """Base stats and per-level MP for a class."""
    for path in glob.glob(os.path.join(DATA, "stats/players/templates/*/*.xml")):
        root = ET.parse(path).getroot()
        if int(root.findtext("classId")) != class_id:
            continue
        sd = root.find("staticData")
        out = {k: float(sd.findtext(k)) for k in ("basePAtk", "baseMAtk", "basePAtkSpd", "baseCritRate",
                                                  "baseSTR", "baseINT", "baseDEX", "baseWIT", "baseMEN", "baseCON")
               if sd.findtext(k)}
        out["baseMAtkSpd"] = float(sd.findtext("baseMAtkSpd") or 333)
        out["levels"] = {}
        for lv in root.iter("level"):
            out["levels"][int(lv.get("val"))] = {k: float(lv.findtext(k)) for k in ("hp", "mp", "mpRegen")}
        return out
    raise KeyError(class_id)
