"""Single-target PvE combat simulator for L2J Mobius Interlude, using the server's own formulas and timings.

Mechanics mirrored from the server source (Formulas.calcPhysDam / calcMagicDam / calcAtkSpd, Creature.useMagic):
  physical skill  dmg = 76 * (PAtk * shot + power) * prox / PDef        (shot = 2 with soulshots)
  physical auto   dmg = 76 * PAtk * shot * prox / PDef
  magic skill     dmg = 91 * sqrt(MAtk * shot) / MDef * power           (shot = 2 spirit, 4 blessed)
  crit            physical about x2, magic x3
  skill time      (hitTime + coolTime) / PAtkSpd * 300  (physical)  or  / MAtkSpd * 333  (magic), floor 500/550 ms,
                  x0.7 for magic with spirit shots
  skill reuse     reuseDelay * 333 / PAtkSpd (or MAtkSpd), unless static reuse
  auto interval   (500000 + weapon reuse_delay*333) / PAtkSpd ms   (reuse_delay is 0 except bows)
"""
import itertools, math
from dataclasses import dataclass, field


@dataclass
class Actor:
    patk: float
    patk_spd: float
    matk: float
    matk_spd: float
    mp_max: float
    mp_regen_3s: float          # MP per 3 second regen tick
    weapon: str = "SWORD"       # weapon type kind used by skill conditions
    crit: float = 0.08          # physical crit chance
    mcrit: float = 0.05
    soulshot: bool = True
    spiritshot: int = 0         # 0 none, 1 spirit, 2 blessed
    prox: float = 1.0           # 1.0 front, 1.1 side, 1.2 behind
    mp_reserve: float = 0.0     # MP the policy refuses to spend below
    hp_max: float = 0.0         # 0 = HP costs are ignored
    hp_regen_3s: float = 0.0
    hp_reserve: float = 0.5     # a skill that costs HP is refused if it would drop HP below this fraction of max
    crit_mul: float = 1.0       # critDmg multiplier (Death Whisper, Dance of Fire): crit damage = 2 * crit_mul
    crit_add: float = 0.0       # critDmgAdd: flat crit damage = crit_add * 77 / defence
    reuse_mul: float = 1.0      # pReuse multiplier (Song of Champion): scales physical skill reuse
    mp_mul: float = 1.0         # physicalMpConsumeRate multiplier
    mp_drain_per_s: float = 0.0 # toggle upkeep (e.g. Vicious Stance)
    weapon_reuse: float = 0.0   # weapon reuse_delay (bows: 1500): auto cycle = (500000 + reuse_delay*333) / PAtkSpd  (Creature.calculateReuseTime, doAttackHitByBow)
    position: str = "front"     # where the attacker stands: front (target faces it), side, behind. Sets prox, the blow success multiplier and Backstab availability
    dex_bonus: float = 1.0      # DEX bonus: blow success = blowChance * dex_bonus * side * blow_mul
    blow_mul: float = 1.0       # blowRate multiplier from passives, weapon abilities and buffs (Assassination, Mighty Mortal, Mortal Strike)
    crit_pos: float = 1.0       # critDmgPos multiplier at this position (Focus Death / Focus Power); autos and skills crit for 2 * crit_mul * crit_pos
    mcrit_mul: float = 1.0      # mCritPower multiplier on magic crit damage
    mreuse_mul: float = 1.0     # mReuse multiplier: scales magic skill reuse
    mmp_mul: float = 1.0        # magicalMpConsumeRate multiplier
    str_bonus: float = 1.0      # STR bonus: physical SKILL crit chance = skill.baseCritRate * 10 * str_bonus / 1000


@dataclass
class Dummy:
    pdef: float = 400.0
    mdef: float = 300.0


def usable(skill, actor):
    if skill.dot is None and (skill.damage_kind() is None or skill.power <= 0):
        return False
    if skill.weapons and actor.weapon not in skill.weapons:
        return False
    if skill.flags & {"charge", "rear", "race"}:
        return False
    if "backstab" in skill.flags and actor.position == "front":
        return False                  # Backstab.calcSuccess: never lands from in front
    if skill.static_reuse is False and skill.hit <= 0 and skill.reuse <= 0:
        return False
    return True


BLOW_SIDE = {"front": 1.0, "side": 1.5, "behind": 2.0}     # Formulas.calcBlowSuccess position multiplier


def blow_chance(skill, actor, boost=1.0):
    rate = skill.blow_chance * actor.dex_bonus * BLOW_SIDE[actor.position] * actor.blow_mul * boost
    return min(1.0, math.ceil(rate) / 100.0)          # Rnd.get(100) < rate


