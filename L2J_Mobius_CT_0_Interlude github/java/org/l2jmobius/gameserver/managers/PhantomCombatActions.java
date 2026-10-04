/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.managers;

import org.l2jmobius.gameserver.ai.Action;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.PhantomCombatPolicy.Availability;
import org.l2jmobius.gameserver.managers.PhantomCombatPolicy.Context;
import org.l2jmobius.gameserver.managers.PhantomPlaystyleEngine.CastAction;
import org.l2jmobius.gameserver.managers.PhantomPlaystyleEngine.PlayState;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.stats.Formulas;
import org.l2jmobius.gameserver.util.LocationUtil;

/** Native Mobius adapter shared by field, party and Olympiad combat. */
public final class PhantomCombatActions
{
	private PhantomCombatActions()
	{
	}

	public static PhantomCombatController.Outcome execute(Player npc, Creature focus, PlayState state, CastAction action, boolean physical)
	{
		if ((focus != null) && !focus.isAlikeDead() && npc.isAffectedBySkill(441))
		{
			PhantomClassRecovery.cancel(npc);
		}
		final Skill skill = (action == null) ? null : action.skill;
		final long duration = (skill == null) ? 0 : Math.max(skill.getHitTime() + skill.getCoolTime(), Formulas.calcAtkSpd(npc, skill, skill.getHitTime() + skill.getCoolTime()));
		final PhantomCombatController.Request request = (action == null) ? null : new PhantomCombatController.Request(skill.getId(), action.target.getObjectId(), skill.hasNegativeEffect(), action.repeatMs, duration, action.priority >= 1000);
		final PhantomCombatController.Outcome outcome = PhantomCombatController.execute(state.combat, adapter(npc, focus, action), request, physical, System.currentTimeMillis());
		if (outcome == PhantomCombatController.Outcome.STARTED)
		{
			PhantomPlaystyleEngine.confirmCast(state, action);
		}
		else if (outcome == PhantomCombatController.Outcome.REJECTED)
		{
			PhantomPlaystyleEngine.markRejected(state, action);
		}
		return outcome;
	}

	public static void observe(Player npc, PlayState state)
	{
		if (state != null)
		{
			PhantomCombatController.observe(state.combat, adapter(npc, null, null), System.currentTimeMillis());
		}
	}

	private static PhantomCombatController.Actor adapter(Player npc, Creature focus, CastAction action)
	{
		return new PhantomCombatController.Actor()
		{
			@Override
			public boolean available()
			{
				return !npc.isAlikeDead() && !npc.isSitting() && !npc.isStunned() && !npc.isSleeping() && !npc.isParalyzed() && !npc.isOutOfControl();
			}

			@Override
			public boolean casting()
			{
				return npc.isCastingNow() || npc.isCastingSimultaneouslyNow();
			}

			@Override
			public boolean ownsCast(int skillId)
			{
				return (npc.isCastingNow() && (npc.getLastSkillCast() != null) && (npc.getLastSkillCast().getId() == skillId))
					|| (npc.isCastingSimultaneouslyNow() && (npc.getLastSimultaneousSkillCast() != null) && (npc.getLastSimultaneousSkillCast().getId() == skillId));
			}

			@Override
			public boolean pendingTargetAlive(int targetId)
			{
				final WorldObject target = World.getInstance().findObject(targetId);
				return (target instanceof Creature creature) && !creature.isAlikeDead();
			}

			@Override
			public boolean inReach()
			{
				return (action.target == npc) || (PhantomPlaystyleEngine.inReach(npc, action.target, action.skill) && GeoEngine.getInstance().canSeeTarget(npc, action.target));
			}

			@Override
			public boolean canMove()
			{
				return !npc.isMovementDisabled();
			}

			@Override
			public void approach()
			{
				approachCaster(npc, action.target, Math.max(40, action.skill.getCastRange()), 0);
			}

			@Override
			public boolean cast()
			{
				npc.setTarget(action.target);
				final boolean accepted = PhantomCombatController.launchCast(new PhantomCombatController.CastPort()
				{
					@Override
					public boolean legal()
					{
						return !(action.target.isAlikeDead() && action.skill.hasNegativeEffect()) && npc.checkDoCastConditions(action.skill)
							&& action.skill.checkCondition(npc, action.target, false) && PhantomSkillFeasibility.satisfiedNow(npc, action.target, action.skill);
					}

					@Override
					public void stopMoving()
					{
						npc.getAI().clientStopMoving(null);
					}

					@Override
					public void launch()
					{
						npc.doCast(action.skill);
					}

					@Override
					public boolean accepted()
					{
						return casting() || npc.isSkillDisabled(action.skill) || (action.skill.isToggle() && npc.isAffectedBySkill(action.skill.getId()));
					}
				}, action.skill.getHitTime(), action.skill.isSimultaneousCast());
				if ((focus != null) && (action.target != focus))
				{
					npc.setTarget(focus); // beginCast has already captured its own target
				}
				return accepted;
			}

			@Override
			public void abortCast()
			{
				npc.abortCast();
			}

			@Override
			public void attack()
			{
				maintainAttack(npc, focus);
			}
		};
	}

