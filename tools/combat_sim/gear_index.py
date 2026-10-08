"""Gear Grimoire index: every armor set, armor piece, weapon and jewel with where it comes from, written as one TSV the
module loads. Sources are resolved one step down, so "Majestic Plate Armor" says it is an exchange for a Sealed Majestic
Plate Armor + Ancient Adena AND where the sealed piece comes from. Usage: python3 gear_index.py [out.tsv]"""
import csv, glob, os, re, sys, collections
import xml.etree.ElementTree as ET
import l2data as L
from item_sources import sources, droppers, quests

D = L.DATA
HERE = os.path.dirname(os.path.abspath(__file__))
GR = {"NONE": "NG", "D": "D", "C": "C", "B": "B", "A": "A", "S": "S"}
ADENA, ANCIENT = 57, 5575

items = {}
for p in glob.glob(os.path.join(D, "stats/items/*.xml")):
    for it in ET.parse(p).getroot().iter("item"):
        items[int(it.get("id"))] = it
sv = lambda it: {s.get("name"): s.get("val") for s in it.findall("set")}
name = lambda i: items[i].get("name") if i in items else "#%d" % i
grade = lambda i: GR.get(sv(items[i]).get("crystal_type", "NONE"), "NG") if i in items else "NG"
def stat(i, k): return sum(float(x.text) for x in items[i].findall("stats/stat") if x.get("type") == k)

npc_name = {}
for p in glob.glob(os.path.join(D, "stats/npcs/*.xml")):
    for n in ET.parse(p).getroot().iter("npc"):
        npc_name[int(n.get("id"))] = n.get("name")

# multisell list -> where it is run from (the html pages that open it)
mult_where = collections.defaultdict(set)
for p in glob.glob(os.path.join(D, "html/**/*.htm*"), recursive=True):
    t = open(p, encoding="utf-8", errors="ignore").read()
    for m in re.findall(r"multisell\s+(\d+)", t):
        base = os.path.splitext(os.path.basename(p))[0]
        n = re.match(r"(\d{4,5})", base)
        mult_where[m].add(npc_name.get(int(n.group(1)), base) if n else base.replace("_", " "))

# real shops only: a buylist price of 0 is a display list, not a sale
shop_price = {}
for p in glob.glob(os.path.join(D, "buylists/*.xml")):
    for it in ET.parse(p).getroot().iter("item"):
        pr = int(it.get("price", 0) or 0)
        if pr > 0:
            i = int(it.get("id")); shop_price[i] = min(pr, shop_price.get(i, pr))

recipes = collections.defaultdict(list)       # produced id -> [(recipe name, craftLevel, rate, [(ing, n)])]
for r in ET.parse(os.path.join(D, "Recipes.xml")).getroot().iter("item"):
    ing = [(int(x.get("id")), int(x.get("count"))) for x in r.findall("ingredient")]
    for pr in r.findall("production"):
        recipes[int(pr.get("id"))].append((r.get("name"), r.get("craftLevel"), r.get("successRate"), ing))

mult = collections.defaultdict(list)          # produced id -> [(list id, [(ing, n)])]
for p in glob.glob(os.path.join(D, "multisell/*.xml")):
    lid = os.path.splitext(os.path.basename(p))[0]
    if os.path.basename(p) in ("DelevelManager.xml", "NoblesseMaster.xml", "SchemeBuffer.xml"):
        continue
    for it in ET.parse(p).getroot().iter("item"):
        ing = [(int(x.get("id")), int(x.get("count"))) for x in it.findall("ingredient")]
        for pr in it.findall("production"):
            mult[int(pr.get("id"))].append((lid, ing))

def money(n): return "{:,}".format(n)
def mobs(i, kind=None, n=3):
    got = sorted(d for d in droppers.get(i, ()) if d[1])
    got = [("L%d %s" % (lv, nm)) for lv, nm in got]
    return ", ".join(got[:n]) + (" +%d" % (len(got) - n) if len(got) > n else "")

def short(i):
    """One-line origin of an ingredient: drop / craft / shop."""
    s = []
    if i in shop_price: s.append("shop %s" % money(shop_price[i]))
    if droppers.get(i): s.append("drop: " + mobs(i, n=2))
    if recipes.get(i): s.append("craft")
    if mult.get(i): s.append("exchange")
    if quests.get(i): s.append("quest")
    return "/".join(s) or "?"

def ing_text(ing, deep):
    out = []
    for i, n in ing:
        t = "%s x%s" % (name(i), money(n))
        if deep and i not in (ADENA, ANCIENT):
            t += " [" + short(i) + "]"
        out.append(t)
    return ", ".join(out)

def lines_for(i):
    L_ = []
    if i in shop_price: L_.append("Shop: %s adena" % money(shop_price[i]))
    for lid, ing in mult.get(i, [])[:3]:
        w = ", ".join(sorted(mult_where.get(lid, [])))
        L_.append("Exchange%s: %s" % (" at " + w if w else " (list %s)" % lid, ing_text(ing, True)))
    for nm, lvl, rate, ing in recipes.get(i, [])[:2]:
        L_.append("Craft (lvl %s, %s%%): %s" % (lvl, rate, ing_text(ing, True)))
    if droppers.get(i):
        L_.append("Drop: " + mobs(i, n=5))
    if quests.get(i):
        L_.append("Quest: " + ", ".join(sorted(quests[i])[:3]))
    return L_ or ["Not obtainable (no shop, drop, craft, exchange or quest)"]

