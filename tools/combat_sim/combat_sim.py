"""Single-target PvE combat simulator for L2J Mobius Interlude, using the server's own formulas and timings.

Mechanics mirrored from the server source (Formulas.calcPhysDam / calcMagicDam / calcAtkSpd, Creature.useMagic):
  physical skill  dmg = 76 * (PAtk * shot + power) * prox / PDef        (shot = 2 with soulshots)
  physical auto   dmg = 76 * PAtk * shot * prox / PDef
  magic skill     dmg = 91 * sqrt(MAtk * shot) / MDef * power           (shot = 2 spirit, 4 blessed)
  crit            physical about x2, magic x3
  skill time      (hitTime + coolTime) / PAtkSpd * 300  (physical)  or  / MAtkSpd * 333  (magic), floor 500/550 ms,
                  x0.7 for magic with spirit shots
  skill reuse     reuseDelay * 333 / PAtkSpd (or MAtkSpd), unless static reuse
  auto interval   500000 / PAtkSpd ms
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
    str_bonus: float = 1.0      # STR bonus: physical SKILL crit chance = skill.baseCritRate * 10 * str_bonus / 1000


@dataclass
class Dummy:
    pdef: float = 400.0
    mdef: float = 300.0


def usable(skill, actor):
    if skill.damage_kind() is None or skill.power <= 0:
        return False
    if skill.weapons and actor.weapon not in skill.weapons:
        return False
    if skill.flags & {"charge", "rear"}:
        return False
    if skill.static_reuse is False and skill.hit <= 0 and skill.reuse <= 0:
        return False
    return True


def skill_dmg(skill, actor, dummy):
    """Expected damage of one cast (crit-weighted)."""
    if skill.damage_kind() == "magic":
        shot = 4 if actor.spiritshot == 2 else 2 if actor.spiritshot == 1 else 1
        base = 91 * math.sqrt(actor.matk * shot) / dummy.mdef * skill.power
        return base * (1 - actor.mcrit) + base * 3 * actor.mcrit
    shot = 2 if actor.soulshot else 1
    base = 76 * (actor.patk * shot + skill.power) * actor.prox / dummy.pdef
    # physical skills roll crit from the skill's own baseCritRate and STR (Formulas.calcCrit), not weapon crit or Focus
    crit = min(1.0, skill.base_crit * 10 * actor.str_bonus / 1000.0)
    return base * (1 - crit) + base * 2 * crit


def auto_dmg(actor, dummy):
    shot = 2 if actor.soulshot else 1
    base = 76 * actor.patk * shot * actor.prox / dummy.pdef
    return base * (1 - actor.crit) + base * 2 * actor.crit


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
    spd = actor.matk_spd if (skill.damage_kind() == "magic" or skill.magic) else actor.patk_spd
    return skill.reuse * 333.0 / spd


@dataclass
class Policy:
    order: tuple                # skill ids, highest priority first
    hold_ms: int = 0            # wait this long for a higher-priority skill instead of filling with a lower one


def simulate(actor, dummy, skills, policy, duration_ms, start_mp=None, timeline=None):
    """Returns (total_damage, mp_used, casts dict). `skills` maps id -> SkillDef.
    If `timeline` is a list, (time_ms, cumulative_damage) is appended after every action so one run answers many windows."""
    t = 0.0
    mp = actor.mp_max if start_mp is None else start_mp
    ready_at = {sid: 0.0 for sid in policy.order}
    total = 0.0
    mp_used = 0.0
    casts = {sid: 0 for sid in policy.order}
    casts["auto"] = 0
    a_dmg = auto_dmg(actor, dummy)
    a_int = 500000.0 / actor.patk_spd
    regen_per_ms = actor.mp_regen_3s / 3000.0
    sd = {sid: (skill_dmg(skills[sid], actor, dummy), cast_ms(skills[sid], actor), reuse_ms(skills[sid], actor)) for sid in policy.order}

    def advance(dt):
        nonlocal t, mp
        t += dt
        mp = min(actor.mp_max, mp + regen_per_ms * dt)

    while t < duration_ms:
        chosen = None
        soonest = None
        for sid in policy.order:
            if mp - skills[sid].mp < actor.mp_reserve:
                continue
            if ready_at[sid] <= t:
                chosen = sid
                break
            wait = ready_at[sid] - t
            if soonest is None or wait < soonest[1]:
                soonest = (sid, wait)
        if chosen is not None and soonest is not None:
            # a higher priority skill is not ready but a lower one is: does holding for it pay?
            pass
        if chosen is not None:
            # something higher priority is about to be ready: wait or fill
            hold = None
            for sid in policy.order:
                if sid == chosen:
                    break
                if mp - skills[sid].mp >= actor.mp_reserve and 0 < ready_at[sid] - t <= policy.hold_ms:
                    hold = ready_at[sid] - t
                    break
            if hold is not None:
                if a_int <= hold + 1:
                    advance(a_int); total += a_dmg; casts["auto"] += 1
                    if timeline is not None: timeline.append((t, total, mp_used))
                else:
                    advance(hold)
                continue
            dmg, cms, rms = sd[chosen]
            mp -= skills[chosen].mp
            mp_used += skills[chosen].mp
            ready_at[chosen] = t + rms
            casts[chosen] += 1
            advance(cms)
            total += dmg
            if timeline is not None: timeline.append((t, total, mp_used))
            continue
        # nothing castable: auto attack
        advance(a_int)
        total += a_dmg
        casts["auto"] += 1
        if timeline is not None: timeline.append((t, total, mp_used))
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
