"""Levels where a class line's rotation-relevant skills change (the only levels worth simulating)."""
import json
import l2data, relevance

names, parent = l2data.load_classes()
trees = l2data.load_trees()
info = relevance.classify_all()


def lineage_breakpoints(leaf):
    prev, out = {}, []
    for level in range(1, 81):
        cur = {s: l for s, l in l2data.learned(leaf, level, trees, parent).items() if relevance.keep(info.get(s))}
        if cur != prev:
            changed = sorted(s for s in cur if cur[s] != prev.get(s))
            out.append({"level": level, "changed": [{"id": s, "level": cur[s], "name": info[s]["name"], "kinds": sorted(info[s]["kinds"])} for s in changed]})
            prev = cur
    return out


children = set(parent.values())
leaves = sorted(c for c in names if c not in children and c >= 88)
res = {names[l]: lineage_breakpoints(l) for l in leaves}
json.dump(res, open("breakpoints.json", "w"), indent=1)
tot = 0
for n, b in res.items():
    tot += len(b)
    print(f"{n:22s} {len(b):3d}  levels {[x['level'] for x in b]}")
print("lines", len(res), "total breakpoints", tot)
