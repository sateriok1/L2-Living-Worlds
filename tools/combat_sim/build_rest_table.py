"""REST rows for zone_combat.tsv: how much a class has to sit and rest in a zone, from rest_estimate.py (HP and MP, the sim's best rotation).

REST  zone  role  level  cycleSeconds  hpDeficitPerKill  hpSitRegenPerSecond  mpSitSeconds
cycleSeconds     kill time plus the 2.5 s between kills
hpDeficitPerKill HP the bot is down after a kill, over what its standing regen refills in the cycle
hpSitRegenPerSecond HP regained a second while sitting
mpSitSeconds     seconds of sitting per kill to refill its MP
The module turns them into a kills factor: cycle / (cycle + max(HP deficit left after potions / sit regen, MP sit seconds)).
Levels per zone: its range in steps of 3. Usage: python3 build_rest_table.py [out.tsv]"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import rest_estimate as R
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "rest_rows.tsv")
rows = []
for name, z in R.zones.items():
    lo, hi = int(z["min_level"]), int(z["max_level"])
    for level in sorted(set(list(range(lo, hi + 1, 3)) + [hi])):
        for role in R.ROLES:
            try:
                e = R.estimate(role, name, level)
            except Exception:
                continue
            rows.append("REST\t%s\t%s\t%d\t%.3f\t%.2f\t%.3f\t%.3f" % (name, role, level, e["cycle"], e["hp_def"], e["hp_sit_regen"], e["sit_mp"]))
with open(out, "w", encoding="utf-8", newline="") as f:
    f.write("#REST\tzone\trole\tlevel\tcycleSeconds\thpDeficitPerKill\thpSitRegenPerSecond\tmpSitSeconds   (see tools/combat_sim/build_rest_table.py)\n")
    f.write("\n".join(rows) + "\n")
print(len(rows), "rows ->", out)
