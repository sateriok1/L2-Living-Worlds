"""Quick estimate of how much a class has to sit and rest while hunting a zone, from HP and MP.

For a class line at a level (the sim's best gear and rotation), against a zone's average monster:
  kill time        zone HP / (rotation damage per second, scaled from the sim's dummy to the zone's defence)
  MP per kill      the rotation's MP use over that time
  HP lost per kill the zone's monsters' hits on the class's P.Def over that time (one monster at a time, times ENGAGED)
  regen            the class's HP and MP regen standing (while fighting and in the overhead between kills), faster sitting
Physical classes burn MP at a rate their regen cannot match if they cast their skills all the time, so they mix skills and auto-attacks:
the share of time on skills is the most their MP regen sustains (they then never rest for MP, they just kill slower); a mage has no
useful auto-attack, so it casts and sits when its MP is out. HP rest comes on top for everyone.
A bot keeps hunting until HP falls to REST_HP (10%) or MP to REST_MP (5%), then sits until it is topped up. Over a long hunt the
pool size cancels out: each kill leaves a deficit (use - regen over the whole cycle) and sitting repays it at the sitting rate.
  sit per kill  = max(HP deficit / HP sit regen, MP deficit / MP sit regen)        (HP and MP refill at the same time)
  kills factor  = cycle / (cycle + sit)         (1.0 = never has to rest; 0.8 = a fifth of the time sitting)
Not modeled: potions, buffs/heals from others, several monsters at once beyond ENGAGED, monster skills and crits, evasion, resting
for the threshold's burst risk. Usage: python3 rest_estimate.py
"""
import csv, json, os
HERE = os.path.dirname(os.path.abspath(__file__))

REST_HP, REST_MP = 0.10, 0.05        # rest when HP or MP falls to this share of the pool
OVERHEAD = 2.5                        # seconds per kill that are not fighting (finding the next mob, looting); same as the cold model
ENGAGED = 1.0                         # monsters hitting the bot at once, on average (1.0 = one at a time; 1.3 for some pulling)
HIT = 0.85                            # share of monster hits that land
SIT = 1.5 / 1.1                       # sitting regen over standing regen (server: standing x1.1, sitting x1.5)
SIM_PDEF, SIM_MDEF = 400.0, 300.0    # the dummy the sim's rotations were measured against

ROLES = {"tank": ("phoenix_knight", "P.Def heavy +shield"), "melee": ("duelist", "P.Def heavy"), "bow": ("sagittarius", "P.Def light"), "mage": ("archmage", "P.Def robe")}
UNLOCK = [(76, 5), (61, 4), (52, 3), (40, 2), (20, 1), (1, 0)]
curves = {r["curve"]: r for r in csv.DictReader(open(os.path.join(HERE, "curves_points.csv"), encoding="utf-8"))}
GR = ["L1", "L20", "L40", "L52", "L61", "L76"]
zones = {r["zone"]: r for r in csv.DictReader(open(os.path.join(HERE, "zone_monsters.csv"), encoding="utf-8"))}


def grade(level):
    return next(g for lv, g in UNLOCK if level >= lv)


def pdef(curve, level):
    return float(curves[curve][GR[grade(level)]])


def rotation(line, level):
    d = json.load(open(os.path.join(HERE, f"rotations_{line}.json")))
    keys = sorted(int(k) for k in d)
    k = max([x for x in keys if x <= level] or [keys[0]])
    return d[str(k)][0], k


