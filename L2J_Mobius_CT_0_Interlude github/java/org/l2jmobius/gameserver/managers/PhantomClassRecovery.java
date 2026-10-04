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

import org.l2jmobius.gameserver.managers.PhantomCombatPolicy.Availability;
import org.l2jmobius.gameserver.managers.PhantomPlaystyleEngine.CastAction;
import org.l2jmobius.gameserver.managers.PhantomPlaystyleEngine.PlayState;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;

/** Class sustain belongs to safe downtime, never to generic buff maintenance. */
public final class PhantomClassRecovery
{
	static final int[] RECOVERY_SKILLS = {441, 417};

	private PhantomClassRecovery()
	{
	}

	public static void cancel(Player npc)
	{
		if (npc.isAffectedBySkill(441))
		{
			npc.stopSkillEffects(SkillFinishType.REMOVED, 441);
		}
	}

	public static boolean tick(Player npc, PlayState state, boolean safe)
	{
		if (npc.isAlikeDead() || (state == null))
		{
			return false;
		}
		if (npc.isAffectedBySkill(441))
		{
			if (!safe || (npc.getCurrentMpPercent() >= 100))
			{
				cancel(npc);
				return false;
			}
			return true;
		}
		if (safe && npc.isCastingNow() && (npc.getLastSkillCast() != null))
		{
			final int id = npc.getLastSkillCast().getId();
			if ((id == 441) || (id == 417) || ((id == 50) && (npc.getCurrentMpPercent() < PhantomPartyDowntime.MP_SIT)))
			{
				return true;
			}
		}
		if (!safe || (npc.getCurrentMpPercent() >= PhantomPartyDowntime.MP_SIT) || npc.isSitting()
			|| npc.isCastingNow() || npc.isCastingSimultaneouslyNow() || npc.isParalyzed() || npc.isStunned() || npc.isSleeping())
		{
			return false;
		}
		Skill skill = npc.getKnownSkill(441);
		if ((skill != null) && !npc.isSkillDisabled(skill) && (npc.getCurrentHpPercent() >= 70))
		{
			if (npc.getCharges() < skill.getChargeConsumeCount())
			{
				final Skill builder = npc.getKnownSkill(50);
				if ((builder != null) && (PhantomChargePlanner.plan(npc.getCharges(), skill.getChargeConsumeCount(), PhantomSkillFeasibility.builderCap(builder, 7),
					PhantomCombatActions.mpCost(npc, builder), builder.getHpConsume(), PhantomCombatActions.mpCost(npc, skill), skill.getHpConsume(),
					npc.getCurrentMp(), npc.getMaxMp(), npc.getCurrentHp(), npc.getMaxHp(), 0) != null))
				{
					skill = builder;
				}
			}
		}
		else
		{
			skill = npc.getKnownSkill(417);
			if ((skill == null) || ((npc.getCurrentHp() - skill.getHpConsume()) < npc.getMaxHp() * 0.75))
			{
				return false;
			}
		}
		if ((skill == null) || (PhantomCombatActions.availability(npc, npc, skill, npc.getCharges()) != Availability.READY_NOW))
		{
			return false;
		}
		final CastAction action = new CastAction(skill, npc, false, 0, skill.getId(), 1000, 200, 0);
		final PhantomCombatController.Outcome outcome = PhantomCombatActions.execute(npc, null, state, action, false);
		return (outcome == PhantomCombatController.Outcome.STARTED) || (outcome == PhantomCombatController.Outcome.BUSY);
	}
}
