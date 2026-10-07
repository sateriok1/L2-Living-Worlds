"""Real-monster P.Def / M.Def benchmark from the server's NPC and spawn data.
For a player at level L the regular benchmark is the spawn-count-weighted mean of the regular monsters (type Monster) spawned on the map with level in [L-2, L+2];
the boss benchmark is the unweighted mean of every RaidBoss/GrandBoss within [L-3, L+5]. Output: monsters.json."""
import os, sys, re, glob, json, statistics
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l2data as L
import xml.etree.ElementTree as ET

def load():
    npcs = {}
    for p in glob.glob(os.path.join(L.DATA, "stats/npcs/*.xml")):
        for n in ET.parse(p).getroot().iter("npc"):
            d = n.find("stats/defence")
            if d is None:
                continue
            npcs[int(n.get("id"))] = dict(name=n.get("name"), level=int(n.get("level")), type=n.get("type"), pdef=float(d.get("physical")), mdef=float(d.get("magical")))
    w = {}
    for p in glob.glob(os.path.join(L.DATA, "spawns/**/*.xml"), recursive=True):
        for m in re.finditer(r"<npc ([^>]*?)/?>", open(p, encoding="utf-8").read()):
            a = dict(re.findall(r'(\w+)="([^"]*)"', m.group(1)))
            if "id" in a:
                w[int(a["id"])] = w.get(int(a["id"]), 0) + int(a.get("count", 1))
    return npcs, w

def wstats(items):
    """items: [(pdef, mdef, weight)] -> weighted mean and weighted median."""
    tw = sum(x[2] for x in items)
    if not tw:
        return None
    def med(i):
        s = sorted(items, key=lambda x: x[i]); c = 0
        for x in s:
            c += x[2]
            if c >= tw / 2:
                return x[i]
    return dict(n=int(tw), pdef_mean=round(sum(x[0] * x[2] for x in items) / tw), mdef_mean=round(sum(x[1] * x[2] for x in items) / tw), pdef_med=round(med(0)), mdef_med=round(med(1)))

def main():
    npcs, w = load()
    reg = [(v["level"], v["pdef"], v["mdef"], w[i]) for i, v in npcs.items() if v["type"] == "Monster" and i in w]
    boss = [(v["level"], v["pdef"], v["mdef"], 1) for i, v in npcs.items() if v["type"] in ("RaidBoss", "GrandBoss")]
    out = {}
    for lvl in range(1, 86):
        r = wstats([(p, m, c) for l, p, m, c in reg if lvl - 2 <= l <= lvl + 2])
        b = wstats([(p, m, c) for l, p, m, c in boss if lvl - 3 <= l <= lvl + 5])
        out[lvl] = dict(regular=r, boss=b)
    json.dump(out, open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "monsters.json"), "w"), indent=1)
    print("regular spawned monsters:", sum(x[3] for x in reg), "bosses:", len(boss))
    for lvl in (10, 20, 30, 40, 50, 60, 70, 76, 80):
        r, b = out[lvl]["regular"], out[lvl]["boss"]
        print(lvl, "regular", r and (r["n"], r["pdef_mean"], r["pdef_med"], r["mdef_mean"], r["mdef_med"]), "boss", b and (b["n"], b["pdef_mean"], b["mdef_mean"]))

if __name__ == "__main__":
    main()
