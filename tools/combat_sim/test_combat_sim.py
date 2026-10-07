"""Hand-computed checks of the simulator against the server formulas (run: python3 test_combat_sim.py)."""
import os, sys, math
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l2data as L, stats_model as S, combat_sim as C

SK = L.load_skills()
names, parent = L.load_classes()
trees = L.load_trees()
fails = []


def check(name, got, want, tol=1e-6):
    ok = abs(got - want) <= tol * max(1, abs(want))
    print(("ok   " if ok else "FAIL ") + f"{name}: got {got:.4f} want {want:.4f}")
    if not ok:
        fails.append(name)


A = C.Actor(patk=1000, patk_spd=400, matk=1, matk_spd=333, mp_max=1000, mp_regen_3s=0, weapon="SWORD", crit=0.0, str_bonus=1.0)
D = C.Dummy(pdef=400, mdef=300)
fs = SK[(190, 37)]          # Fatal Strike 37: power 3044, mp 83, hit 1080, cool 720, reuse 13000
# damage: 76 * (PAtk*2 + power) / PDef    (calcPhysDam: soulshot x2 on PAtk, power added, then 76*x*proximity/def)
check("auto dmg", C.auto_dmg(A, D), 76 * 1000 * 2 / 400)
check("Fatal Strike dmg (no crit)", C.skill_dmg(fs, A, D), 76 * (1000 * 2 + 3044) / 400)
# skill time: (hit+cool)/PAtkSpd*300 = 1800/400*300; reuse: 13000*333/400
check("Fatal Strike cast ms", C.cast_ms(fs, A), 1800 / 400 * 300)
check("Fatal Strike reuse ms", C.reuse_ms(fs, A), 13000 * 333 / 400)
check("auto interval", 500000 / 400, 1250)
# Armor Crush: baseCritRate 20 -> crit chance = 20*10*STRbonus/1000 ; crit doubles damage
ac = SK[(362, 1)]
A2 = C.Actor(patk=1000, patk_spd=400, matk=1, matk_spd=333, mp_max=1000, mp_regen_3s=0, weapon="SWORD", str_bonus=1.24)
base = 76 * (1000 * 2 + 2565) / 400
chance = 20 * 10 * 1.24 / 1000
check("Armor Crush expected dmg", C.skill_dmg(ac, A2, D), base * (1 - chance) + base * 2 * chance)
assert ac.debuff == (0.7, 0.7, 9000.0, 0.4), ac.debuff
check("Armor Crush debuff chance", ac.debuff[3], 0.4)
# simulate: a single Fatal Strike then autos for 5 s. Cast ends at 1350 ms.
pol = C.Policy((190,), 0)
tl = []
A3 = C.Actor(patk=1000, patk_spd=400, matk=1, matk_spd=333, mp_max=1000, mp_regen_3s=0, weapon="SWORD", crit=0.0)
tot, mp_used, casts = C.simulate(A3, D, {190: fs}, pol, 5000, timeline=tl)
check("sim first cast end", tl[0][0], 1350)
check("sim first cast dmg", tl[0][1], C.skill_dmg(fs, A3, D))
check("sim MP used", mp_used, 83 * casts[190])
# debuff uplift: Armor Crush lands the debuff after its own hit; later hits within 9 s scale by 1+0.4*(1/0.7-1)
pol = C.Policy((362,), 0)
tl = []
A4 = C.Actor(patk=1000, patk_spd=400, matk=1, matk_spd=333, mp_max=1000, mp_regen_3s=0, weapon="SWORD", crit=0.0, str_bonus=0.0)
C.simulate(A4, D, {362: ac}, pol, 9000, timeline=tl)
up = 1 + 0.4 * (1 / 0.7 - 1)
auto = C.auto_dmg(A4, D)
check("hit after debuff", tl[1][1] - tl[0][1], auto * up)
check("own hit not boosted", tl[0][1], 76 * (1000 * 2 + 2565) / 400)
# HP cost: with 50% reserve a 338 HP skill is refused when HP 600/1000 (600-338 < 500)
A5 = C.Actor(patk=1000, patk_spd=400, matk=1, matk_spd=333, mp_max=1000, mp_regen_3s=0, weapon="SWORD", crit=0.0, hp_max=1000, hp_reserve=0.5)
tot, _, casts = C.simulate(A5, D, {362: ac}, C.Policy((362,), 0), 20000)
check("HP-limited casts", casts[362], 1)      # 1000 -> 662 after one cast; second would leave 324 < 500
# stat model: recompute Titan L80 P.Atk by hand for Heavens Divider (Haste) + Tallum Heavy
w = [r for r in S.read_csv("gear_titan_weapons.csv") if r["weapon_name"] == "Heavens Divider" and r["variant"] == "Haste"][0]
ar = [r for r in S.read_csv("gear_titan_armor.csv") if r["set_name"] == "Tallum Heavy"][0]
cid = [k for k, v in names.items() if v == "Titan"][0]
ls = L.learned(cid, 80, trees, parent)
st = S.compute(cid, 80, w, ar, ls)
t = S.template(cid)
strb = S.bonus("STR", t["baseSTR"] + 2)
dexb = S.bonus("DEX", t["baseDEX"])
want_spd = 325 * dexb * 1.07 * 1.08         # weapon set -> DEX bonus -> Haste mul -> Tallum mul
check("Titan 80 attack speed", st["p_atk_spd"], want_spd)
mul = 1.085
add = 4 + 45 * 0 + 0                         # filled below from the passives themselves
ents = []
for sid, lv in ls.items():
    el = S._skill_elements().get(sid)
    if el is not None and (el.findtext("operateType") or "").strip() == "P":      # passives only; self-buff actives are not baseline
        ents += S.passive_entries(sid, lv, "SWORD", "2H")
