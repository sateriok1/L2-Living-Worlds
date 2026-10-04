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

/** Stateless builder/spender budgets. Recomputed every tick; never changes the actor's charges. */
public final class PhantomChargePlanner
{
	public record Plan(int casts, double mpCost, double hpCost)
	{
	}

	private PhantomChargePlanner()
	{
	}

	/** A ready attack yields only to a bounded plan for an earlier authored spender. */
	public static boolean preferPlan(Plan plan, boolean readySpender, int plannedOrder, int immediateOrder, boolean survivesSetup)
	{
		return (plan != null) && survivesSetup && (!readySpender || ((plan.casts() == 1) && (plannedOrder < immediateOrder)));
	}

	/** Leave time for the payoff after builder damage and a few ordinary hits; this is not a DPS prediction. */
	public static boolean survivesSetup(double targetHp, double ordinaryHit, double builderHit)
	{
		return (targetHp > 0) && (targetHp > Math.max(0, builderHit) + 3 * Math.max(1, ordinaryHit));
	}

	public static Plan plan(int current, int required, int cap, double builderMp, double builderHp, double spenderMp, double spenderHp, double mp, double maxMp, double hp, double maxHp, int reserve)
	{
		final int casts = required - current;
		if ((casts <= 0) || (required > cap))
		{
			return null;
		}
		final double cost = casts * builderMp + spenderMp;
		final double health = casts * builderHp + spenderHp;
		if (!PhantomCombatPolicy.affordable(false, mp, maxMp, cost, reserve) || (health >= hp) || ((health > 0) && ((hp - health) < maxHp * 0.5)))
		{
			return null;
		}
		return new Plan(casts, cost, health);
	}
}
