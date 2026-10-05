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
 * Pure rules for the phantom encounter engine: a phantom, or a group, that is sent for one player and fights them
 * once. The engine itself is generic. It knows nothing about kinds, odds, rewards or timers: a module decides all of
 * that and hands the engine a {@link Style} and a {@link Listener} (see {@code ModuleEncounters}). No world access
 * here, so every decision unit tests without a server.
 */
public final class PhantomEncounterRules
{
	/** How an encounter actor picks its moment to start the fight. */
	public enum Approach
	{
		/** Attacks the moment it reaches the player. */
		STRIKE_ON_ARRIVAL,
		/** Walks up, says an opening line, and attacks after a short warning (or at once if hit first). */
		ASK_FIRST,
		/** Closes in quietly and attacks when the player stands still or is busy with a monster. */
		WAIT_FOR_MOMENT
	}

	/** Called by the engine when the player wins. Ids, not objects, so this stays free of world types. */
	public interface Listener
	{
		/**
		 * Every actor of the encounter is dead.
		 * @param victimObjectId the player the encounter came for
		 * @param lastActorObjectId the actor that died last
		 */
		void onGroupDefeated(int victimObjectId, int lastActorObjectId);
	}

	/** How one encounter behaves. Immutable. */
	public static final class Style
	{
		public static final String[] NO_LINES = new String[0];

		public final Approach approach;
		public final int approachSeconds;
		public final int fightSeconds;
		public final int warnSeconds;
		public final int stillSeconds;
		public final String[] askLines;
		public final String[] winLines;

		/**
		 * @param approach how the actors pick their moment
		 * @param approachSeconds how long they may take to reach the player before giving up (minimum 10)
		 * @param fightSeconds the longest the fight may last (minimum 30)
		 * @param warnSeconds ASK_FIRST: how long after the opening line they attack (minimum 1)
		 * @param stillSeconds WAIT_FOR_MOMENT: how long the player must stand still before they attack (minimum 1)
		 * @param askLines ASK_FIRST: the opening lines, one is said (may be empty)
		 * @param winLines said when the actors beat the player (may be empty)
		 */
		public Style(Approach approach, int approachSeconds, int fightSeconds, int warnSeconds, int stillSeconds, String[] askLines, String[] winLines)
		{
			this.approach = (approach == null) ? Approach.STRIKE_ON_ARRIVAL : approach;
			this.approachSeconds = Math.max(10, approachSeconds);
			this.fightSeconds = Math.max(30, fightSeconds);
			this.warnSeconds = Math.max(1, warnSeconds);
			this.stillSeconds = Math.max(1, stillSeconds);
			this.askLines = (askLines == null) ? NO_LINES : askLines.clone();
			this.winLines = (winLines == null) ? NO_LINES : winLines.clone();
		}
	}

	/** The members of one encounter share this, so a group speaks once, warns once, and strikes together. */
	public static final class EncounterGroup
	{
		private final AtomicBoolean _speech = new AtomicBoolean();
		private final AtomicInteger _dead = new AtomicInteger();
		private volatile int _size;
		private final AtomicBoolean _winLine = new AtomicBoolean();
		private final AtomicLong _warnedAt = new AtomicLong();
		private volatile boolean _fighting;
		private final Style _style;
		private final Listener _listener;

		public EncounterGroup(int size, Style style, Listener listener)
		{
			_size = Math.max(1, size);
			_style = (style == null) ? new Style(Approach.STRIKE_ON_ARRIVAL, 60, 240, 7, 4, null, null) : style;
			_listener = listener;
		}

		public Style style()
		{
			return _style;
		}

		/** @return who to tell when the player wins, or {@code null} */
		public Listener listener()
		{
			return _listener;
		}

		/** The group turned out smaller than planned (some phantoms could not spawn). */
		public void shrinkTo(int size)
		{
			_size = Math.max(1, size);
		}

		/** @return how many members the group has (after any shrink) */
		public int size()
		{
			return _size;
		}

		/** @return how many members are down (dead or escaped) */
		public int deadCount()
		{
			return _dead.get();
		}

		/** @return {@code true} when this death is the one that wipes the group (every member is now dead). */
		public boolean memberDied()
		{
			return _dead.incrementAndGet() >= _size;
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
	// Player.isAutoAttackable so an encounter actor's skills and attacks are legal against an unflagged victim. Empty
	// unless a module has sent an encounter, so the stock rule is untouched.
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

	/** @return {@code true} once a WAIT_FOR_MOMENT actor should strike: the player is mid-fight with a monster, or has stood still long enough. */
	public static boolean momentReady(boolean victimBusyWithMonster, long victimStillMs, long stillNeededMs)
	{
		return victimBusyWithMonster || (victimStillMs >= stillNeededMs);
	}

	/** @return {@code true} once an ASK_FIRST actor's warning period has passed (or it was hit first). */
	public static boolean mayStrikeAfterWarning(long now, long warnedAt, long warnMs, boolean hitFirst)
	{
		return hitFirst || ((warnedAt > 0) && ((now - warnedAt) >= warnMs));
	}
}
