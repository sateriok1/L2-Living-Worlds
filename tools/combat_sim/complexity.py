"""How complicated is each class line's damage rotation? Counts the mechanics a plain cooldown/MP simulation cannot express."""
import glob, os, collections
import xml.etree.ElementTree as ET
import l2data, relevance

names, parent = l2data.load_classes()
trees = l2data.load_trees()
info = relevance.classify_all()

# per skill: extra mechanics found in the skill XML
extra = collections.defaultdict(set)
for path in sorted(glob.glob(os.path.join(l2data.DATA, "stats/skills/*.xml"))):
    for sk in ET.parse(path).getroot().iter("skill"):
        sid = int(sk.get("id")); tags = extra[sid]
        for e in sk.findall("effects/effect"):
            n = e.get("name")
            if n in ("Backstab", "Lethal", "FatalBlow"): tags.add("blow/lethal")
            if n in ("EnergyDamage", "FocusEnergy"): tags.add("charges")
            if n == "DamOverTime": tags.add("dot")
            if n == "ElementSeed": tags.add("seeds")
            if n == "PolearmSingleTarget": tags.add("pole")
        c = sk.find("conditions")
        if c is not None:
            for t in c.iter():
                if t.tag in ("behind", "inFront", "rear") or t.get("kind") == "BEHIND": tags.add("positional")
                if t.tag == "hp" or t.tag == "targetHp": tags.add("hp-gated")
                if t.tag == "charges": tags.add("charges")
        if (sk.findtext("targetType") or "").strip() in ("AURA", "AREA", "FRONT_AREA", "FRONT_AURA", "BEHIND_AREA", "BEHIND_AURA", "PARTY_ALL"): tags.add("aoe")
        if sk.findtext("itemConsumeId") or sk.find("itemConsume") is not None: tags.add("reagent")

children = set(parent.values())
leaves = sorted(c for c in names if c not in children and c >= 88)
print(f"{'line':22s} dmg skills  mechanics")
rows = []
for leaf in leaves:
    ls = l2data.learned(leaf, 80, trees, parent)
    dmg = [s for s in ls if "dmg" in (info.get(s) or {}).get("kinds", ())]
    mech = collections.Counter()
    for s in dmg + [s for s in ls if (info.get(s) or {}).get("kinds", set()) & {"enabler", "dot", "defdown"}]:
        for t in extra[s]: mech[t] += 1
        for k in info[s]["kinds"]:
            if k in ("enabler", "dot", "defdown"): mech[k] += 1
    rows.append((names[leaf], len(dmg), dict(mech)))
for n, d, m in sorted(rows, key=lambda r: (len(r[2]), r[1])):
    print(f"{n:22s} {d:3d}   {m}")
