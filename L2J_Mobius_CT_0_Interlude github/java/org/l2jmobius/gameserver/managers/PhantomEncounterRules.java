/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.managers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure rules for the PvP danger encounters (a phantom, or a group, that comes for the player and fights once).
 * No world access, so every decision here unit tests without a server.
 */
public final class PhantomEncounterRules
{
	/** Encounter tiers, easiest first. */
	public enum Tier
	{
		WIMP, // an undergeared phantom walks up, asks "are you a bot?", then attacks
		NORMIE, // an equally geared phantom attacks while the player stands still or fights a mob
		PKER, // an overgeared red-name roamer hunts the player down (stage 2)
		HORSEMEN // an overgeared 4-man red-name party (stage 2)
	}

	// Which actors are currently authorised to attack which player (actor objectId -> victim objectId). Read by
	// Player.isAutoAttackable so an encounter actor's skills and attacks are legal against an unflagged victim.
	private static final Map<Integer, Integer> HOSTILE = new ConcurrentHashMap<>();

	private PhantomEncounterRules()
	{
	}

	public static void markHostile(int actorObjectId, int victimObjectId)
	{
		HOSTILE.put(actorObjectId, victimObjectId);
	}

	public static void clearHostile(int actorObjectId)
	{
		HOSTILE.remove(actorObjectId);
	}

	/** @return {@code true} when this actor is an encounter actor currently hunting exactly this victim. */
	public static boolean isHostile(int actorObjectId, int victimObjectId)
	{
		final Integer victim = HOSTILE.get(actorObjectId);
		return (victim != null) && (victim == victimObjectId);
	}

	/**
	 * Picks a tier by weight. Tiers locked by level or party size have weight 0 after {@link #weightFor}.
	 * @param weights the four weights in {@link Tier} order (negative counts as 0)
	 * @param roll a uniform roll in [0, total)
	 * @return the tier, or {@code null} when nothing is available
	 */
	public static Tier pickTier(int[] weights, int roll)
	{
		int total = 0;
		for (int w : weights)
		{
			total += Math.max(0, w);
		}
		if ((total <= 0) || (roll < 0))
		{
			return null;
		}
		int r = roll % total;
		for (int i = 0; i < weights.length; i++)
		{
			final int w = Math.max(0, weights[i]);
			if (r < w)
			{
				return Tier.values()[i];
			}
			r -= w;
		}
		return null;
	}

	/** @return the effective weight of a tier for this player: 0 when the player's level or party size locks it out. */
	public static int weightFor(Tier tier, int configuredWeight, int playerLevel, int partySize, int minLevel, int maxPartyForSolo)
	{
		if ((configuredWeight <= 0) || (playerLevel < minLevel))
		{
			return 0;
		}
		// A big party is never ambushed by a lone phantom of the harder kind (see the party-size rule).
		if ((tier == Tier.PKER) && (partySize > maxPartyForSolo))
		{
			return 0;
		}
		return configuredWeight;
	}

	/**
	 * The actor's level. Wimp: 3-8 under the player. Normie: within 2 either way. Both clamp to [1, 80].
	 * @param roll any non-negative number (the caller's random roll)
	 */
	public static int actorLevel(Tier tier, int playerLevel, int roll)
	{
		final int r = Math.abs(roll);
		final int level;
		switch (tier)
		{
			case WIMP:
			{
				level = playerLevel - (3 + (r % 6));
				break;
			}
			case NORMIE:
			{
				level = playerLevel + ((r % 5) - 2);
				break;
			}
			case PKER:
			{
				level = playerLevel + (4 + (r % 5));
				break;
			}
			default:
			{
				level = playerLevel + (5 + (r % 6));
				break;
			}
		}
		return Math.max(1, Math.min(80, level));
	}

	/** @return how many armor/weapon grades above (+) or below (-) its level's normal grade the actor is geared. */
	public static int gradeShift(Tier tier)
	{
		switch (tier)
		{
			case WIMP:
			{
				return -1;
			}
			case NORMIE:
			{
				return 0;
			}
			default:
			{
				return 1;
			}
		}
	}

	/**
	 * The enchant level the actor's weapon and armor carry. Wimp +0, Normie +0..+3 (an ordinary player), PKer +14..+20,
	 * Horsemen +16..+20 (real over-enchanted gear, not a stat bonus).
	 * @param roll any non-negative number (the caller's random roll)
	 */
	public static int enchantFor(Tier tier, int roll)
	{
		final int r = Math.abs(roll);
		switch (tier)
		{
			case WIMP:
			{
				return 0;
			}
			case NORMIE:
			{
				return r % 4;
			}
			case PKER:
			{
				return 14 + (r % 7);
			}
			default:
			{
				return 16 + (r % 5);
			}
		}
	}

	/** @return {@code base + shift} clamped to the valid grade ordinals [0, maxOrdinal]. */
	public static int shiftedGrade(int base, int shift, int maxOrdinal)
	{
		return Math.max(0, Math.min(maxOrdinal, base + shift));
	}

	/** @return {@code true} once the Normie should strike: the player is mid-fight with a monster, or has stood still long enough. */
	public static boolean normieStrikeReady(boolean victimBusyWithMonster, long victimStillMs, long stillNeededMs)
	{
		return victimBusyWithMonster || (victimStillMs >= stillNeededMs);
	}

	/** @return {@code true} once the Wimp's warning period has passed (or it was hit first). */
	public static boolean wimpMayStrike(long now, long warnedAt, long warnMs, boolean hitFirst)
	{
		return hitFirst || ((warnedAt > 0) && ((now - warnedAt) >= warnMs));
	}

	/** @return a delay in [minMs, maxMs] for a roll in [0, 1000). A reversed range collapses to minMs. */
	public static long delayMs(long minMs, long maxMs, int roll)
	{
		if (maxMs <= minMs)
		{
			return Math.max(0, minMs);
		}
		return minMs + (((maxMs - minMs) * Math.max(0, Math.min(999, roll))) / 1000);
	}

	/**
	 * Encounter size for a party: how many actors to send for a group tier. One actor per two party members, never fewer
	 * than {@code base}, capped.
	 */
	public static int actorCount(int base, int partySize, int cap)
	{
		return Math.max(1, Math.min(cap, Math.max(base, (partySize + 1) / 2)));
	}

	/** @return {@code true} if this encounter outcome ends it for good (a dead actor, a dead victim, or an expired clock). */
	public static boolean encounterOver(boolean actorDead, boolean victimDead, boolean victimGone, long now, long deadline)
	{
		return actorDead || victimDead || victimGone || (now >= deadline);
	}
}