def blow_dmg(skill, actor, dummy, boost=1.0):
    """Expected damage of a blow / Backstab cast (Formulas.calcBlowDamage / calcBackstabDamage, blow success and crit included).
    Differences from other skills: soulshots give x1.458, critDmg and critDmgPos (halved) always apply, critDmgAdd is flat * 6.1, and a crit doubles the whole hit."""
    ss = 1.458 if actor.soulshot else 1.0
    if "backstab" in skill.flags:
        base = 77 * (skill.power + actor.patk) / dummy.pdef * ss
    else:
        base = 77 * (skill.power + actor.patk * ss) / dummy.pdef
    dmg = base * actor.prox * actor.crit_mul * ((actor.crit_pos - 1) / 2 + 1) + actor.crit_add * 6.1 * 77 / dummy.pdef
    crit = min(1.0, skill.base_crit * 10 * actor.str_bonus / 1000.0)
    return dmg * (1 + crit) * blow_chance(skill, actor, boost)


def skill_dmg(skill, actor, dummy, boost=1.0):
    """Expected damage of one cast (crit-weighted)."""
    if skill.dot is not None and skill.damage_kind() is None:
        return 0.0                 # a pure DoT deals its damage over time (see simulate); Sting-type skills also hit directly, below
    if "blow" in skill.flags:
        return blow_dmg(skill, actor, dummy, boost)
    if skill.damage_kind() == "magic":
        shot = 4 if actor.spiritshot == 2 else 2 if actor.spiritshot == 1 else 1
        base = 91 * math.sqrt(actor.matk * shot) / dummy.mdef * skill.power
        return base * (1 - actor.mcrit) + base * 3 * actor.mcrit_mul * actor.mcrit
    shot = 2 if actor.soulshot else 1
    base = 76 * (actor.patk * shot + skill.power) * actor.prox / dummy.pdef
    # physical skills roll crit from the skill's own baseCritRate and STR (Formulas.calcCrit), not weapon crit or Focus
    crit = min(1.0, skill.base_crit * 10 * actor.str_bonus / 1000.0)
    return base * (1 - crit) + (base * 2 * actor.crit_mul * actor.crit_pos + actor.crit_add * 77 / dummy.pdef) * crit


def auto_dmg(actor, dummy):
    shot = 2 if actor.soulshot else 1
    base = 76 * actor.patk * shot * actor.prox / dummy.pdef
    return base * (1 - actor.crit) + (base * 2 * actor.crit_mul * actor.crit_pos + actor.crit_add * 77 / dummy.pdef) * actor.crit


def cast_ms(skill, actor):
    total = skill.hit + skill.cool
    if skill.damage_kind() == "magic" or skill.magic:
        t = total / actor.matk_spd * 333
        if actor.spiritshot:
            t *= 0.7
        floor = 550 if total > 550 else 0
    else:
        t = total / actor.patk_spd * 300
        floor = 500 if total >= 500 else 0
    return max(t, floor)


def reuse_ms(skill, actor):
    if skill.static_reuse:
        return skill.reuse
    magic = skill.damage_kind() == "magic" or skill.magic
    spd = actor.matk_spd if magic else actor.patk_spd
    return skill.reuse * (actor.mreuse_mul if magic else actor.reuse_mul) * 333.0 / spd


@dataclass
class Policy:
    order: tuple                # skill ids, highest priority first
    hold_ms: int = 0            # wait this long for a higher-priority skill instead of filling with a lower one