	public static void maintainAttack(Player npc, Creature focus)
	{
		if ((focus == null) || focus.isAlikeDead() || npc.isAlikeDead() || npc.isCastingNow() || npc.isCastingSimultaneouslyNow() || npc.isDisabled())
		{
			return;
		}
		if (npc.isAttackingNow() && (npc.getTarget() == focus))
		{
			return;
		}
		npc.setTarget(focus);
		npc.setRunning();
		if ((npc.getAI().getIntention() == Intention.ATTACK) && (npc.getAI().getAttackTarget() == focus))
		{
			npc.getAI().notifyAction(Action.THINK);
		}
		else
		{
			npc.getAI().setIntention(Intention.ATTACK, focus);
		}
	}

	/** Learned range, including the no-playstyle case, is shared across solo and party positioning. */
	public static int casterReach(Player npc, PlayState state, String role, int cap)
	{
		final int authored = PhantomPlaystyleEngine.rotationReach(npc, state, role, 400);
		if (authored > 0)
		{
			return Math.min(authored, cap);
		}
		int ranged = Integer.MAX_VALUE;
		int shortRange = 0;
		for (Skill skill : npc.getAllSkills())
		{
			if (skill.isMagic() && PhantomManager.isAttackSpell(skill) && (skill.getCastRange() > 0))
			{
				if (skill.getCastRange() >= 400)
				{
					ranged = Math.min(ranged, skill.getCastRange());
				}
				else
				{
					shortRange = Math.max(shortRange, skill.getCastRange());
				}
			}
		}
		return (ranged != Integer.MAX_VALUE) ? Math.min(ranged, cap) : ((shortRange > 0) ? shortRange : cap);
	}

	public static Availability availability(Player npc, Creature target, Skill skill, int charges)
	{
		final boolean feasible = (skill != null) && (target != null) && !npc.isAlikeDead()
			&& !npc.isStunned() && !npc.isSleeping() && !npc.isParalyzed() && !npc.isOutOfControl()
			&& !npc.isSkillDisabled(skill) && (npc.getCurrentMp() >= mpCost(npc, skill))
			&& (npc.getCurrentHp() > skill.getHpConsume()) && (charges >= skill.getChargeConsumeCount())
			&& (skill.isStatic() || !(skill.isMagic() ? npc.isMuted() : npc.isPhysicalMuted()))
			&& !(skill.hasNegativeEffect() && target.isAlikeDead()) && (!skill.isPvPOnly() || target.isPlayer())
			&& PhantomBuffs.canAffordReagent(npc, skill) && PhantomSkillFeasibility.possible(npc, target, skill, charges);
		final boolean positioned = feasible && ((target == npc) || (PhantomPlaystyleEngine.inReach(npc, target, skill) && GeoEngine.getInstance().canSeeTarget(npc, target)));
		return PhantomCombatPolicy.availability(feasible, positioned, !npc.isMovementDisabled());
	}

