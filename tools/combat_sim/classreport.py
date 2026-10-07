"""Per-class write-up generator for the combat-simulator study.
Usage: python3 classreport.py "<Line>" [--out=dir]   -> prints markdown; reads rotations_*.json (none + party tiers) and the current PhantomPlaystyles.xml entry."""
import json, os, re, subprocess, sys
import xml.etree.ElementTree as ET
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l2data as L

HERE = os.path.dirname(os.path.abspath(__file__))
DAGGER = {"Adventurer", "Wind Rider", "Ghost Hunter"}
ORDER_USES = {"ROTATION", "CONTROL", "DEBUFF", "PULL"}


def playstyle(line, names, parent):
    cid = [k for k, v in names.items() if v == line][0]
    chain = [cid]
    while chain[-1] in parent:
        chain.append(parent[chain[-1]])
    root = ET.parse(os.path.join(L.DATA, "PhantomPlaystyles.xml")).getroot()
    pss = [(ps, [int(x) for x in ps.get("classIds", "").replace(" ", "").split(",") if x]) for ps in root.iter("playstyle")]
    for c in chain:                      # most specific class first: the leaf, then its parents
        for ps, ids in pss:
            if c in ids:
                return ps
    return None


def table_order(ps):
    out, mx = [], {}
    if ps is None:
        return out, mx, ""
    for s in ps.findall("skill"):
        if s.get("use") in ORDER_USES:
            sid = int(s.get("id"))
            if sid not in out:
                out.append(sid)
            if s.get("maxLevel"):
                mx[sid] = int(s.get("maxLevel"))
    return out, mx, ps.get("name")


TITAN_TIER = {"none": "none_self_vicious", "party": "party_self_vicious"}      # the Titan study used self buffs (Rage, Vicious Stance) in both tiers


def fname(line, tier, pos=None):
    slug = line.lower().replace(" ", "_")
    if line == "Titan":
        t = TITAN_TIER[tier]
        return f"rotations_{slug}_{t}.json"
    if line in DAGGER:
        return f"rotations_{slug}_{tier}_dagger_{pos}.json"
    return f"rotations_{slug}{'' if tier == 'none' else '_' + tier}.json"


def bands(data, win="60"):
    rows = []
    for lv in sorted(data, key=int):
        b = max(data[lv], key=lambda r: r["windows"][win]["dps"])
        w = b["windows"][win]
        foc = b.get("focus") or ""
        key = (b["weapon"], b["armor"], foc, tuple(w["order"]))
        if rows and rows[-1]["key"] == key:
            rows[-1]["hi"] = int(lv); rows[-1]["d1"] = w["dps"]
        else:
            rows.append(dict(key=key, lo=int(lv), hi=int(lv), d0=w["dps"], d1=w["dps"]))
    return rows


def band_md(data):
    out = ["| Levels | DPS @60 s | Weapon | Armor | Rotation (priority order) |", "|---|---|---|---|---|"]
    for r in bands(data):
        wpn, arm, foc, order = r["key"]
        lv = f"{r['lo']}" if r["lo"] == r["hi"] else f"{r['lo']}–{r['hi']}"
        d = f"{r['d0']:.0f}" if round(r["d0"]) == round(r["d1"]) else f"{r['d0']:.0f}–{r['d1']:.0f}"
        w = wpn + (f" + {foc}" if foc else "")
        out.append(f"| {lv} | {d} | {w} | {arm} | {' > '.join(order) if order else 'autos only'} |")
    return "\n".join(out)


def compare(line, tier, order, mx, pos=None):
    env = dict(os.environ)
    if pos:
        env["L2_POS"] = pos
    buff = f"{tier}_dagger" if line in DAGGER else (TITAN_TIER[tier] if line == "Titan" else tier)
    cmd = [sys.executable, os.path.join(HERE, "compare_table.py"), line, buff] + [str(x) for x in order]
    if mx:
        cmd.append("--max=" + ",".join(f"{k}:{v}" for k, v in mx.items()))
    p = subprocess.run(cmd, capture_output=True, text=True, env=env, cwd=HERE)
    rows = {}
    mean = worst = None
    for ln in p.stdout.splitlines():
        m = re.match(r"\s*(\d+)\s+(.*)", ln)
        if m and "tbl/opt" not in ln and "%" in ln:
            pct = re.findall(r"(\d+)%", ln)
            nums = re.findall(r"(\d+)/(\d+)", ln)
            if len(pct) >= 3:
                rows[int(m.group(1))] = (int(nums[2][0]), int(nums[2][1]), int(pct[2]), ln.split("   ", 1)[-1][-1:] and ln.split("]  ")[-1] if False else ln)
        if ln.startswith("mean ratio"):
            mm = re.findall(r"([\d.]+)%", ln); mean, worst = float(mm[0]), float(mm[1])
    return rows, mean, worst, p.stdout


