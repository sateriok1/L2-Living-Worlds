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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure rules for the PvP danger encounters (a phantom, or a group, that comes for the player and fights once).
 * No world access, so every decision here unit tests without a server.
 */
public final class PhantomEncounterRules
{
	/** Encounter tiers, easiest first. The order is the order of every per-tier config array. */
	public enum Tier
	{
		WIMP, // a few levels under you, undergeared by level: walks up, asks "are you a bot?", then attacks
		NORMIE, // your level: attacks while you stand still or fight a monster
		HARD, // a few levels over you, +3-4 gear: waits for its moment like a Normie
		HORSEMEN, // a group (as many as your party, at least four), well over you, +7-10 gear: attacks on arrival
		PKER // one lone phantom far over you in level and gear: the extinction event, attacks on arrival
	}

	/** The members of one encounter share this, so a group speaks once, warns once, and strikes together. */
	public static final class EncounterGroup
	{
		private final AtomicBoolean _speech = new AtomicBoolean();
		private final AtomicBoolean _loot = new AtomicBoolean();
		private final AtomicInteger _dead = new AtomicInteger();
		private volatile int _size;
		private final AtomicBoolean _winLine = new AtomicBoolean();
		private final AtomicLong _warnedAt = new AtomicLong();
		private volatile boolean _fighting;

		public EncounterGroup(int size)
		{
			_size = Math.max(1, size);
		}

		/** The group turned out smaller than planned (some phantoms could not spawn). */
		public void shrinkTo(int size)
		{
			_size = Math.max(1, size);
		}

		/** @return {@code true} when this death is the one that wipes the group (every member is now dead). */
		public boolean memberDied()
		{
			return _dead.incrementAndGet() >= _size;
		}

		/** @return {@code true} for exactly one caller: the one that gets to drop the group's single piece of loot. */
		public boolean claimLoot()
		{
			return _loot.compareAndSet(false, true);
		}

		/** @return {@code true} for exactly one caller: the member that gets to speak the opening line. */
		public boolean claimSpeech(long now)
		{
			if (_speech.compareAndSet(false, true))
			{
				_warnedAt.set(now);
				return true;
			}
			return false;
		}

		/** @return when the opening line was spoken, or 0 if it has not been. */
		public long warnedAt()
		{
			return _warnedAt.get();
		}

		/** @return {@code true} for exactly one caller: the member that gets to speak the closing line. */
		public boolean claimWinLine()
		{
			return _winLine.compareAndSet(false, true);
		}

		/** Called by the first member to attack; every other member then joins in. */
		public void startFight()
		{
			_fighting = true;
		}

		public boolean isFighting()
		{
			return _fighting;
		}
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
	 * Picks a tier by weight.
	 * @param weights the weights in {@link Tier} order (negative counts as 0)
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
		for (int i = 0; (i < weights.length) && (i < Tier.values().length); i++)
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

	/** @return the tier's weight for this player: 0 when the tier is off or the player is below that tier's minimum level. */
	public static int weightFor(int configuredWeight, int playerLevel, int tierMinPlayerLevel)
	{
		return ((configuredWeight <= 0) || (playerLevel < tierMinPlayerLevel)) ? 0 : configuredWeight;
	}

	/**
	 * The actor's level: the player's level plus an offset picked from [minOffset, maxOffset], clamped to [1, 80].
	 * @param roll any number (the caller's random roll)
	 */
	public static int levelFor(int playerLevel, int minOffset, int maxOffset, int roll)
	{
		final int lo = Math.min(minOffset, maxOffset);
		final int hi = Math.max(minOffset, maxOffset);
		return Math.max(1, Math.min(80, playerLevel + lo + (Math.abs(roll) % ((hi - lo) + 1))));
	}

	/**
	 * The enchant on the actor's weapon and armor: a value in [min, max] (clamped to 0-30).
	 * @param roll any number (the caller's random roll)
	 */
	public static int enchantIn(int min, int max, int roll)
	{
		final int a = Math.max(0, Math.min(30, min));
		final int b = Math.max(0, Math.min(30, max));
		final int lo = Math.min(a, b);
		final int hi = Math.max(a, b);
		return lo + (Math.abs(roll) % ((hi - lo) + 1));
	}

	/**
	 * How many phantoms an encounter sends. Wimp, Normie and Hard match the party (at least one); the Horsemen match
	 * it too but never fewer than {@code horsemenMin}; the lone PKer is always one. Capped at {@code cap}.
	 * @param partySize members in the player's party, counting the player (1 when solo)
	 */
	public static int groupSize(Tier tier, int partySize, int horsemenMin, int cap)
	{
		final int limit = Math.max(1, cap);
		switch (tier)
		{
			case PKER:
			{
				return 1;
			}
			case HORSEMEN:
			{
				return Math.max(1, Math.min(limit, Math.max(partySize, horsemenMin)));
			}
			default:
			{
				return Math.max(1, Math.min(limit, partySize));
			}
		}
	}

	/** @return {@code true} for the tiers that attack the moment they reach the player, with no waiting for an opening. */
	public static boolean strikesOnArrival(Tier tier)
	{
		return (tier == Tier.HORSEMEN) || (tier == Tier.PKER);
	}

	/** @return {@code true} once an opportunist (Normie, Hard) should strike: the player is mid-fight with a monster, or has stood still long enough. */
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
}
