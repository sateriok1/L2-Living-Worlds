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

import java.util.List;

import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.conditions.Condition;
import org.l2jmobius.gameserver.model.conditions.ConditionLogicAnd;
import org.l2jmobius.gameserver.model.conditions.ConditionLogicNot;
import org.l2jmobius.gameserver.model.conditions.ConditionLogicOr;
import org.l2jmobius.gameserver.model.conditions.ConditionMinDistance;
import org.l2jmobius.gameserver.model.conditions.ConditionPlayerCharges;
import org.l2jmobius.gameserver.model.skill.Skill;

/** Reads native conditions without messages, actor mutation or duplicating weapon/charge requirements. */
public final class PhantomSkillFeasibility
{
	private PhantomSkillFeasibility()
	{
	}

	public static boolean possible(Player actor, Creature target, Skill skill, int charges)
	{
		return possible(skill.getCastConditions(), actor, target, skill, charges);
	}

	/** Strict launch check: actual actor charges and geometry, without the stock fake-player bypass. */
	public static boolean satisfiedNow(Player actor, Creature target, Skill skill)
	{
		return (actor.getCharges() >= skill.getChargeConsumeCount())
			&& satisfiedNow(skill.getCastConditions(), actor, target, skill, actor.getCharges());
	}

	static boolean satisfiedNow(List<Condition> conditions, Player actor, Creature target, Skill skill, int actualCharges)
	{
		for (Condition condition : conditions)
		{
			if (!Boolean.TRUE.equals(test(condition, actor, target, skill, actualCharges, false)))
			{
				return false;
			}
		}
		return true;
	}

	static boolean possible(List<Condition> conditions, Player actor, Creature target, Skill skill, int charges)
	{
		for (Condition condition : conditions)
		{
			if (Boolean.FALSE.equals(test(condition, actor, target, skill, charges, true)))
			{
				return false;
			}
		}
		return true;
	}

	// null means a positional condition still needs execution. NOT must retain unknown rather than invert it.
	private static Boolean test(Condition condition, Player actor, Creature target, Skill skill, int charges, boolean planning)
	{
		if (condition instanceof ConditionPlayerCharges charge)
		{
			return charges >= charge.getRequiredCharges();
		}
		if (planning && (condition instanceof ConditionMinDistance))
		{
			return null;
		}
		if (condition instanceof ConditionLogicNot not)
		{
			final Boolean result = test(not.getCondition(), actor, target, skill, charges, planning);
			return (result == null) ? null : !result;
		}
		if ((condition instanceof ConditionLogicAnd) || (condition instanceof ConditionLogicOr))
		{
			final boolean and = condition instanceof ConditionLogicAnd;
			final Condition[] children = and ? ((ConditionLogicAnd) condition).conditions : ((ConditionLogicOr) condition).conditions;
			boolean unknown = false;
			for (Condition child : children)
			{
				final Boolean result = test(child, actor, target, skill, charges, planning);
				if (result == null)
				{
					unknown = true;
				}
				else if (result != and)
				{
					return !and;
				}
			}
			return unknown ? null : and;
		}
		return condition.testImpl(actor, target, skill, null);
	}

	/** Native charge gate inside NOT is the builder's maximum; ranged builders use their authored cap. */
	public static int builderCap(Skill skill, int authoredCap)
	{
		return builderCap(skill.getCastConditions(), authoredCap);
	}

	static int builderCap(List<Condition> conditions, int authoredCap)
	{
		for (Condition condition : conditions)
		{
			if ((condition instanceof ConditionLogicNot not) && (not.getCondition() instanceof ConditionPlayerCharges charge))
			{
				return Math.min(authoredCap, charge.getRequiredCharges());
			}
		}
		return authoredCap;
	}
}