CAVEATS = {
    "melee": "Single target only; stun landing assumed at the skill's nominal rate; no incoming damage, healing or tanking; skill timings and formulas come from the server source but have not been confirmed in game (only stats were validated against the Stat Dump).",
    "bow": "Bow auto cycle includes the weapon reuse delay (read from the server source, not yet confirmed in game); arrows and bow MP cost ignored; no kiting or range.",
    "dagger": "Positioning is the main variable: 'behind' assumes the mob never turns, 'facing' assumes it always faces the dagger; real fights sit in between. Instant-kill 'Lethal' procs, Bleed/Sting weapon abilities and blow evasion are not modelled. Focus, Vicious Stance and Mortal Strike are assumed up for the whole fight.",
    "spell": "Spell success is assumed (no magic resist rolls); blessed spirit shots always charged; infinite mana (finite mana changes the picture a lot for nukers); flat DoTs use the skill's nominal land chance; area spells are scored on one target.",
    "support": "Heals, buffs and debuffs have no damage value in this study; the numbers show only what the class can do with its own attack skills while idle.",
    "summoner": "Servitor, cubic and summon damage is NOT modelled, so the numbers are the summoner's own spells only and understate real output.",
}
KIND = {"Titan": "melee", "Maestro": "melee", "Fortune Seeker": "melee", "Dreadnought": "melee", "Phoenix Knight": "melee", "Hell Knight": "melee", "Eva's Templar": "melee",
        "Shillien Templar": "melee", "Sword Muse": "melee", "Spectral Dancer": "melee", "Sagittarius": "bow", "Moonlight Sentinel": "bow", "Ghost Sentinel": "bow",
        "Adventurer": "dagger", "Wind Rider": "dagger", "Ghost Hunter": "dagger", "Archmage": "spell", "Soultaker": "spell", "Mystic Muse": "spell", "Storm Screamer": "spell",
        "Arcana Lord": "summoner", "Elemental Master": "summoner", "Spectral Master": "summoner", "Dominator": "support", "Doom Cryer": "support",
        "Hierophant": "support", "Cardinal": "support", "Eva's Saint": "support", "Shillien Saint": "support"}


def findings(line, order, sk, datas):
    out = []
    names_of = lambda ids: ", ".join(sk_name(sk, i) for i in ids)
    used = {}
    for lab, d in datas.items():
        for lv, rows in d.items():
            b = max(rows, key=lambda r: r["windows"]["60"]["dps"])
            for n in b["windows"]["60"]["order"]:
                used.setdefault(n, set()).add(int(lv))
    tnames = [sk_name(sk, i) for i in order]
    unused = [n for n in tnames if n not in used]
    missing = sorted(n for n in used if n not in tnames)
    for lab, d in datas.items():
        top = str(max(d, key=int)); b = max(d[top], key=lambda r: r["windows"]["60"]["dps"])
        out.append(f"{lab}: L{top} best is {b['windows']['60']['dps']:.0f} DPS ({b['weapon']}{' + ' + b['focus'] if b.get('focus') else ''}).")
        autos = [int(lv) for lv, rows in d.items() if not max(rows, key=lambda r: r["windows"]["60"]["dps"])["windows"]["60"]["order"]]
        if autos:
            out.append(f"{lab}: plain autos beat every skill at {_ranges(autos)}.")
    if missing:
        out.append("Skills the optimum uses that the current table lacks: " + ", ".join(missing) + ".")
    if unused:
        out.append("Skills in the current table that the optimum never uses (control/utility or too weak): " + ", ".join(unused) + ".")
    return out


def sk_name(sk, i):
    return next((sk[(i, l)].name for l in range(1, 90) if (i, l) in sk), str(i))


def _ranges(lv):
    lv = sorted(lv); out = []; a = b = lv[0]
    for x in lv[1:]:
        if x - b <= 4:
            b = x
        else:
            out.append((a, b)); a = b = x
    out.append((a, b))
    return ", ".join(f"L{a}" if a == b else f"L{a}–{b}" for a, b in out)


def main():
    line = sys.argv[1]
    names, parent = L.load_classes()
    sk = L.load_skills()
    ps = playstyle(line, names, parent)
    order, mx, psname = table_order(ps)
    sname = lambda i: next((sk[(i, l)].name for l in range(1, 90) if (i, l) in sk), str(i))
    positions = ["behind", "front"] if line in DAGGER else [None]
    print(f"## {line}\n")
    print(f"Current phantom table: *{psname}* — rotation entries: " + (", ".join(sname(i) + (f" (≤L{mx[i]})" if i in mx else "") for i in order) or "none") + "\n")
    datas = {}
    for pos in positions:
        for tier, label in (("none", "No buffs"), ("party", "Full party of buffers")):
            f = os.path.join(HERE, fname(line, tier, pos))
            if not os.path.exists(f):
                print(f"*{label}{' / ' + pos if pos else ''}: results not available.*\n"); continue
            data = json.load(open(f))
            datas[label + (f" ({pos})" if pos else "")] = data
            title = label + (f", {'target facing you (bad positioning)' if pos == 'front' else 'attacking from behind (perfect positioning)'}" if pos else "")
            print(f"### {title}\n")
            print(band_md(data) + "\n")
            rows, mean, worst, raw = compare(line, tier, order, mx, pos)
            if mean is not None:
                lv_s = ", ".join(f"L{k}: {v[2]}%" for k, v in rows.items() if k in (40, 61, 80) or k == max(rows))
                print(f"Current table vs optimum (60 s): mean {mean:.0f}% of optimum, worst {worst:.0f}%. Selected levels — {lv_s}.\n")
            else:
                print("*Current table could not be scored.*\n")
    print("### Findings\n")
    for b in findings(line, order, sk, datas):
        print("- " + b)
    print("\n### Assumptions and caveats\n")
    print(CAVEATS[KIND[line]] + "\n")


if __name__ == "__main__":
    main()