def estimate(role, zone_name, level):
    line, pdef_curve = ROLES[role]
    z = zones[zone_name]
    row, key = rotation(line, level)
    hp_z, zp, zm = float(z["hp"]), float(z["p_def"]), float(z["m_def"])
    scale = SIM_MDEF / max(1.0, zm) if role == "mage" else SIM_PDEF / max(1.0, zp)
    windows = {int(w): v for w, v in row["windows"].items()}
    # the window nearest the kill time (damage per second and MP use over it)
    w0 = min(windows)
    dps0 = windows[w0]["dps"] * scale
    tk = hp_z / max(1e-6, dps0)
    w = min(windows, key=lambda x: abs(x - tk))
    dps = windows[w]["dps"] * scale
    tk = hp_z / max(1e-6, dps)
    mp_per_s = windows[w].get("mp_used", 0.0) / w
    st = row["stats"]
    skill_share = 1.0
    if role != "mage" and mp_per_s > 0:
        # auto-attack damage per second against the zone (soulshots: P.Atk x2; crit about x2)
        interval = (500000.0 + (1500.0 * 333.0 if role == "bow" else 0.0)) / st["p_atk_spd"]
        auto_dps = 76.0 * st["p_atk"] * 2.0 * (1 + st["crit_pct"] / 100.0) / max(1.0, zp) / (interval / 1000.0)
        auto_dps = min(auto_dps, dps)
        skill_share = min(1.0, (st["mp_regen_3s"] / 3.0) / mp_per_s)       # share of time on skills that the standing MP regen sustains
        dps = auto_dps + (dps - auto_dps) * skill_share
        tk = hp_z / max(1e-6, dps)
        mp_per_s = mp_per_s * skill_share
    mp_loss = mp_per_s * tk
    hp_dmg = max(70.0 * float(z["p_atk"]) / pdef(pdef_curve, level), 0.0)
    hits_per_s = float(z["atk_speed"]) / 500.0
    hp_loss = hits_per_s * hp_dmg * HIT * tk * ENGAGED
    cycle = tk + OVERHEAD
    hp_def = max(0.0, hp_loss - (st["hp_regen_3s"] / 3.0) * cycle)
    mp_def = max(0.0, mp_loss - (st["mp_regen_3s"] / 3.0) * cycle)
    sit_hp = hp_def / max(1e-9, (st["hp_regen_3s"] / 3.0) * SIT)
    sit_mp = mp_def / max(1e-9, (st["mp_regen_3s"] / 3.0) * SIT)
    sit = max(sit_hp, sit_mp)
    # how many kills before the threshold on a full pool (for the burst: the first rest comes after this many)
    n_hp = (st["hp_max"] * (1 - REST_HP)) / hp_def if hp_def > 0 else float("inf")
    n_mp = (st["mp_max"] * (1 - REST_MP)) / mp_def if mp_def > 0 else float("inf")
    return dict(role=role, zone=zone_name, level=level, sim_level=key, kill_s=tk, skill_share=skill_share, hp_loss=hp_loss, hp_pool=st["hp_max"], hp_def=hp_def, mp_loss=mp_loss, mp_pool=st["mp_max"], mp_def=mp_def,
                kills_before_rest=min(n_hp, n_mp), limit="HP" if sit_hp >= sit_mp and sit_hp > 0 else ("MP" if sit_mp > 0 else "-"), sit_s=sit, factor=cycle / (cycle + sit),
                cycle=cycle, hp_sit_regen=(st["hp_regen_3s"] / 3.0) * SIT, sit_mp=sit_mp)


if __name__ == "__main__":
    picks = [("Talking Island, Western Territory (Northern Area)", 13), ("Cruma Tower", 40), ("Blazing Swamp", 72)]
    extra = [z for z in zones.values() if z["zone"] not in {p[0] for p in picks}]
    for lo in (20, 50, 60):
        z = min(extra, key=lambda r: abs(int(r["min_level"]) - lo))
        picks.insert(1 if lo == 20 else 2 if lo == 50 else 3, (z["zone"], (int(z["min_level"]) + int(z["max_level"])) // 2))
    rows, md = [], ["| zone | level | role | kill s | skills share | HP lost / kill | HP pool | MP used / kill | MP pool | kills before 1st rest | limit | sit s / kill | kills factor |", "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for zone, level in picks:
        for role in ROLES:
            e = estimate(role, zone, level)
            rows.append(e)
            n = e["kills_before_rest"]
            md.append(f"| {zone} | {level} | {role} | {e['kill_s']:.1f} | {e['skill_share']:.0%} | {e['hp_loss']:.0f} | {e['hp_pool']:.0f} | {e['mp_loss']:.0f} | {e['mp_pool']:.0f} | {'never' if n == float('inf') else f'{n:.0f}'} | {e['limit']} | {e['sit_s']:.1f} | {e['factor']:.2f} |")
    open(os.path.join(HERE, "rest_estimate.md"), "w").write("\n".join(md) + "\n")
    with open(os.path.join(HERE, "rest_estimate.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys())); w.writeheader(); w.writerows(rows)
    print("\n".join(md))
