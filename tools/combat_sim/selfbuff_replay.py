"""Self-buff tier for the cold model: replays each line's best rotation (from the no-buff file) with every self buff the class has learned at the level on
(free and permanent, user decision) and writes rotations_<slug>[_none_dagger_front]_selfall.json in the same schema (windows -> dps).
Daggers: the base is the front-facing dagger file (it already carries Vicious Stance, Mortal Strike and Focus). Usage: python3 selfbuff_replay.py [line ...]"""
import json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import l2data as L, stats_model as S, combat_sim as C, rotations as R

DAGGER = {"Adventurer", "Wind Rider", "Ghost Hunter"}

def main():
    names, parent = L.load_classes(); trees = L.load_trees(); sk_all = L.load_skills()
    want = sys.argv[1:] or sorted(n for n in names.values() if os.path.exists(os.path.join(HERE, "rotations_%s.json" % n.lower().replace(" ", "_"))) or n in DAGGER)
    for line in want:
        slug = line.lower().replace(" ", "_"); dag = line in DAGGER
        src = os.path.join(HERE, "rotations_%s_none_dagger_front.json" % slug if dag else "rotations_%s.json" % slug)
        if not os.path.exists(src):
            continue
        base = json.load(open(src)); leaf = [k for k, v in names.items() if v == line][0]
        weapons = S.read_csv(os.path.join(HERE, "gear_%s_weapons.csv" % slug)); armors = S.read_csv(os.path.join(HERE, "gear_%s_armor.csv" % slug))
        R.set_mage(any(str(x.get("shot", "")).startswith("SPS") for x in weapons))
        R.POSITION = "front" if dag else ""
        bname = "none_dagger_selfall" if dag else "none_selfall"
        out = {}
        for lv, rows in base.items():
            level = int(lv); cid = S.class_at(leaf, level, parent); learned = L.learned(cid, level, trees, parent)
            skills = {sid: sk_all[(sid, l)] for sid, l in learned.items() if (sid, l) in sk_all}
            opts = S.options(weapons, armors, level); res = []
            for row in rows:
                match = [(w, a) for w, a in opts if ("%s %s" % (w["weapon_name"], w["variant"])).strip() == row["weapon"] and a["set_name"] == row["armor"]]
                if not match:
                    continue
                w, a = match[0]
                _st, actor, later = R.setup(level, cid, w, a, learned, bname, row.get("focus_id"))
                new = {}
                for ms, b in row["windows"].items():
                    tl = []
                    C.simulate(actor, R.DUMMY, skills, C.Policy(tuple(b["order_ids"]), b["hold"]), R.WINDOWS[-1] * 1000, timeline=tl, later=later)
                    dmg, _mp = R.at(tl, int(ms) * 1000)
                    new[ms] = {"dps": max(dmg / int(ms), b["dps"]), "order": b["order"], "order_ids": b["order_ids"], "hold": b["hold"], "mp_used": 0.0}
                ent = []
                for sid, _l in R.self_set(learned, w):
                    ent += S.passive_entries(sid, learned[sid], w["weapon_type"], w.get("hands"))
                pm, md = S._apply(ent, "pDef")[0], S._apply(ent, "mDef")[0]
                run_add = 0.0
                for rid in (4, 230, 451):                       # Dash and Sonic Move last 15 s per 60-80 s, Sprint 20 min: weight by uptime
                    if rid in learned:
                        ent = S.passive_entries(rid, learned[rid], w["weapon_type"], w.get("hands"))
                        el = S._skill_elements()[rid]
                        up = min(1.0, float(el.findtext("abnormalTime")) / max(1.0, float(el.findtext("reuseDelay") or 0) / 1000.0))
                        if ent:
                            run_add = max(run_add, ent[0][2] * up)
                res.append({"run_add": run_add, "pdef_mul": pm, "mdef_mul": md, "weapon": row["weapon"], "armor": row["armor"], "focus_id": row.get("focus_id"), "stats": _st, "skills": row["skills"], "windows": new,
                            "selfbuffs": [sid for sid, _ in R.self_set(learned, w)]})
            out[level] = res
            if res:
                b0 = max(res, key=lambda r: r["windows"]["60"]["dps"]); o0 = max(rows, key=lambda r: r["windows"]["60"]["dps"])
                print("%s L%d none %.1f -> self %.1f (%d buffs)" % (line, level, o0["windows"]["60"]["dps"], b0["windows"]["60"]["dps"], len(b0["selfbuffs"])), flush=True)
        json.dump(out, open(os.path.join(HERE, "rotations_%s_selfall%s.json" % (slug, "_dagger_front" if dag else "")), "w"))

main()
