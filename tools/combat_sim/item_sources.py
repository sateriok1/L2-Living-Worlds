"""Where each item comes from: sources[id] (drop, spoil, recipe, shop, multisell), droppers[id], quests[id]."""
import glob, os, re, collections
import xml.etree.ElementTree as ET
import l2data as L

D = L.DATA
sources = collections.defaultdict(set)
droppers = collections.defaultdict(set)
quests = collections.defaultdict(set)

for p in glob.glob(os.path.join(D, "stats/npcs/*.xml")):          # custom/ is a subfolder and is not read
    for npc in ET.parse(p).getroot().iter("npc"):
        for tag in ("dropLists", "spoilLists"):
            for el in npc.iter(tag):
                for it in el.iter("item"):
                    sources[int(it.get("id"))].add("drop" if tag == "dropLists" else "spoil")
                    droppers[int(it.get("id"))].add((int(npc.get("level", 0) or 0), npc.get("name")))
for rec in ET.parse(os.path.join(D, "Recipes.xml")).getroot().iter("item"):
    for pr in rec.iter("production"):
        sources[int(pr.get("id"))].add("recipe")
for p in glob.glob(os.path.join(D, "buylists/*.xml")):
    for it in ET.parse(p).getroot().iter("item"):
        sources[int(it.get("id"))].add("shop")
for p in glob.glob(os.path.join(D, "multisell/*.xml")):
    if os.path.basename(p) in ("DelevelManager.xml", "NoblesseMaster.xml", "SchemeBuffer.xml"): continue
    for it in ET.parse(p).getroot().iter("production"):
        sources[int(it.get("id"))].add("multisell")
quest_text = {}      # quest -> item ids it hands out (giveItems / rewardItems with a number or a named constant)
for p in glob.glob(os.path.join(D, "scripts/quests/**/*.java"), recursive=True):
    t = open(p, encoding="utf-8", errors="ignore").read()
    consts = {n: int(v) for n, v in re.findall(r"(?:int|Integer)\s+(\w+)\s*=\s*(\d{2,5})\s*;", t)}
    ids = set()
    for arg in re.findall(r"(?:giveItems|rewardItems|giveItemsAndRandom)\s*\(\s*(?:\w+\s*,\s*)?(\w+)", t):
        v = int(arg) if arg.isdigit() else consts.get(arg)
        if v: ids.add(v)
    if ids:
        quest_text[p] = ids
        for n in ids: quests[n].add(re.sub(r"^Q\d+_", "", os.path.basename(p)[:-5]))
quest_ids = set().union(*quest_text.values())