def clean(s): return re.sub(r"[\t\r\n]+", " ", s)

# ---- tier tags from the tier lists we already built
tags = collections.defaultdict(list)
def tag_csv(fn, key_name, label):
    path = os.path.join(HERE, fn)
    if not os.path.exists(path): return
    for r in csv.DictReader(open(path, encoding="utf-8")):
        tags[(key_name, r[label[0]] if isinstance(label, tuple) else r[label])].append(r)
for r in csv.DictReader(open(os.path.join(HERE, "armor_tiers.csv"), encoding="utf-8")):
    tags[("set", r["set"], r["grade"])].append("%s #%s" % (r["list"], r["rank"]))
for r in csv.DictReader(open(os.path.join(HERE, "robe_tiers.csv"), encoding="utf-8")):
    tags[("set", r["set"], r["grade"])].append("%s #%s" % (r["role"], r["rank"]))

out_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "gear_index.tsv")
rows = []
piece_set = {}

# ---- armor sets
SLOTS = ["chest", "legs", "head", "gloves", "feet", "shield"]
nset = 0
for p in sorted(glob.glob(os.path.join(D, "stats/armorsets/*_grade.xml"))):
    for st in ET.parse(p).getroot().iter("set"):
        ids = {s: int(st.find(s).get("id")) for s in SLOTS if st.find(s) is not None}
        if "chest" not in ids or ids["chest"] not in items: continue
        chest = items[ids["chest"]]
        nm = re.sub(r"\s+(Armor|Robe|Breastplate|Tunic|Tunic of|Plate)\b.*$", "", name(ids["chest"])) or name(ids["chest"])
        setname = name(ids["chest"])
        g = grade(ids["chest"])
        atype = sv(chest).get("armor_type", "?")
        sid = int(st.get("id"))
        bonus = ", ".join("%s %+d" % (k.upper(), int(float(st.find(k).get("val")))) for k in ("str", "dex", "con", "int", "wit", "men") if st.find(k) is not None)
        tg = []
        for key in {setname, nm}:
            tg += tags.get(("set", key, g), [])
        for fn_set in re.findall(r"[A-Za-z' ]+", " ".join(name(v) for v in ids.values())):
            pass
        # tags are keyed by set name (the sets in armor_tiers use the chest/set name); also try the chest's own name
        rows.append(("SET", sid, setname, g, atype, ",".join(str(ids[s]) if s in ids else "0" for s in SLOTS), bonus or "-", "; ".join(sorted(set(tg))) or "-"))
        for s, i in ids.items():
            piece_set[i] = sid
        nset += 1

# ---- pieces: armor, shields, jewelry, weapons
JEWEL = {"neck": "Necklace", "rear;lear": "Earring", "rfinger;lfinger": "Ring"}
npc_ = 0
for i, it in sorted(items.items()):
    s = sv(it)
    if it.get("type") not in ("Weapon", "Armor"): continue
    if s.get("for_npc") == "true" or (s.get("is_tradable") == "false" and s.get("is_dropable") == "false"): continue
    if re.search(r"Shadow Item|Event|For NPC|Chrono|Clan Pledge|\(Rental\)|Hero", it.get("name")): continue
    bp = s.get("bodypart", "")
    g = grade(i)
    ls = lines_for(i)
    if ls[0].startswith("Not obtainable") and i not in piece_set: continue
    if it.get("type") == "Weapon":
        kind, slot, atype = "weapon", bp, s.get("weapon_type", "?")
        meta = "P.Atk %g M.Atk %g" % (stat(i, "pAtk"), stat(i, "mAtk"))
    elif bp in JEWEL:
        kind, slot, atype = "jewel", JEWEL[bp], "-"
        meta = "M.Def %g MP %g" % (stat(i, "mDef"), stat(i, "maxMp"))
    else:
        kind, slot, atype = "armor", bp, s.get("armor_type", "SHIELD" if bp == "lhand" else "?")
        meta = "P.Def %g M.Def %g" % (stat(i, "pDef"), stat(i, "mDef"))
    rows.append(("PIECE", i, name(i), g, kind, slot, atype, piece_set.get(i, 0), meta, clean(" || ".join(ls))))

with open(out_path, "w", encoding="utf-8", newline="") as f:
    f.write("#SET\tid\tname\tgrade\tarmortype\tchest,legs,head,gloves,feet,shield\tbonuses\ttier tags\n")
    f.write("#PIECE\tid\tname\tgrade\tkind\tslot\ttype\tset\tstats\tsources (||-separated)\n")
    for r in rows:
        f.write("\t".join(clean(str(x)) for x in r) + "\n")
print("%d sets, %d pieces -> %s" % (nset, len(rows) - nset, out_path))
