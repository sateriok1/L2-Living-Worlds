"""Levels where a class line's learnable skills change (the only levels worth simulating)."""
import json, sys
import l2data

IGNORE_NAMES = ("Craft", "Create ", "Lucky", "Common", "Dwarven Craft", "Fishing", "Pumping", "Reeling", "Expand", "Weapon Mastery",)
sk = l2data.load_skills()
names, parent = l2data.load_classes()
trees = l2data.load_trees()


def relevant(sid, lv):
    d = sk.get((sid, lv))
    if d is None:
        return False
    if d.name.startswith(IGNORE_NAMES) and d.name != "Weapon Mastery":
        return False
    if d.op == "P":
        return True  # passives can change stats
    return bool(d.effects) or d.power > 0


def lineage_breakpoints(leaf):
    prev, out = {}, []
    for level in range(1, 81):
        cur = {s: l for s, l in l2data.learned(leaf, level, trees, parent).items() if relevant(s, l)}
        # the class line is the leaf's ancestors; walking up from a leaf only sees the leaf's own skills, so merge ancestors by tier
        if cur != prev:
            new = sorted(s for s in cur if cur[s] != prev.get(s))
            out.append((level, new))
            prev = cur
    return out


# Third-profession leaves: classes with no children
children = {p for p in parent.values()}
leaves = [c for c in names if c not in children and c >= 88]
res = {}
for leaf in sorted(leaves):
    bps = lineage_breakpoints(leaf)
    res[names[leaf]] = [{"level": lv, "changed": [(s) for s in new]} for lv, new in bps]
json.dump(res, open("breakpoints.json", "w"), indent=1)
tot = 0
for n, b in res.items():
    tot += len(b)
    print(f"{n:22s} {len(b):3d} breakpoints  first levels {[x['level'] for x in b][:12]}")
print("lines", len(res), "total breakpoints", tot)
