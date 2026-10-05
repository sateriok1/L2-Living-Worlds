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

import java.util.Locale;
import java.util.function.IntPredicate;

/**
 * Pure rules for the summoner lineages (Warlock/Arcana Lord, Elemental Summoner/Elemental Master, Phantom
 * Summoner/Spectral Master): which servitor to call, what keeps it alive, and how a leader asks for Summon Friend.
 * No game classes, so it tests on its own.
 */
public final class PhantomServitorRules
{
	/**
	 * Servitor summon skills, strongest tier first, across the three lineages (a member knows only its own lineage's,
	 * so one list serves all). Corpse-based Necromancer summons are left out: they need a corpse target.
	 */
	public static final int[] SUMMON_SKILLS =
	{
		1406, 1407, 1408, // Feline King / Magnus the Unicorn / Spectral Lord (3rd class)
		1331, 1332, 1333, // Feline Queen / Unicorn Seraphim / Nightshade
		1276, 1277, 1278, // Kai the Cat / Merrow the Unicorn / Soulless
		1225, 1227, 1228, // Mew the Cat / Mirage the Unicorn / Silhouette
		1111, 1226, 1128 // Kat the Cat / Boxer the Unicorn / Shadow
	};

	public static final int SERVITOR_RECHARGE = 1126;
	public static final int SERVITOR_HEAL = 1127;
	public static final int SERVITOR_MAGIC_SHIELD = 1139;
	public static final int SERVITOR_PHYSICAL_SHIELD = 1140;
	public static final int SERVITOR_HASTE = 1141;
	public static final int SERVITOR_CURE = 1300;
	/** Cast on the servitor when it has none of these: lasting buffs that make it a sturdier fighter. */
	public static final int[] SERVITOR_BUFFS =
	{
		SERVITOR_PHYSICAL_SHIELD,
		SERVITOR_MAGIC_SHIELD,
		SERVITOR_HASTE
	};
	public static final int SUMMON_FRIEND = 1403;
	public static final int SUMMONING_CRYSTAL = 8615;
	/** Servitor summons consume crystals of the grade their level needs: D, C, B, A, S. */
	public static final int[] CRYSTAL_ITEMS =
	{
		1458,
		1459,
		1460,
		1461,
		1462
	};

	public static final int HEAL_BELOW_PERCENT = 60;
	public static final int RECHARGE_BELOW_PERCENT = 35;
	/** A servitor skill is tried at most this often while fighting, so attacks are not buried under skills. */
	public static final long SERVITOR_SKILL_GAP_MS = 4000;

	private PhantomServitorRules()
	{
	}

	public static boolean isSummonSkill(int skillId)
	{
		for (int id : SUMMON_SKILLS)
		{
			if (id == skillId)
			{
				return true;
			}
		}
		return false;
	}

	/** @return the strongest summon skill {@code usable} accepts, or 0 when the member has none ready. */
	public static int pickSummon(IntPredicate usable)
	{
		for (int id : SUMMON_SKILLS)
		{
			if (usable.test(id))
			{
				return id;
			}
		}
		return 0;
	}

	public static boolean servitorNeedsHeal(int hpPercent)
	{
		return hpPercent < HEAL_BELOW_PERCENT;
	}

	public static boolean servitorNeedsRecharge(int mpPercent)
	{
		return mpPercent < RECHARGE_BELOW_PERCENT;
	}

	/**
	 * Whether a leader's message asks a Summon Friend caster to bring someone over: "summon me", "summon us",
	 * "can you summon me pls", "summon <name>" (the name is for the caller to find). Cubic and servitor wording
	 * ("summon storm cubic", "summon your cat") is not one.
	 */
	public static boolean isSummonRequest(String text)
	{
		final String t = text.toLowerCase(Locale.ROOT);
		if (t.contains("cubic") || t.contains("servitor") || t.contains("summon your") || t.contains("resummon"))
		{
			return false;
		}
		return t.matches(".*\\b(summon|port|tp)\\s+(me|us|him|her|them|pls|plz|please)\\b.*") //
			|| t.matches(".*\\b(summon|port)\\s+\\w+\\s+(to you|over|here)\\b.*") //
			|| t.matches(".*\\bsummon\\s+friend\\b.*") //
			|| t.matches(".*\\bsummon\\s+\\w+\\s*$");
	}

	/** @return how many seconds remain of a reuse in milliseconds, rounded up (never negative). */
	public static int secondsLeft(long millis)
	{
		return (int) Math.max(0, (millis + 999) / 1000);
	}
}
