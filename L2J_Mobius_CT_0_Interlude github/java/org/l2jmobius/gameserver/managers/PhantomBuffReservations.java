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

import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure, game-type-free registry backing the cross-caster buff reservations used by {@link PhantomBuffs}.
 * <p>
 * Several independent bot buff sources can target the same player - the recruited-party supports
 * ({@code PhantomPartyManager}) and the personal support buddy ({@code PhantomBuddyManager}). A cast is not
 * instant, so if two of them both decide to cast the same buff inside the cast-time window, each sees "not
 * buffed yet" and both cast, landing the buff twice (a double icon, and for some buffs a doubled stat). A caster
 * claims a {@code (target, skill)} slot just before casting; another caster that finds it claimed by a
 * <i>different</i> caster skips that buff. Claims carry an expiry so nothing needs an explicit release.
 * <p>
 * The time source and the composed key are passed in, so this is fully deterministic and unit-testable with a
 * plain JDK (see {@code tests/java/PhantomBuffReservationsTest.java}); {@link PhantomBuffs} feeds it
 * {@link System#currentTimeMillis()} and the real object ids. {@link ConcurrentHashMap#compute} makes each claim
 * atomic, so simultaneous ticks on different threads can never both win the same slot.
 */
public final class PhantomBuffReservations
{
	// Key -> {expiryMillis, casterId}. Bounded by opportunistic pruning of expired entries.
	private final ConcurrentHashMap<Long, long[]> _reservations = new ConcurrentHashMap<>();

	// Above this many live entries, drop the expired ones so a long uptime can't grow the map without bound.
	private static final int PRUNE_THRESHOLD = 1024;

	/**
	 * Composes the reservation key for a {@code (target, skill)} pair. Object ids and skill ids are ints, so they
	 * pack losslessly into the high/low halves of a long.
	 * @param targetObjectId the buff target's object id
	 * @param skillId the buff skill id
	 * @return a unique key for this target/skill pair
	 */
	public static long key(int targetObjectId, int skillId)
	{
		return ((long) targetObjectId << 32) | (skillId & 0xFFFFFFFFL);
	}

	/**
	 * Attempts to claim a slot. A slot is granted when it is free, expired, or already held by this same caster
	 * (a re-claim just refreshes it); it is refused only while a <i>different</i> caster holds an unexpired claim.
	 * @param key the {@link #key(int, int)} of the target/skill pair
	 * @param nowMillis the current time
	 * @param casterId the claiming caster's id
	 * @param holdMillis how long the claim is held from {@code nowMillis}
	 * @return {@code true} if the caster may cast now; {@code false} if a different caster is already holding it
	 */
	public boolean reserve(long key, long nowMillis, int casterId, int holdMillis)
	{
		final boolean[] granted =
		{
			false
		};
		_reservations.compute(key, (k, cur) ->
		{
			if ((cur != null) && (cur[0] > nowMillis) && (cur[1] != casterId))
			{
				granted[0] = false;
				return cur; // still held by a different caster
			}
			granted[0] = true;
			return new long[]
			{
				nowMillis + holdMillis,
				casterId
			};
		});
		if (_reservations.size() > PRUNE_THRESHOLD)
		{
			_reservations.values().removeIf(v -> v[0] <= nowMillis);
		}
		return granted[0];
	}

	/**
	 * Read-only peek: is the slot currently held by a caster <i>other</i> than {@code askerId}? Unlike
	 * {@link #reserve}, this takes no claim - it lets a caster see a slot is already spoken for (e.g. a rezzer
	 * checking whether another rezzer is already on a corpse) and pick a different target, deferring the actual
	 * atomic claim to {@code reserve} once every other gate has passed.
	 * @param key the {@link #key(int, int)} of the slot
	 * @param nowMillis the current time
	 * @param askerId the caster asking (its own live claim does not count as held by another)
	 * @return {@code true} if a different caster holds an unexpired claim on the slot
	 */
	public boolean isHeldByOther(long key, long nowMillis, int askerId)
	{
		final long[] cur = _reservations.get(key);
		return (cur != null) && (cur[0] > nowMillis) && (cur[1] != askerId);
	}

	/** Return a refused cast's claim without disturbing a claim now held by a different caster. */
	public void release(long key, int casterId)
	{
		_reservations.computeIfPresent(key, (k, cur) -> (cur[1] == casterId) ? null : cur);
	}

	/** @return the number of live entries (test/diagnostic use). */
	public int size()
	{
		return _reservations.size();
	}
}