	/** Stable party farming is different from solo combat and threats on vulnerable allies. */
	public static Context context(Player npc, Creature focus, boolean ownPressure)
	{
		boolean danger = ownPressure;
		if (npc.getParty() != null)
		{
			for (Player member : npc.getParty().getMembers())
			{
				if (!member.isAlikeDead() && (npc.calculateDistance2D(member) <= 1400) && (member.getCurrentHpPercent() < 50))
				{
					danger = true;
				}
			}
			for (Monster mob : World.getInstance().getVisibleObjectsInRange(npc, Monster.class, 1400))
			{
				if (!mob.isAlikeDead() && !mob.isSleeping() && !mob.isStunned() && mob.isInCombat()
					&& (mob.getTarget() instanceof Player victim) && npc.getParty().getMembers().contains(victim))
				{
					danger |= PhantomCombatPolicy.partyVictimDanger(fragile(victim), victim.getCurrentHpPercent(),
						!victim.isStunned() && !victim.isSleeping() && !victim.isParalyzed() && !victim.isOutOfControl());
				}
			}
		}
		return PhantomCombatPolicy.context(focus.isPlayer(), focus.isRaid(), npc.getParty() != null, danger);
	}

	private static boolean fragile(Player player)
	{
		return player.isMageClass() || ((player.getActiveWeaponItem() != null) && (player.getActiveWeaponItem().getItemType() == org.l2jmobius.gameserver.model.item.type.WeaponType.BOW));
	}

	/** Include initial MP and the actor's live cost modifiers, as the native cast gate does. */
	public static double mpCost(Player npc, Skill skill)
	{
		return npc.getStat().getMpConsume(skill) + npc.getStat().getMpInitialConsume(skill);
	}

	/** Returns true while a caster must approach. Root holds position while still permitting an in-range cast. */
	public static boolean approachCaster(Player npc, Creature target, int reach, int spread)
	{
		if (LocationUtil.checkIfInRange(reach, npc, target, false) && GeoEngine.getInstance().canSeeTarget(npc, target))
		{
			return false;
		}
		if (npc.isMovementDisabled() || npc.isCastingNow() || npc.isCastingSimultaneouslyNow())
		{
			return true;
		}
		final GeoEngine geo = GeoEngine.getInstance();
		final PhantomCasterPositioning.Point destination = PhantomCasterPositioning.destination(
			new PhantomCasterPositioning.Point(npc.getX(), npc.getY(), npc.getZ()),
			new PhantomCasterPositioning.Point(target.getX(), target.getY(), target.getZ()), reach, spread,
			npc.getCollisionRadius() + (target.isPlayer() ? target.asPlayer().getCollisionRadius() : target.getTemplate().getCollisionRadius()), new PhantomCasterPositioning.Geometry()
		{
			@Override
			public PhantomCasterPositioning.Point reachable(PhantomCasterPositioning.Point candidate)
			{
				final Location reachable = geo.getValidLocation(npc, new Location(candidate.x(), candidate.y(), candidate.z()));
				return new PhantomCasterPositioning.Point(reachable.getX(), reachable.getY(), reachable.getZ());
			}

			@Override
			public boolean visible(PhantomCasterPositioning.Point candidate)
			{
				return geo.canSeeTarget(candidate.x(), candidate.y(), candidate.z(), target.getX(), target.getY(), target.getZ(), npc.getInstanceId());
			}
		});
		if (destination == null)
		{
			return true;
		}
		npc.setRunning();
		if (!npc.isMoving() || (Math.hypot(npc.getXdestination() - destination.x(), npc.getYdestination() - destination.y()) > 50))
		{
			npc.getAI().setIntention(Intention.MOVE_TO, new Location(destination.x(), destination.y(), destination.z()));
		}
		return true;
	}

	public static boolean durable(Creature target)
	{
		return ((target instanceof Monster monster) && monster.isRaid()) || ((target.getMaxHp() >= 4000) && (target.getCurrentHpPercent() > 35));
	}

	/** Ordinary single-mob aggro alone is not a reason to spend a farmer's MP on control and bow skills. */
	public static boolean pressure(Player npc, boolean underAttack)
	{
		if (!underAttack)
		{
			return false;
		}
		if (PhantomCombatPolicy.pressured(true, npc.getCurrentHpPercent(), 1, fragile(npc)))
		{
			return true;
		}
		int attackers = 0;
		for (Monster mob : World.getInstance().getVisibleObjectsInRange(npc, Monster.class, 900))
		{
			if (!mob.isAlikeDead() && mob.isInCombat() && (mob.getTarget() == npc) && PhantomCombatPolicy.pressured(true, npc.getCurrentHpPercent(), ++attackers, fragile(npc)))
			{
				return true;
			}
		}
		return false;
	}
}