def simulate(actor, dummy, skills, policy, duration_ms, start_mp=None, timeline=None, later=None):
    """Returns (total_damage, mp_used, casts dict). `skills` maps id -> SkillDef.
    Tracks MP and HP (skills can cost both), and the expected effect of defence-lowering debuffs (e.g. Armor Crush's stun,
    pDef x0.7 for its duration, landing with its activate rate): while active, hits are scaled by 1 + p * (1/mult - 1).
    If `timeline` is a list, (time_ms, cumulative_damage, mp_used) is appended after every action so one run answers many windows."""
    t = 0.0
    mp = actor.mp_max if start_mp is None else start_mp
    hp = actor.hp_max
    ready_at = {sid: 0.0 for sid in policy.order}
    total = 0.0
    mp_used = hp_used = 0.0
    casts = {sid: 0 for sid in policy.order}
    casts["auto"] = 0
    hp_per_ms = actor.hp_regen_3s / 3000.0

    def derive():
        return (auto_dmg(actor, dummy), (500000.0 + actor.weapon_reuse * 333.0) / actor.patk_spd, (actor.mp_regen_3s / 3000.0) - actor.mp_drain_per_s / 1000.0,
                {sid: (skill_dmg(skills[sid], actor, dummy), cast_ms(skills[sid], actor), reuse_ms(skills[sid], actor)) for sid in policy.order})

    a_dmg, a_int, mp_per_ms, sd = derive()
    switched = later is None
    deb = [0.0, 0.0, 1.0, 1.0]       # until, probability active, pDef mult, mDef mult
    dots = {}                        # abnormal type -> [end ms, expected damage per ms]; one slot per type, like the server's abnormal-type stacking
    boost = [0.0, 1.0]               # until, blowRate multiplier a blow gave itself (Critical Blow)

    def advance(dt):
        nonlocal t, mp, hp, total
        for end, rate in dots.values():
            if end > t:
                total += rate * (min(t + dt, end) - t)
        t += dt
        mp = max(0.0, min(actor.mp_max, mp + mp_per_ms * dt))
        if actor.hp_max:
            hp = min(actor.hp_max, hp + hp_per_ms * dt)

    def mpm(s):
        return actor.mmp_mul if (s.damage_kind() == "magic" or s.magic) else actor.mp_mul

    def can(sid):
        s = skills[sid]
        if s.dot is not None:
            cur = dots.get(s.dot[4])
            if cur is not None and cur[0] > t + 1 and cur[1] >= s.dot[0] * s.dot[3] / 1000.0 - 1e-12:
                return False            # an equal or stronger DoT of this type is still running: recasting would waste the cast
        if mp - s.mp * mpm(s) < actor.mp_reserve:
            return False
        return not (actor.hp_max and s.hp_cost and (hp - s.hp_cost) < actor.hp_reserve * actor.hp_max)

    def uplift(kind):
        if t >= deb[0]:
            return 1.0
        m = deb[2] if kind == "phys" else deb[3]
        return 1.0 + deb[1] * (1.0 / m - 1.0)

    def record():
        if timeline is not None:
            timeline.append((t, total, mp_used))

    while t < duration_ms:
        if not switched and t >= later[0]:          # a temporary buff (e.g. Rage) has ended: continue with the after-stats
            switched = True
            actor = later[1]
            a_dmg, a_int, mp_per_ms, sd = derive()
        chosen = None
        for sid in policy.order:
            if can(sid) and ready_at[sid] <= t:
                chosen = sid
                break
        if chosen is not None:
            hold = None
            for sid in policy.order:
                if sid == chosen:
                    break
                if can(sid) and 0 < ready_at[sid] - t <= policy.hold_ms:
                    hold = ready_at[sid] - t
                    break
            if hold is not None:
                if a_int <= hold + 1:
                    advance(a_int); total += a_dmg * uplift("phys"); casts["auto"] += 1
                    record()
                else:
                    advance(hold)
                continue
            dmg, cms, rms = sd[chosen]
            sk = skills[chosen]
            if "blow" in sk.flags and t < boost[0]:
                dmg = skill_dmg(sk, actor, dummy, boost[1])
            mp -= sk.mp * mpm(sk)
            mp_used += sk.mp * mpm(sk)
            if actor.hp_max and sk.hp_cost:
                hp -= sk.hp_cost
                hp_used += sk.hp_cost
            ready_at[chosen] = t + rms
            casts[chosen] += 1
            advance(cms)
            total += dmg * uplift(sk.damage_kind() or "phys")     # the debuff lands after this hit, so it does not boost it
            if sk.dot is not None:
                pw, iv, dur, chance, typ = sk.dot
                dots[typ] = [t + dur, pw * chance / 1000.0]          # expected value: lands with its activate rate; power is damage per second
            if sk.self_blow:
                boost[:] = [t + sk.self_blow[1], sk.self_blow[0]]
            if sk.debuff:
                pm, mm, dur, chance = sk.debuff
                p_prev = deb[1] if t < deb[0] else 0.0
                deb[:] = [t + dur, 1 - (1 - p_prev) * (1 - chance), pm, mm]
            record()
            continue
        advance(a_int)
        total += a_dmg * uplift("phys")
        casts["auto"] += 1
        record()
    casts["hp_used"] = hp_used
    return total, mp_used, casts


def search(actor, dummy, skills, ids, duration_ms, max_len=6, holds=(0, 400, 1000)):
    """Exhaustive search over ordered subsets of `ids` and hold windows. Returns best (dps, policy, casts)."""
    best = None
    ids = list(ids)
    for k in range(0, min(max_len, len(ids)) + 1):
        for perm in itertools.permutations(ids, k):
            for h in (holds if k > 1 else (0,)):
                pol = Policy(perm, h)
                dmg, used, casts = simulate(actor, dummy, skills, pol, duration_ms)
                dps = dmg / (duration_ms / 1000.0)
                if best is None or dps > best[0] + 1e-9:
                    best = (dps, pol, casts, used)
    return best