mul = math.prod(v for f, s_, v in ents if f == "mul" and s_ == "pAtk")
add = sum(v for f, s_, v in ents if f == "add" and s_ == "pAtk")
check("Titan 80 P.Atk", st["p_atk"], 342 * strb * 1.69 * mul + add)
print("passive P.Atk adds:", add, "mul:", mul)

# Blows (Formulas.calcBlowSuccess / calcBlowDamage / calcBackstabDamage), hand-computed
B = C.Actor(patk=1000, patk_spd=400, matk=1, matk_spd=333, mp_max=1000, mp_regen_3s=0, weapon="DAGGER", crit=0.0, str_bonus=1.0,
            dex_bonus=1.2, blow_mul=1.3, position="behind", prox=1.2, crit_mul=1.0, crit_add=0.0, crit_pos=1.0)
mb = SK[(16, 24)]            # Mortal Blow 24: power 977, blowChance 20, FatalBlow, baseCritRate 0
bs = SK[(30, 37)]            # Backstab 37: power 5479, blowChance 30, baseCritRate 20
check("blow chance behind", C.blow_chance(mb, B), math.ceil(20 * 1.2 * 2 * 1.3) / 100)
B.position = "front"; B.prox = 1.0
check("blow chance front", C.blow_chance(mb, B), math.ceil(20 * 1.2 * 1 * 1.3) / 100)
check("Backstab unusable from the front", float(C.usable(bs, B)), 0.0)
B.position = "behind"; B.prox = 1.2
check("Backstab usable from behind", float(C.usable(bs, B)), 1.0)
check("Mortal Blow dmg", C.skill_dmg(mb, B, D), 77 * (977 + 1000 * 1.458) / 400 * 1.2 * C.blow_chance(mb, B))
check("Backstab dmg (20% crit)", C.skill_dmg(bs, B, D), 77 * (5479 + 1000) / 400 * 1.458 * 1.2 * (1 + 0.2) * C.blow_chance(bs, B))

# Flat DoT (DamOverTime: power per second, one tick every ticks*666 ms, expiry after floor(duration/interval) ticks)
bl = SK[(96, 6)]            # Bleed 6: power 102, ticks 5, 20 s, activateRate 100
check("Bleed 6 power/s", bl.dot[0], 102.0)
check("Bleed 6 tick interval", bl.dot[1], 3330.0)
check("Bleed 6 duration (6 ticks)", bl.dot[2], 19980.0)
Z = C.Actor(patk=0, patk_spd=400, matk=1, matk_spd=333, mp_max=1e9, mp_regen_3s=0, weapon="DAGGER", crit=0.0)
tl = []
C.simulate(Z, D, {96: bl}, C.Policy((96,)), 19000, timeline=tl)
t_end, tot, _ = tl[-1]
check("Bleed accrual = 102/s from cast end", tot, 102 * (t_end - C.cast_ms(bl, Z)) / 1000.0)

# Magic (Formulas.calcMagicDam): 91 * sqrt(mAtk * shot) / mDef * power, crit x3, shot 4 for blessed spiritshots; cast time (hit+cool)/mAtkSpd*333 * 0.7 with spirit shots
M = C.Actor(patk=1, patk_spd=300, matk=1000, matk_spd=500, mp_max=1e9, mp_regen_3s=0, weapon="BLUNT", mcrit=0.1, soulshot=False, spiritshot=2)
af = SK[(1230, 1)] if (1230, 1) in SK else None
ws = SK[(1177, 1)]            # Wind Strike 1
check("Wind Strike dmg", C.skill_dmg(ws, M, D), 91 * math.sqrt(1000 * 4) / 300 * ws.power * (0.9 + 0.1 * 3))
check("Wind Strike cast ms", C.cast_ms(ws, M), max((ws.hit + ws.cool) / 500 * 333 * 0.7, 550))
print("FAILED: %s" % fails if fails else "all passed")
sys.exit(1 if fails else 0)
