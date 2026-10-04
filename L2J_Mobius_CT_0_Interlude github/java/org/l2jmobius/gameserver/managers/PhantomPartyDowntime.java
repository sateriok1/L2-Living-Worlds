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

import java.util.LinkedHashMap;
import java.util.Map;

/** Coordinates the independent free-hunt scanner and party tick between fights. */
public final class PhantomPartyDowntime
{
	public enum Recovery
	{
		CONTINUE, REST, STAND
	}

	public static final int MP_SIT = 30; // casters may still finish a fight below this when a skill is available
	public static final int REST_SIT = 30; // between fights a member sits when its lowest relevant resource is below this
	public static final int REST_STAND = 90; // ...and stands again once it is back to this
	public static final int FULL = 100; // resting with the leader or on a "sit" order tops up to full
	private static final long HANDOFF_MS = 2500;
	private static final long LOOT_RETRY_MS = 30000;

	public static final class State
	{
		private int _deadTargetId;
		private boolean _recoveryRequested;
		private long _handoffUntil;
		private long _managedUntil;
		private final Map<Integer, Long> _lootRetryAt = new LinkedHashMap<>();

		/** A dead target gets one bounded window before AutoPlay may replace it, even if the party tick stalls. */
		public synchronized boolean deferScan(int deadTargetId, boolean recoveryRequested, long now)
		{
			if (((deadTargetId != 0) && (deadTargetId != _deadTargetId)) || (recoveryRequested && !_recoveryRequested))
			{
				_handoffUntil = now + HANDOFF_MS;
			}
			if (deadTargetId != 0)
			{
				_deadTargetId = deadTargetId;
			}
			_recoveryRequested = recoveryRequested;
			return (now < _handoffUntil) || (now < _managedUntil);
		}

		/** Only a functioning party tick renews ownership of a loot walk, recovery or regroup. */
		public synchronized void pause(long now)
		{
			_managedUntil = now + HANDOFF_MS;
		}

		public synchronized void resume()
		{
			_handoffUntil = 0;
			_managedUntil = 0;
		}

		public synchronized void deferLoot(int itemId, long now)
		{
			_lootRetryAt.entrySet().removeIf(entry -> entry.getValue() <= now);
			_lootRetryAt.put(itemId, now + LOOT_RETRY_MS);
			while (_lootRetryAt.size() > 64)
			{
				_lootRetryAt.remove(_lootRetryAt.keySet().iterator().next());
			}
		}

		public synchronized boolean lootDeferred(int itemId, long now)
		{
			return now < _lootRetryAt.getOrDefault(itemId, 0L);
		}
	}

	private PhantomPartyDowntime()
	{
	}

	/** The resource that decides a rest: the lower of MP and HP for a class that lives on MP, HP alone for the rest. */
	public static int need(boolean mpUser, int mp, int hp)
	{
		return mpUser ? Math.min(mp, hp) : hp;
	}

	/**
	 * @param need the lowest relevant resource percent, from {@link #need}
	 * @param leaderSitting the party leader is sitting: members join in whenever they are not full
	 * @param topUp the current rest began with the leader or on an order, so it lasts until full
	 * @param sitOrdered an explicit "sit" order: sit now and stay down until another order or a threat
	 */
	public static Recovery recovery(boolean sitting, int need, boolean threat, boolean ownerFar, boolean fighting, boolean starved, boolean standSuppressed, boolean leaderSitting, boolean topUp, boolean sitOrdered)
	{
		if (sitting)
		{
			if (threat || ownerFar || standSuppressed)
			{
				return Recovery.STAND;
			}
			if (sitOrdered)
			{
				return Recovery.REST;
			}
			return (need >= ((topUp || leaderSitting) ? FULL : REST_STAND)) ? Recovery.STAND : Recovery.REST;
		}
		if (threat || ownerFar || standSuppressed || (fighting && !starved))
		{
			return Recovery.CONTINUE;
		}
		if (sitOrdered)
		{
			return Recovery.REST;
		}
		return (need < (leaderSitting ? FULL : REST_SIT)) ? Recovery.REST : Recovery.CONTINUE;
	}
}
